#!/usr/bin/env bash
#
# Spawn one Quack node - invoked by LocalQuackBackend.
#
# Usage:
#   spawn-quack-node.sh <port>
#
# The non-sensitive metastore connection details come from environment variables that
# LocalQuackBackend.start() sets from CreatePoolRequest.metastore. The
# env-var keys match the UI form field names verbatim:
#
#   pgHost pgPort pgUser               (Postgres for DuckLake catalog)
#   dbName                             (Postgres DB; also the DuckDB catalog alias unless
#                                       catalogAlias is set)
#   catalogAlias                       (optional DuckDB catalog alias the database is ATTACHed
#                                       under; defaults to $dbName. Branch catalogs set it to
#                                       their parent's alias, see TenantDb.catalogAlias)
#   schemaName                         (DuckLake schema under the alias; default `main`.
#                                       MUST differ from the alias or 2-part identifiers
#                                       like "$dbName"."customer" resolve as ambiguous.)
#   dataPath                           (DuckLake data files directory)
#
# Sensitive values NEVER arrive through the environment or the command line: a node user with SQL
# can read getenv() and /proc/<pid>/{environ,cmdline} of the duckdb process and of this script.
# They arrive as one file per key in the directory $QOD_SECRETS_DIR (see SECRET_KEYS below; the
# Scala twin is NodeSecretFiles.Keys). The local and fleet launchers create that directory private
# to the current user (0700, files 0600) and set QOD_SECRETS_DIR_EPHEMERAL=1, and this script
# deletes it as soon as the values are read. On Kubernetes it is a read-only Secret volume. A key
# still present in the environment is ignored (with a warning) rather than used.
#
# DuckDB is kept alive after `quack_serve` returns via a FIFO held open
# by this shell, so the background quack thread keeps serving until the
# process is terminated (SIGTERM from `LocalQuackBackend.stop`).
#
# duckdb runs in an empty, private (0700) directory of its own, created here and removed when the
# node stops: DuckDB 2.0 replacement-scans an unquoted dotted name as a file (`SELECT * FROM x.csv`
# reads ./x.csv), and an unquoted name cannot hold a path separator, so the working directory is
# the one place that form reaches. Every relative path the node relies on (dataPath, the duckdb
# binary, the spill directory) is made absolute against the directory this script started in.

set -euo pipefail

# DuckDB 2.0's CLI switches to agent mode (markdown tables, JSON errors) when an agent env var
# (AI_AGENT, CLAUDECODE) is set and stdout is not a terminal; pin the normal output format.
export DUCKDB_AGENT_MODE=0

PORT="${1:?port required}"

# The directory relative paths resolve against (the manager's or join process's working directory,
# where duckdb itself used to run). Physical, so it matches the manager JVM's own resolution.
START_DIR="$(pwd -P)"

# Keep in sync with NodeSecretFiles.Keys (Scala), SECRET_KEYS in spawn-quack-node.ps1 and
# _SECRET_KEYS in cli/src/qod_cli/fleet_join.py; NodeSecretFilesSpec pins all four.
SECRET_KEYS=(pgPassword encryptionKey extraSetupSql objectStoreSql QOD_NODE_TOKEN
  QOD_S3_ACCESS_KEY_ID QOD_S3_SECRET_ACCESS_KEY QOD_AZURE_CONNECTION_STRING QOD_GCS_KEY_ID
  QOD_GCS_SECRET)
for key in "${SECRET_KEYS[@]}"; do
  if [[ -n "${!key+x}" ]]; then
    echo "WARN: ignoring $key from the environment; secrets are read from \$QOD_SECRETS_DIR only" >&2
    unset "$key"
  fi
done
if [[ -n "${QOD_SECRETS_DIR:-}" ]]; then
  for key in "${SECRET_KEYS[@]}"; do
    if [[ -f "$QOD_SECRETS_DIR/$key" ]]; then
      # read -d '' slurps the whole file (newlines included); it returns 1 at EOF by design.
      IFS= read -r -d '' "$key" < "$QOD_SECRETS_DIR/$key" || true
    fi
  done
  if [[ "${QOD_SECRETS_DIR_EPHEMERAL:-}" == "1" ]]; then
    rm -rf -- "$QOD_SECRETS_DIR"
  fi
fi
# Plain shell variables from here on: nothing below exports them, so neither duckdb nor psql
# inherits them.
export -n "${SECRET_KEYS[@]}" QOD_SECRETS_DIR QOD_SECRETS_DIR_EPHEMERAL 2>/dev/null || true
TOKEN="${QOD_NODE_TOKEN:-}"
if [[ -z "$TOKEN" ]]; then
  echo "ERROR: no node token: \$QOD_SECRETS_DIR/QOD_NODE_TOKEN is missing or empty" >&2
  exit 1
fi

pgHost="${pgHost:-localhost}"
pgPort="${pgPort:-5432}"
pgUser="${pgUser:-postgres}"
pgPassword="${pgPassword:-azizam}"
dbName="${dbName:-db1}"
catalogAlias="${catalogAlias:-$dbName}"
schemaName="${schemaName:-main}"
dataPath="${dataPath:-/Users/hayssams/git/public/quack-on-demand/ducklake/$dbName}"

kind="${kind:?kind env var is required (ducklake | duckdb-file | memory)}"
case "$kind" in
  ducklake|duckdb-file|memory) ;;
  *)
    echo "fatal: unknown kind='$kind' (expected: ducklake | duckdb-file | memory)" >&2
    exit 92
    ;;
esac

if [[ "$schemaName" == "$catalogAlias" ]]; then
  echo "ERROR: schemaName ($schemaName) must differ from the catalog alias ($catalogAlias)." >&2
  echo "       DuckDB rejects 2-part identifiers like \"$catalogAlias\".<table> as" >&2
  echo "       ambiguous when a catalog and a schema share a name." >&2
  exit 1
fi

# Detect whether dataPath points at a remote object store. DuckLake accepts
# s3:// (covers AWS S3, SeaweedFS, MinIO, R2, GCS via the S3-interop endpoint)
# and azure:// / abfss:// when the matching DuckDB extension is loaded. For
# remote schemes we skip the local mkdir (the parent dir doesn't exist on the
# container fs) and emit the SQL needed to install httpfs/azure + a SECRET so
# the ATTACH below can read/write parquet against the bucket.
IS_REMOTE=0
STORAGE_SQL=""
case "$dataPath" in
  s3://*|s3a://*|gs://*|r2://*)
    IS_REMOTE=1
    STORAGE_SQL="INSTALL httpfs;"$'\n'"LOAD httpfs;"
    if [[ -n "${QOD_S3_ACCESS_KEY_ID:-}" && -n "${QOD_S3_SECRET_ACCESS_KEY:-}" ]]; then
      # Strip http(s):// from the endpoint - DuckDB wants "host:port".
      ep="${QOD_S3_ENDPOINT:-}"
      ep="${ep#http://}"; ep="${ep#https://}"; ep="${ep%/}"
      STORAGE_SQL="$STORAGE_SQL
CREATE OR REPLACE SECRET quack_s3 (
  TYPE s3,
  KEY_ID '${QOD_S3_ACCESS_KEY_ID}',
  SECRET '${QOD_S3_SECRET_ACCESS_KEY}',
  REGION '${QOD_S3_REGION:-us-east-1}',
  ENDPOINT '${ep}',
  URL_STYLE '${QOD_S3_URL_STYLE:-path}',
  USE_SSL ${QOD_S3_USE_SSL:-true}
);"
    fi
    ;;
  az://*|azure://*|abfss://*)
    IS_REMOTE=1
    STORAGE_SQL="INSTALL azure;"$'\n'"LOAD azure;"
    if [[ -n "${QOD_AZURE_CONNECTION_STRING:-}" ]]; then
      STORAGE_SQL="$STORAGE_SQL
CREATE OR REPLACE SECRET quack_azure (
  TYPE azure,
  CONNECTION_STRING '${QOD_AZURE_CONNECTION_STRING}'
);"
    fi
    ;;
esac

# A relative local dataPath (the bundled default `./ducklake/tpch` is one) used to resolve against
# duckdb's working directory; resolve it against the starting directory instead, since duckdb no
# longer runs there. DuckLake refuses a DATA_PATH that differs from the one recorded at catalog
# creation (still the relative spelling), so the ATTACH then passes OVERRIDE_DATA_PATH, which applies
# to this attach only and rewrites nothing in the catalog.
DATA_PATH_OVERRIDE=""
if [[ "$IS_REMOTE" == "0" && -n "$dataPath" && "$dataPath" != /* ]]; then
  while [[ "$dataPath" == ./* ]]; do dataPath="${dataPath#./}"; done
  dataPath="$START_DIR/$dataPath"
  DATA_PATH_OVERRIDE=", OVERRIDE_DATA_PATH true"
fi
# The starting directory is not validated like the rest of the metastore: double any quote.
data_path_sql=${dataPath//\'/\'\'}

# mkdir behaviour differs by kind: for `ducklake`, dataPath is a parquet
# DIRECTORY, so mkdir -p on the path itself is correct. For `duckdb-file`,
# dataPath is a .duckdb FILE path - mkdir -p on the path itself would create
# a directory there and the later `ATTACH '$dataPath'` fails with "Is a
# directory"; only the parent directory needs to exist. For `memory`, there
# is no on-disk path at all.
# Not dry-run-gated: SpawnScriptEncryptionSpec supplies a writable temp dataPath, so this
# exercises the same filesystem preparation a real spawn does.
if [[ "$IS_REMOTE" == "0" ]]; then
  case "$kind" in
    ducklake)
      mkdir -p "$dataPath"
      ;;
    duckdb-file)
      if [[ -n "$dataPath" ]]; then
        mkdir -p "$(dirname "$dataPath")"
      fi
      ;;
    memory)
      : # no on-disk dataPath to create
      ;;
  esac
fi

# Resolve the duckdb CLI. $DUCKDB_BIN wins (an absolute path the launcher sets to
# the provisioned exe); otherwise the first `duckdb` on PATH. Mirrors
# spawn-quack-node.ps1 so the manager can pin duckdb without relying on PATH
# inheritance reaching this spawned process.
# The presence check is skipped in dry-run mode: SpawnScriptEncryptionSpec only exercises
# INIT_SQL assembly, which is pure string building and needs no duckdb binary at all.
DUCKDB="${DUCKDB_BIN:-duckdb}"
if [[ "${QOD_SPAWN_DRY_RUN:-}" != "1" ]]; then
  command -v "$DUCKDB" >/dev/null 2>&1 || {
    echo "ERROR: duckdb not found (DUCKDB_BIN='${DUCKDB_BIN:-}', 'duckdb' not on PATH)" >&2
    exit 1
  }
  # Absolute, since duckdb starts in its own directory (a relative DUCKDB_BIN or PATH entry
  # would otherwise resolve there).
  DUCKDB="$(command -v "$DUCKDB")"
  [[ "$DUCKDB" == /* ]] || DUCKDB="$START_DIR/$DUCKDB"
fi

# Per-node private state, all removed by cleanup() when the node stops: NODE_DIR (0700, from mktemp)
# holds the FIFO, the psql passfile while it is needed, and WORK_DIR, the empty directory duckdb
# runs in. Created in dry-run mode too, so the SQL below names the same spill directory a real
# spawn would.
NODE_DIR="$(mktemp -d "${TMPDIR:-/tmp}"/quack-node.XXXXXX)"
SPILL_DIR=""
DUCK_PID=""
cleanup() {
  if [[ -n "$DUCK_PID" ]]; then
    kill -TERM "$DUCK_PID" 2>/dev/null || true
    wait "$DUCK_PID" 2>/dev/null || true
  fi
  exec 9>&- 2>/dev/null || true
  rm -rf -- "$NODE_DIR" ${SPILL_DIR:+"$SPILL_DIR"}
}
trap cleanup TERM INT EXIT
WORK_DIR="$NODE_DIR/cwd"
mkdir -m 700 "$WORK_DIR"
# DuckDB's spill directory defaults to `.tmp` under its working directory, which no longer persists
# across nodes. Give each node its own, on the filesystem it spilled to before: the emptyDir
# KubernetesQuackBackend mounts on every pod when present, else `.tmp` of the starting directory
# (not TMPDIR, which is often RAM-backed on Linux). Per node so that under lockdown, where DuckDB
# admits its temp directory, no node can read another's spill files. NODE_DIR when neither works.
if [[ -d /duckdb-tmp && -w /duckdb-tmp ]]; then
  SPILL_ROOT=/duckdb-tmp
else
  SPILL_ROOT="$START_DIR/.tmp"
fi
SPILL_DIR="$(mkdir -p "$SPILL_ROOT" 2>/dev/null && mktemp -d "$SPILL_ROOT/qod-node.XXXXXX" 2>/dev/null)" ||
  SPILL_DIR=""
if [[ -z "$SPILL_DIR" ]]; then
  SPILL_DIR="$NODE_DIR/tmp"
  mkdir -m 700 "$SPILL_DIR"
fi

# Ensure the Postgres database $dbName exists. Connects to PG_ADMIN_DB (default
# `postgres`) as admin and runs CREATE DATABASE if missing. Skipped when psql
# isn't available - the DuckLake ATTACH below will fail loudly in that case.
# Only needed for kind=ducklake.
# Still dry-run-gated (unlike mkdir above): SpawnScriptEncryptionSpec's ducklake cases use a
# fake pgHost, and a real psql invocation would attempt a network connection to it - DNS
# resolution of an unresolvable host has no bounded latency guarantee, so this would make the
# test's runtime depend on the test machine's network/resolver behavior.
if [[ "$kind" == "ducklake" && "${QOD_SPAWN_DRY_RUN:-}" != "1" ]] && command -v psql >/dev/null 2>&1; then
  ADMIN_DB="${PG_ADMIN_DB:-postgres}"
  # The password reaches psql through a private passfile, never PGPASSWORD: psql's own
  # environment is as readable as duckdb's while it runs. It sits in NODE_DIR, so cleanup()
  # removes it on any exit (a kill during psql included), not only on the normal path below.
  PGPASS_FILE="$NODE_DIR/pgpass"
  pgpass_escaped="${pgPassword//\\/\\\\}"
  pgpass_escaped="${pgpass_escaped//:/\\:}"
  (umask 077 && printf '*:*:*:*:%s\n' "$pgpass_escaped" > "$PGPASS_FILE")
  EXISTS=$(PGPASSFILE="$PGPASS_FILE" psql -w -h "$pgHost" -p "$pgPort" -U "$pgUser" \
    -d "$ADMIN_DB" -tAc "SELECT 1 FROM pg_database WHERE datname = '$dbName'" 2>/dev/null || true)
  if [[ "$EXISTS" != "1" ]]; then
    echo "spawn: creating Postgres database $dbName"
    PGPASSFILE="$PGPASS_FILE" psql -w -h "$pgHost" -p "$pgPort" -U "$pgUser" \
      -d "$ADMIN_DB" -tAc "CREATE DATABASE \"$dbName\"" >/dev/null || {
        echo "WARN: CREATE DATABASE $dbName failed; ATTACH below may fail" >&2
      }
  fi
  rm -f -- "$PGPASS_FILE"
fi

# Early guard: in dry-run mode we only want the assembled INIT_SQL (below), never a live
# duckdb process. Skip the FIFO and the duckdb launch entirely rather than starting one and
# killing it - SpawnScriptEncryptionSpec drives this script many times per run.
if [[ "${QOD_SPAWN_DRY_RUN:-}" != "1" ]]; then
  # A named pipe keeps duckdb's stdin live without blocking after we feed the
  # init SQL. fd 9 holds the writer side open until this shell exits.
  FIFO="$NODE_DIR/in"
  mkfifo "$FIFO"

  # Start duckdb first - open() on FIFO blocks until the writer side appears. It runs in WORK_DIR
  # (the subshell execs it, so $! is duckdb's own pid); this script stays where it started.
  (cd "$WORK_DIR" && exec "$DUCKDB") < "$FIFO" &
  DUCK_PID=$!

  # Open the writer end; this unblocks duckdb's open().
  exec 9> "$FIFO"
fi

# DuckDB does NOT honour the HTTP_PROXY / HTTPS_PROXY env vars for
# `INSTALL <extension>` downloads - those have to be set via the SQL
# `SET http_proxy=...` setting (duckdb forwards to libcurl through that
# setting only). Without this, behind a corporate proxy the very first
# INSTALL hangs and the node never reaches `quack_serve`. We emit the
# SET lines only when a proxy env var is present, so non-proxied
# environments are unaffected.
PROXY_SQL=""
PROXY_URL="${HTTP_PROXY:-${http_proxy:-${HTTPS_PROXY:-${https_proxy:-}}}}"
if [[ -n "$PROXY_URL" ]]; then
  # DuckDB wants "host:port" without the scheme prefix.
  HOSTPORT="${PROXY_URL#http://}"
  HOSTPORT="${HOSTPORT#https://}"
  HOSTPORT="${HOSTPORT%/}"
  PROXY_SQL="SET http_proxy = '$HOSTPORT';"
fi

# Cgroup-aware memory_limit / threads, K8s pods only (no-op elsewhere).
#
# DuckDB's own defaults read the HOST, not the container: memory_limit
# defaults to ~80% of host RAM (ignores the cgroup limit a K8s
# `resources.limits.memory` actually sets), and threads defaults to the
# CPU count visible via affinity, which K8s's CFS-quota-based
# `resources.limits.cpu` does not reduce (that throttles time slices, it
# doesn't shrink the visible core count without the rarely-used "static"
# CPU manager policy). Left alone, a pod sized smaller than the node
# either gets OOM-killed by the kernel once it exceeds its real cgroup
# limit (DuckDB never thought it was close, so it never spilled), or
# oversubscribes worker threads onto a fraction of a CPU's worth of
# actual CFS-granted time. Reading the cgroup files directly - rather
# than threading a new cpu/memory env var through both backends - stays
# correct for any pool size (fixed tier or custom) with no new plumbing.
# Same pattern as the SF>=10 fix in _load-common.sh's
# cgroup_memory_bytes/default_memory_limit, but tuned differently: that
# script shares its cgroup with the manager JVM during a local demo load
# (hence a conservative 40%), whereas a node pod runs nothing but DuckDB,
# so 85% (in line with general DuckDB memory-tuning guidance) is safe.
# temp_directory is the per-node SPILL_DIR chosen above, on every runtime.
RESOURCE_SQL="SET temp_directory = '${SPILL_DIR//\'/\'\'}';"$'\n'
if [[ -r /sys/fs/cgroup/memory.max ]]; then
  MEM_BYTES="$(cat /sys/fs/cgroup/memory.max 2>/dev/null)"        # cgroup v2
elif [[ -r /sys/fs/cgroup/memory/memory.limit_in_bytes ]]; then
  MEM_BYTES="$(cat /sys/fs/cgroup/memory/memory.limit_in_bytes 2>/dev/null)"  # cgroup v1
else
  MEM_BYTES=""
fi
if [[ "$MEM_BYTES" =~ ^[0-9]+$ ]] && (( MEM_BYTES > 0 && MEM_BYTES < 9000000000000000000 )); then
  MEM_MIB=$(( MEM_BYTES / 1024 / 1024 * 85 / 100 ))
  (( MEM_MIB < 256 )) && MEM_MIB=256
  RESOURCE_SQL+="SET memory_limit = '${MEM_MIB}MiB';"$'\n'
fi
if [[ -r /sys/fs/cgroup/cpu.max ]]; then
  read -r CPU_QUOTA CPU_PERIOD < /sys/fs/cgroup/cpu.max              # cgroup v2: "<quota> <period>"
  if [[ "$CPU_QUOTA" =~ ^[0-9]+$ && "$CPU_PERIOD" =~ ^[0-9]+$ && "$CPU_PERIOD" -gt 0 ]]; then
    CPU_THREADS=$(( (CPU_QUOTA + CPU_PERIOD - 1) / CPU_PERIOD ))     # ceil(quota / period)
    (( CPU_THREADS < 1 )) && CPU_THREADS=1
    RESOURCE_SQL+="SET threads = ${CPU_THREADS};"$'\n'
  fi
elif [[ -r /sys/fs/cgroup/cpu/cpu.cfs_quota_us && -r /sys/fs/cgroup/cpu/cpu.cfs_period_us ]]; then
  CPU_QUOTA="$(cat /sys/fs/cgroup/cpu/cpu.cfs_quota_us 2>/dev/null)"   # cgroup v1
  CPU_PERIOD="$(cat /sys/fs/cgroup/cpu/cpu.cfs_period_us 2>/dev/null)"
  if [[ "$CPU_QUOTA" =~ ^[0-9]+$ && "$CPU_PERIOD" =~ ^[0-9]+$ && "$CPU_PERIOD" -gt 0 ]]; then
    CPU_THREADS=$(( (CPU_QUOTA + CPU_PERIOD - 1) / CPU_PERIOD ))
    (( CPU_THREADS < 1 )) && CPU_THREADS=1
    RESOURCE_SQL+="SET threads = ${CPU_THREADS};"$'\n'
  fi
fi

# Feed init SQL. Per-kind branching below:
#   ducklake    - INSTALL/ATTACH the DuckLake catalog via a per-dbname
#                 pg_advisory_lock to serialize first-time
#                 __ducklake_metadata creation across concurrent K8s
#                 pod spawns (see guides/ROADMAP.md, issue #3).
#   duckdb-file - ATTACH the on-disk .duckdb file directly. No
#                 advisory lock; per-node file is independent.
#   memory      - DuckDB's built-in 'memory' catalog is the default,
#                 no ATTACH needed. Federation aliases (in extraSetupSql)
#                 are typically the only catalogs available.

# Build init SQL piecemeal based on $kind so kind=memory and
# kind=duckdb-file skip DuckLake-specific setup entirely.
INIT_SQL=""
INIT_SQL+="$PROXY_SQL"$'\n'
# Cgroup-derived memory_limit / temp_directory / threads (see above) run
# right after the proxy settings and before dbInitSql, so an operator's
# own "SET memory_limit=..." / "SET threads=..." in dbInitSql or
# extraSetupSql still wins - DuckDB's SET is a plain reassignment, so the
# later statement is the one that sticks.
INIT_SQL+="$RESOURCE_SQL"
# Per-database init SQL (tenant-db initSql, `dbInitSql` env var). Runs BEFORE
# the quack extension is installed/loaded and before the catalog ATTACH, right
# after the proxy settings, so engine-level defaults (SET memory_limit,
# SET temp_directory, extension INSTALLs) are already in effect when quack
# starts. Pool initSql + federation SQL ride $extraSetupSql further down.
if [[ -n "${dbInitSql:-}" ]]; then
  INIT_SQL+="$dbInitSql"$'\n'
fi
INIT_SQL+=$'INSTALL quack;\nLOAD quack;\n'

# Per-database object-store secret (objectStoreSql env, authored by ObjectStoreSecret.scala).
# A scoped CREATE SECRET for this database's own bucket/credentials, emitted for EVERY kind:
# TenantDb.validate permits a `memory` or `duckdb-file` database to carry an objectStore, and a
# `memory` database of views over s3:// parquet (the `qod serve` remote-prefix case) reads nothing
# without it. Must precede any ATTACH, and runs alongside the global STORAGE_SQL below - DuckDB
# picks the longest matching scope.
if [[ -n "${objectStoreSql:-}" ]]; then
  INIT_SQL+="$objectStoreSql"$'\n'
fi

# Encryption needs OpenSSL, which the httpfs extension provides. Without it DuckDB falls back to
# mbedtls, where 1.4.1+ REFUSES WRITES to an encrypted database file, and the symptom is a silently
# read-only node rather than an error. STORAGE_SQL only loads httpfs for remote data paths, so a
# local encrypted database would miss it.
ENCRYPTION_SQL=""
if [[ "${encrypted:-}" == "true" ]]; then
  ENCRYPTION_SQL=$'INSTALL httpfs;\nLOAD httpfs;\n'
fi
INIT_SQL+="$ENCRYPTION_SQL"

case "$kind" in
  ducklake)
    INIT_SQL+=$'INSTALL ducklake;\nLOAD ducklake;\n'
    INIT_SQL+=$'INSTALL postgres;\nLOAD postgres;\n'
    INIT_SQL+="$STORAGE_SQL"$'\n'
    # The catalog password rides a TEMPORARY secret, never an ATTACH string: DuckDB prints an attach
    # path verbatim in duckdb_databases().path and in connection errors, readable by any SQL user
    # of the node. Both ATTACHes name the secret, and it is dropped right after them (before
    # federation SQL and quack_serve) so no session can attach the catalog again with it. Mirrors
    # CatalogPgSecret.scala.
    # TenantDb.validateSafety already refuses a quote in pgPassword; double one anyway.
    pg_password_sql=${pgPassword//\'/\'\'}
    INIT_SQL+="CREATE OR REPLACE TEMPORARY SECRET qod_catalog_pg (TYPE postgres, PASSWORD '$pg_password_sql');"$'\n'
    INIT_SQL+="ATTACH 'host=$pgHost port=$pgPort dbname=$dbName user=$pgUser' AS qod_init_pg (TYPE postgres, SECRET qod_catalog_pg);"$'\n'
    INIT_SQL+="SELECT * FROM postgres_query('qod_init_pg', 'SELECT pg_advisory_lock(hashtext(''qod-ducklake-init:$dbName''))');"$'\n'
    INIT_SQL+="ATTACH 'ducklake:postgres:host=$pgHost port=$pgPort dbname=$dbName user=$pgUser' AS \"$catalogAlias\""$'\n'
    # Nodes attach as-is: the manager's DuckLakeInitializer migrates older catalogs once per boot.
    if [[ "${encrypted:-}" == "true" ]]; then
      INIT_SQL+="  (DATA_PATH '$data_path_sql', ENCRYPTED$DATA_PATH_OVERRIDE, METADATA_PARAMETERS MAP {'SECRET': 'qod_catalog_pg'});"$'\n'
    else
      INIT_SQL+="  (DATA_PATH '$data_path_sql'$DATA_PATH_OVERRIDE, METADATA_PARAMETERS MAP {'SECRET': 'qod_catalog_pg'});"$'\n'
    fi
    INIT_SQL+="SELECT * FROM postgres_query('qod_init_pg', 'SELECT pg_advisory_unlock(hashtext(''qod-ducklake-init:$dbName''))');"$'\n'
    INIT_SQL+=$'DETACH qod_init_pg;\n'
    INIT_SQL+=$'DROP SECRET IF EXISTS qod_catalog_pg;\n'
    INIT_SQL+="USE \"$catalogAlias\";"$'\n'
    INIT_SQL+="CREATE SCHEMA IF NOT EXISTS \"$schemaName\";"$'\n'
    INIT_SQL+="USE \"$catalogAlias\".\"$schemaName\";"$'\n'
    ;;
  duckdb-file)
    if [[ "${encrypted:-}" == "true" ]]; then
      INIT_SQL+="ATTACH '$data_path_sql' AS \"$catalogAlias\" (ENCRYPTION_KEY '$encryptionKey');"$'\n'
    else
      INIT_SQL+="ATTACH '$data_path_sql' AS \"$catalogAlias\";"$'\n'
    fi
    INIT_SQL+="USE \"$catalogAlias\";"$'\n'
    INIT_SQL+="CREATE SCHEMA IF NOT EXISTS \"$schemaName\";"$'\n'
    INIT_SQL+="USE \"$catalogAlias\".\"$schemaName\";"$'\n'
    ;;
  memory)
    : # nothing; DuckDB's built-in 'memory' catalog is the default
    ;;
esac

if [[ -n "${extraSetupSql:-}" ]]; then
  INIT_SQL+="$extraSetupSql"$'\n'
fi

# Deployment lockdown (lockdownSql env var, authored by NodeLockdown.scala).
# MUST run before quack_serve so the restrictions are in effect before the
# node serves any tenant statement.
if [[ -n "${lockdownSql:-}" ]]; then
  INIT_SQL+="$lockdownSql"$'\n'
fi

# QOD_NODE_BIND: interface the node listens on. 0.0.0.0 keeps local and K8s nodes unchanged; the
# fleet join process passes the server's advertised data interface so a node never listens on a
# management NIC by accident.
NODE_BIND="${QOD_NODE_BIND:-0.0.0.0}"
# DuckDB 2.0 serves HTTPS by default off localhost; the manager speaks plain HTTP (node TLS: #27).
INIT_SQL+="CALL quack_serve('quack:${NODE_BIND}:$PORT', token := '$TOKEN', allow_other_hostname := true, disable_ssl := true);"$'\n'

# Test seam: print the assembled init SQL and exit without launching duckdb.
# Used by SpawnScriptEncryptionSpec to assert the emitted SQL, which is the only
# testable surface of a script the manager shells out to.
if [[ "${QOD_SPAWN_DRY_RUN:-}" == "1" ]]; then
  printf '%s' "$INIT_SQL"
  exit 0
fi

printf '%s' "$INIT_SQL" >&9

# Block until duckdb is killed (typically SIGTERM from the manager).
wait "$DUCK_PID"
