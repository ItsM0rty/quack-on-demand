<#
.SYNOPSIS
  Spawn one Quack node on Windows - invoked by LocalQuackBackend.

.DESCRIPTION
  PowerShell mirror of scripts/spawn-quack-node.sh. LocalQuackBackend wraps
  this as `powershell.exe -File spawn-quack-node.ps1 <port>`.

  The non-sensitive metastore connection details arrive as environment
  variables that LocalQuackBackend.start() sets from CreatePoolRequest.metastore.
  The env-var keys match the UI form field names verbatim:

    pgHost pgPort pgUser               (Postgres for DuckLake catalog)
    dbName                             (Postgres DB; also the DuckDB catalog alias unless
                                        catalogAlias is set)
    catalogAlias                       (optional DuckDB catalog alias the database is ATTACHed
                                        under; defaults to $dbName. Branch catalogs set it to
                                        their parent's alias, see TenantDb.catalogAlias)
    schemaName                         (DuckLake schema under the alias; default `main`.
                                        MUST differ from the alias or 2-part identifiers
                                        like "$dbName"."customer" resolve as ambiguous.)
    dataPath                           (DuckLake data files directory)
    kind                               (ducklake | duckdb-file | memory)
    dbInitSql                          (optional per-db init SQL)
    lockdownSql                        (optional engine lockdown SQL, NodeLockdown.scala; runs
                                        before quack_serve)

  Sensitive values (pgPassword, encryptionKey, extraSetupSql, objectStoreSql, the node token and
  the object-store credentials, see $SecretKeys below) NEVER arrive through the environment or the
  command line: another process of the same user can read both. They arrive as one file per key in
  the directory $env:QOD_SECRETS_DIR, which LocalQuackBackend creates with an ACL granting only the
  current user, and which this script deletes as soon as the values are read
  (QOD_SECRETS_DIR_EPHEMERAL=1). A key still present in the environment is ignored with a warning.

  Unlike the bash version there is no FIFO: DuckDB is fed its init SQL over a
  redirected stdin that this script leaves OPEN, so the background quack
  thread keeps serving until the process tree is terminated (LocalQuackBackend
  uses `taskkill /T` from stop()/cleanup()).

  The Postgres `CREATE DATABASE` step the bash script performs as a fallback is
  intentionally omitted here: the manager JVM already provisions the tenant-db
  (DbAdmin.createDatabase via PoolSupervisor.createTenantDb + DuckLakeInitializer).

  duckdb runs in an empty directory private to the current user, created here per node: DuckDB 2.0
  replacement-scans an unquoted dotted name as a file (`SELECT * FROM x.csv` reads .\x.csv), and
  an unquoted name cannot hold a path separator, so the working directory is the one place that
  form reaches. Every relative path the node relies on (dataPath, the duckdb binary, the spill
  directory) is made absolute against the directory this script started in. The node directory
  is removed when duckdb exits; `taskkill /T /F` gives this script no chance to, so each spawn
  also removes the node directories whose owning script is gone. Mirrors spawn-quack-node.sh.
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory = $true, Position = 0)] [int]    $Port
)

$ErrorActionPreference = 'Stop'

# The directory relative paths resolve against (the manager's working directory, where duckdb
# itself used to run).
$startDir = (Get-Location).ProviderPath

# Keep in sync with NodeSecretFiles.Keys (Scala) and SECRET_KEYS in spawn-quack-node.sh;
# NodeSecretFilesSpec pins both.
$SecretKeys = @('pgPassword', 'encryptionKey', 'extraSetupSql', 'objectStoreSql', 'QOD_NODE_TOKEN',
  'QOD_S3_ACCESS_KEY_ID', 'QOD_S3_SECRET_ACCESS_KEY', 'QOD_AZURE_CONNECTION_STRING',
  'QOD_GCS_KEY_ID', 'QOD_GCS_SECRET')
$Secrets = @{}
foreach ($k in $SecretKeys) {
  if ($null -ne [Environment]::GetEnvironmentVariable($k)) {
    [Console]::Error.WriteLine("WARN: ignoring $k from the environment; secrets are read from `$QOD_SECRETS_DIR only")
  }
  # Removed from this process's environment so the duckdb child never inherits it.
  [Environment]::SetEnvironmentVariable($k, $null)
}
$secretsDir = [Environment]::GetEnvironmentVariable('QOD_SECRETS_DIR')
if (-not [string]::IsNullOrEmpty($secretsDir)) {
  foreach ($k in $SecretKeys) {
    $f = Join-Path $secretsDir $k
    if (Test-Path -LiteralPath $f -PathType Leaf) {
      $Secrets[$k] = [System.IO.File]::ReadAllText($f)
    }
  }
  if ([Environment]::GetEnvironmentVariable('QOD_SECRETS_DIR_EPHEMERAL') -eq '1') {
    Remove-Item -LiteralPath $secretsDir -Recurse -Force -ErrorAction SilentlyContinue
  }
}
[Environment]::SetEnvironmentVariable('QOD_SECRETS_DIR', $null)
[Environment]::SetEnvironmentVariable('QOD_SECRETS_DIR_EPHEMERAL', $null)

function Secret-Or([string]$name, [string]$default) {
  $v = $Secrets[$name]
  if ([string]::IsNullOrEmpty($v)) { return $default } else { return $v }
}

$Token = Secret-Or 'QOD_NODE_TOKEN' ''
if ([string]::IsNullOrEmpty($Token)) {
  Write-Error "ERROR: no node token: `$QOD_SECRETS_DIR\QOD_NODE_TOKEN is missing or empty"; exit 1
}

# DuckDB 2.0's CLI switches to agent mode (markdown tables, JSON errors) when an agent env var
# (AI_AGENT, CLAUDECODE) is set and stdout is not a terminal; pin the normal output format.
$env:DUCKDB_AGENT_MODE = '0'

function Env-Or([string]$name, [string]$default) {
  $v = [Environment]::GetEnvironmentVariable($name)
  if ([string]::IsNullOrEmpty($v)) { return $default } else { return $v }
}

$pgHost     = Env-Or 'pgHost'     'localhost'
$pgPort     = Env-Or 'pgPort'     '5432'
$pgUser     = Env-Or 'pgUser'     'postgres'
$pgPassword = Secret-Or 'pgPassword' 'azizam'
$dbName     = Env-Or 'dbName'     'db1'
$catalogAlias = Env-Or 'catalogAlias' $dbName
$schemaName = Env-Or 'schemaName' 'main'
$dataPath   = Env-Or 'dataPath'   (Join-Path (Get-Location) "ducklake\$dbName")

$kind = [Environment]::GetEnvironmentVariable('kind')
if ([string]::IsNullOrEmpty($kind)) {
  Write-Error "kind env var is required (ducklake | duckdb-file | memory)"; exit 92
}
if ($kind -notin @('ducklake', 'duckdb-file', 'memory')) {
  Write-Error "fatal: unknown kind='$kind' (expected: ducklake | duckdb-file | memory)"; exit 92
}

if ($schemaName -eq $catalogAlias) {
  Write-Error @"
ERROR: schemaName ($schemaName) must differ from the catalog alias ($catalogAlias).
       DuckDB rejects 2-part identifiers like "$catalogAlias".<table> as
       ambiguous when a catalog and a schema share a name.
"@
  exit 1
}

# Detect whether dataPath points at a remote object store. DuckLake accepts
# s3:// (AWS S3, SeaweedFS, MinIO, R2, GCS via the S3-interop endpoint) and
# azure:// / abfss:// when the matching DuckDB extension is loaded. For remote
# schemes we skip the local mkdir and emit the SQL needed to install
# httpfs/azure + a SECRET so the ATTACH below can read/write parquet.
$isRemote   = $false
$storageSql = ''
switch -Regex ($dataPath) {
  '^(s3|s3a|gs|r2)://' {
    $isRemote = $true
    $storageSql = "INSTALL httpfs;`nLOAD httpfs;"
    $s3Key = Secret-Or 'QOD_S3_ACCESS_KEY_ID' ''
    $s3Sec = Secret-Or 'QOD_S3_SECRET_ACCESS_KEY' ''
    if (-not [string]::IsNullOrEmpty($s3Key) -and -not [string]::IsNullOrEmpty($s3Sec)) {
      $ep = Env-Or 'QOD_S3_ENDPOINT' ''
      $ep = $ep -replace '^https?://', '' -replace '/$', ''
      $s3Region   = Env-Or 'QOD_S3_REGION'    'us-east-1'
      $s3UrlStyle = Env-Or 'QOD_S3_URL_STYLE' 'path'
      $s3UseSsl   = Env-Or 'QOD_S3_USE_SSL'   'true'
      $storageSql += @"

CREATE OR REPLACE SECRET quack_s3 (
  TYPE s3,
  KEY_ID '$s3Key',
  SECRET '$s3Sec',
  REGION '$s3Region',
  ENDPOINT '$ep',
  URL_STYLE '$s3UrlStyle',
  USE_SSL $s3UseSsl
);
"@
    }
    break
  }
  '^(az|azure|abfss)://' {
    $isRemote = $true
    $storageSql = "INSTALL azure;`nLOAD azure;"
    $azConn = Secret-Or 'QOD_AZURE_CONNECTION_STRING' ''
    if (-not [string]::IsNullOrEmpty($azConn)) {
      $storageSql += @"

CREATE OR REPLACE SECRET quack_azure (
  TYPE azure,
  CONNECTION_STRING '$azConn'
);
"@
    }
    break
  }
}

# A relative local dataPath used to resolve against duckdb's working directory; resolve it against
# the starting directory instead. DuckLake refuses a DATA_PATH that differs from the one recorded at
# catalog creation (still the relative spelling), so the ATTACH then passes OVERRIDE_DATA_PATH, which
# applies to this attach only and rewrites nothing in the catalog. Mirrors spawn-quack-node.sh.
$dataPathOverride = ''
if (-not $isRemote -and -not [string]::IsNullOrEmpty($dataPath) -and
    -not [System.IO.Path]::IsPathRooted($dataPath)) {
  $dataPath = [System.IO.Path]::GetFullPath((Join-Path $startDir $dataPath))
  $dataPathOverride = ', OVERRIDE_DATA_PATH true'
}
# The starting directory is not validated like the rest of the metastore: double any quote.
$dataPathSql = ([string]$dataPath).Replace("'", "''")

# New-Item behaviour differs by kind: for `ducklake`, dataPath is a parquet
# DIRECTORY, so creating the path itself is correct. For `duckdb-file`,
# dataPath is a .duckdb FILE path - creating a directory at that exact path
# would make the later `ATTACH '$dataPath'` fail ("Is a directory" / file
# already exists as a directory); only the parent directory needs to exist.
# For `memory`, there is no on-disk path at all. Mirrors spawn-quack-node.sh.
if (-not $isRemote) {
  switch ($kind) {
    'ducklake' {
      New-Item -ItemType Directory -Force -Path $dataPath | Out-Null
    }
    'duckdb-file' {
      if (-not [string]::IsNullOrEmpty($dataPath)) {
        $parent = Split-Path -Path $dataPath -Parent
        if (-not [string]::IsNullOrEmpty($parent)) {
          New-Item -ItemType Directory -Force -Path $parent | Out-Null
        }
      }
    }
    'memory' {
      # no on-disk dataPath to create
    }
  }
}

# Resolve the DuckDB CLI. $env:DUCKDB_BIN wins; otherwise the first `duckdb`
# on PATH (run-jar.ps1 prepends the self-installed .duckdb\<ver>\bin).
# The presence check is skipped in dry-run mode: SpawnScriptEncryptionWindowsSpec only
# exercises init-SQL assembly, which is pure string building and needs no duckdb binary at
# all. Mirrors the QOD_SPAWN_DRY_RUN-gated `command -v` check in spawn-quack-node.sh.
$dryRun = ([Environment]::GetEnvironmentVariable('QOD_SPAWN_DRY_RUN') -eq '1')
$duckdbBin = [Environment]::GetEnvironmentVariable('DUCKDB_BIN')
if ([string]::IsNullOrEmpty($duckdbBin)) {
  if ($dryRun) {
    $duckdbBin = 'duckdb'
  } else {
    $cmd = Get-Command duckdb.exe -ErrorAction SilentlyContinue
    if ($null -eq $cmd) { $cmd = Get-Command duckdb -ErrorAction SilentlyContinue }
    if ($null -eq $cmd) { Write-Error "ERROR: duckdb not on PATH"; exit 1 }
    $duckdbBin = $cmd.Source
  }
} elseif (-not $dryRun -and -not [System.IO.Path]::IsPathRooted($duckdbBin)) {
  # Absolute, since duckdb starts in its own directory.
  $cmd = Get-Command $duckdbBin -ErrorAction SilentlyContinue
  if ($null -ne $cmd -and -not [string]::IsNullOrEmpty($cmd.Source)) { $duckdbBin = $cmd.Source }
  else { $duckdbBin = [System.IO.Path]::GetFullPath((Join-Path $startDir $duckdbBin)) }
}

$onWindows = ($env:OS -eq 'Windows_NT')

# A directory only the current user can reach (the analogue of mktemp's 0700).
function New-PrivateDirectory([string]$path) {
  New-Item -ItemType Directory -Path $path -Force | Out-Null
  if ($onWindows) {
    $acl = New-Object System.Security.AccessControl.DirectorySecurity
    $acl.SetAccessRuleProtection($true, $false)
    $me = [System.Security.Principal.WindowsIdentity]::GetCurrent().User
    $acl.AddAccessRule((New-Object System.Security.AccessControl.FileSystemAccessRule(
      $me, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')))
    Set-Acl -LiteralPath $path -AclObject $acl
  } else {
    & chmod 700 $path
  }
}

# Remove a node directory and the spill directory it records.
function Remove-NodeDirectory([string]$dir) {
  $spillFile = Join-Path $dir 'spill.path'
  if (Test-Path -LiteralPath $spillFile -PathType Leaf) {
    $spill = ([System.IO.File]::ReadAllText($spillFile)).Trim()
    if (-not [string]::IsNullOrEmpty($spill)) {
      Remove-Item -LiteralPath $spill -Recurse -Force -ErrorAction SilentlyContinue
    }
  }
  Remove-Item -LiteralPath $dir -Recurse -Force -ErrorAction SilentlyContinue
}

# Per-node private state: $nodeDir holds owner.pid (the owning script's PID and its start ticks),
# spill.path and $workDir, the empty directory duckdb runs in. Node directories of earlier spawns
# whose owning script is gone (taskkill /T /F skipped their cleanup) are removed first. The owner is
# alive only when a process of that PID exists AND started at the recorded time: Windows recycles
# PIDs, so the start time keeps the sweep from sparing (or killing) an unrelated process's dir. A
# dir with no owner.pid yet (still being created) or an unparseable one is left alone.
$tempRoot = [System.IO.Path]::GetTempPath()
Get-ChildItem -LiteralPath $tempRoot -Directory -Filter 'quack-node.*' -ErrorAction SilentlyContinue |
  ForEach-Object {
    $ownerFile = Join-Path $_.FullName 'owner.pid'
    if (Test-Path -LiteralPath $ownerFile -PathType Leaf) {
      $parts = (([System.IO.File]::ReadAllText($ownerFile)).Trim() -split '\s+')
      $owner = 0
      if ($parts.Count -ge 1 -and [int]::TryParse($parts[0], [ref]$owner)) {
        $proc  = Get-Process -Id $owner -ErrorAction SilentlyContinue
        $alive = $false
        if ($null -ne $proc) {
          if ($parts.Count -ge 2) {
            try { $alive = ($proc.StartTime.Ticks.ToString() -eq $parts[1]) } catch { $alive = $true }
          } else {
            $alive = $true
          }
        }
        if (-not $alive) { Remove-NodeDirectory $_.FullName }
      }
    }
  }
$nodeId  = [Guid]::NewGuid().ToString('N').Substring(0, 12)
$nodeDir = Join-Path $tempRoot "quack-node.$nodeId"
New-PrivateDirectory $nodeDir
$workDir = Join-Path $nodeDir 'cwd'
New-PrivateDirectory $workDir
# DuckDB's spill directory defaults to `.tmp` under its working directory, which no longer persists
# across nodes. Give each node its own under `.tmp` of the starting directory (where it spilled
# before), per node so that under lockdown, where DuckDB admits its temp directory, no node can read
# another's spill files. Under $nodeDir when that is not writable.
try {
  $spillDir = Join-Path (Join-Path $startDir '.tmp') "qod-node.$nodeId"
  New-PrivateDirectory $spillDir
} catch {
  $spillDir = Join-Path $nodeDir 'tmp'
  New-PrivateDirectory $spillDir
}
[System.IO.File]::WriteAllText((Join-Path $nodeDir 'spill.path'), $spillDir)
$ownerTicks = ''
try { $ownerTicks = (Get-Process -Id $PID).StartTime.Ticks.ToString() } catch {}
[System.IO.File]::WriteAllText((Join-Path $nodeDir 'owner.pid'), "$PID $ownerTicks")

# DuckDB does NOT honour HTTP_PROXY/HTTPS_PROXY for `INSTALL <extension>`
# downloads - those go through libcurl via the SQL `SET http_proxy=...`
# setting only. Emit the SET line only when a proxy env var is present.
$proxyUrl = $null
foreach ($n in 'HTTP_PROXY', 'http_proxy', 'HTTPS_PROXY', 'https_proxy') {
  $v = [Environment]::GetEnvironmentVariable($n)
  if (-not [string]::IsNullOrEmpty($v)) { $proxyUrl = $v; break }
}
$proxySql = ''
if (-not [string]::IsNullOrEmpty($proxyUrl)) {
  $hostPort = $proxyUrl -replace '^https?://', '' -replace '/$', ''
  $proxySql = "SET http_proxy = '$hostPort';"
}

$dbInitSql    = [Environment]::GetEnvironmentVariable('dbInitSql')
$extraSetupSql = Secret-Or 'extraSetupSql' ''
$lockdownSql  = [Environment]::GetEnvironmentVariable('lockdownSql')
$objectStoreSql = Secret-Or 'objectStoreSql' ''
$encrypted    = [Environment]::GetEnvironmentVariable('encrypted')
$encryptionKey = Secret-Or 'encryptionKey' ''

# Build init SQL piecemeal based on $kind so memory / duckdb-file skip the
# DuckLake-specific setup entirely. Mirrors spawn-quack-node.sh.
$sb = [System.Text.StringBuilder]::new()
# The CLI exits on the first error of an -init file by default; a node has always carried on past
# a failing statement (an unreachable federated source, an offline INSTALL), so keep doing that.
[void]$sb.AppendLine('.bail off')
# First, before any CREATE SECRET or ATTACH: DuckDB loads every persistent secret of the OS user
# (~/.duckdb/stored_secrets) on first secret use, which would put an operator's own cloud keys on
# every node. Reading duckdb_secrets() then puts the secret manager in use, after which DuckDB
# refuses to change the setting from any session. Mirrors spawn-quack-node.sh.
[void]$sb.AppendLine('SET allow_persistent_secrets = false;')
[void]$sb.AppendLine('SELECT count(*) AS qod_secret_manager_ready FROM duckdb_secrets();')
if (-not [string]::IsNullOrEmpty($proxySql))  { [void]$sb.AppendLine($proxySql) }
# Before dbInitSql, so an operator's own SET temp_directory still wins.
[void]$sb.AppendLine("SET temp_directory = '$($spillDir.Replace("'", "''"))';")
if (-not [string]::IsNullOrEmpty($dbInitSql)) { [void]$sb.AppendLine($dbInitSql) }
[void]$sb.AppendLine("INSTALL quack;")
[void]$sb.AppendLine("LOAD quack;")

# Per-database object-store secret, emitted for EVERY kind: a `memory` or `duckdb-file`
# database may carry an objectStore (TenantDb.validate permits it), and a `memory` database
# of views over remote parquet reads nothing without the CREATE SECRET. Must precede ATTACH.
if (-not [string]::IsNullOrEmpty($objectStoreSql)) { [void]$sb.AppendLine($objectStoreSql) }

# Encryption needs OpenSSL, which the httpfs extension provides. Without it DuckDB falls back to
# mbedtls, where 1.4.1+ REFUSES WRITES to an encrypted database file, and the symptom is a silently
# read-only node rather than an error. storageSql only loads httpfs for remote data paths, so a
# local encrypted database would miss it. Mirrors ENCRYPTION_SQL in spawn-quack-node.sh.
# -ceq (case-sensitive) matches bash's `==`, which is also case-sensitive: this script's own
# code always emits the lowercase string "true", but staying case-sensitive keeps the two
# scripts behaving identically for any other caller of the env var.
if ($encrypted -ceq 'true') {
  [void]$sb.AppendLine("INSTALL httpfs;")
  [void]$sb.AppendLine("LOAD httpfs;")
}

switch ($kind) {
  'ducklake' {
    [void]$sb.AppendLine("INSTALL ducklake;")
    [void]$sb.AppendLine("LOAD ducklake;")
    [void]$sb.AppendLine("INSTALL postgres;")
    [void]$sb.AppendLine("LOAD postgres;")
    if (-not [string]::IsNullOrEmpty($storageSql)) { [void]$sb.AppendLine($storageSql) }
    # The catalog password rides a TEMPORARY secret, never an ATTACH string: DuckDB prints an attach
    # path verbatim in duckdb_databases().path and in connection errors, readable by any SQL user
    # of the node. Both ATTACHes name the secret, and it is dropped right after them (before
    # federation SQL and quack_serve) so no session can attach the catalog again with it. Mirrors
    # spawn-quack-node.sh and CatalogPgSecret.scala. TenantDb.validateSafety already refuses a
    # quote in pgPassword; double one anyway.
    $pgPasswordSql = ([string]$pgPassword).Replace("'", "''")
    [void]$sb.AppendLine("CREATE OR REPLACE TEMPORARY SECRET qod_catalog_pg (TYPE postgres, PASSWORD '$pgPasswordSql');")
    [void]$sb.AppendLine("ATTACH 'host=$pgHost port=$pgPort dbname=$dbName user=$pgUser' AS qod_init_pg (TYPE postgres, SECRET qod_catalog_pg);")
    [void]$sb.AppendLine("SELECT * FROM postgres_query('qod_init_pg', 'SELECT pg_advisory_lock(hashtext(''qod-ducklake-init:$dbName''))');")
    [void]$sb.AppendLine("ATTACH 'ducklake:postgres:host=$pgHost port=$pgPort dbname=$dbName user=$pgUser' AS ""$catalogAlias""")
    if ($encrypted -ceq 'true') {
      [void]$sb.AppendLine("  (DATA_PATH '$dataPathSql', ENCRYPTED$dataPathOverride, METADATA_PARAMETERS MAP {'SECRET': 'qod_catalog_pg'});")
    } else {
      [void]$sb.AppendLine("  (DATA_PATH '$dataPathSql'$dataPathOverride, METADATA_PARAMETERS MAP {'SECRET': 'qod_catalog_pg'});")
    }
    [void]$sb.AppendLine("SELECT * FROM postgres_query('qod_init_pg', 'SELECT pg_advisory_unlock(hashtext(''qod-ducklake-init:$dbName''))');")
    [void]$sb.AppendLine("DETACH qod_init_pg;")
    [void]$sb.AppendLine("DROP SECRET IF EXISTS qod_catalog_pg;")
    [void]$sb.AppendLine("USE ""$catalogAlias"";")
    [void]$sb.AppendLine("CREATE SCHEMA IF NOT EXISTS ""$schemaName"";")
    [void]$sb.AppendLine("USE ""$catalogAlias"".""$schemaName"";")
  }
  'duckdb-file' {
    if ($encrypted -ceq 'true') {
      [void]$sb.AppendLine("ATTACH '$dataPathSql' AS ""$catalogAlias"" (ENCRYPTION_KEY '$encryptionKey');")
    } else {
      [void]$sb.AppendLine("ATTACH '$dataPathSql' AS ""$catalogAlias"";")
    }
    [void]$sb.AppendLine("USE ""$catalogAlias"";")
    [void]$sb.AppendLine("CREATE SCHEMA IF NOT EXISTS ""$schemaName"";")
    [void]$sb.AppendLine("USE ""$catalogAlias"".""$schemaName"";")
  }
  'memory' {
    # nothing; DuckDB's built-in 'memory' catalog is the default
  }
}

if (-not [string]::IsNullOrEmpty($extraSetupSql)) { [void]$sb.AppendLine($extraSetupSql) }

# Deployment lockdown (lockdownSql env var, authored by NodeLockdown.scala).
# MUST run before quack_serve so the restrictions are in effect before the
# node serves any tenant statement.
if (-not [string]::IsNullOrEmpty($lockdownSql)) { [void]$sb.AppendLine($lockdownSql) }

# SELECT only listen_url (not the CALL's row) so the node token never reaches the inherited stdout.
# quack_serve is the last PAYLOAD statement; the keep-alive is a dot-command in the OUTER init file
# (below), not a query, so it pins no DuckDB transaction and no scheduler task. Mirrors
# spawn-quack-node.sh.
[void]$sb.AppendLine("SELECT listen_url FROM quack_serve('quack:0.0.0.0:$Port', token := '$Token', allow_other_hostname := true, disable_ssl := true);")

$payloadSql = $sb.ToString()
# What the dry-run seam prints: the SQL the node runs, plus the keep-alive tail, for parity with
# spawn-quack-node.sh's dry run. The live launch below delivers it split (secrets file + outer
# init) so the credentials never persist, which a content dry run does not need to show.
$keepAlive = ".shell `"while kill -0 $PID 2>/dev/null; do sleep 1; done`"`n.exit`n"
$initSql = $payloadSql + $keepAlive

# Test seam, mirror of QOD_SPAWN_DRY_RUN in spawn-quack-node.sh: print the assembled init SQL
# and exit without launching duckdb. Used by SpawnScriptEncryptionWindowsSpec to assert the
# emitted SQL, which is the only testable surface of a script the manager shells out to.
if ($dryRun) {
  [Console]::Out.Write($initSql)
  Remove-NodeDirectory $nodeDir
  exit 0
}

# stdout/stderr are inherited so node logs flow to the manager's console (LocalQuackBackend uses
# inheritIO()). stdin is closed right after start: nothing is fed to the CLI, so no statement can be
# injected on the node's own connection, and the 2.0 CLI records nothing in shell_history(). The
# node is kept alive by the keep-alive dot-command at the end of the outer init file.
#
# The init SQL goes through `-init <file>` (the CLI records stdin in shell_history(), not -init).
# Windows has no FIFO that -init accepts, so to keep the credentials off disk the init is SPLIT:
#   - a secrets file holds the payload (token, catalog password, object-store / federation SQL);
#   - the outer init file holds NO secrets - it `.read`s the secrets file, then has DuckDB itself
#     delete it, then blocks on the keep-alive. DuckDB deletes the file the instant it has finished
#     reading it (the `.read` returns after quack_serve), so there is no external delete race and no
#     window after startup where the secrets sit on disk. A crash before `.read` leaves the secrets
#     file for the stale sweep of a later spawn, no longer than that.
# Both files are current-user-only (explicit ACL on Windows, 0600 elsewhere) inside the private node
# directory. `-init` also stops the CLI from running the user's ~/.duckdbrc.
$secretsFile = Join-Path $nodeDir 'secrets.sql'
$initFile    = Join-Path $nodeDir 'init.sql'
[System.IO.File]::WriteAllText($secretsFile, $payloadSql)
if ($onWindows) {
  $delCmd  = "cmd /c del /f /q `"$secretsFile`""
  $waitCmd = "powershell -NoProfile -Command `"Wait-Process -Id $PID -ErrorAction SilentlyContinue`""
} else {
  $delCmd  = "rm -f '$secretsFile'"
  $waitCmd = "while kill -0 $PID 2>/dev/null; do sleep 1; done"
}
# The outer init: read the secrets, delete them (DuckDB itself), keep alive, exit.
$outer = ".bail off`n.read `"$secretsFile`"`n.shell `"$delCmd`"`n.shell `"$waitCmd`"`n.exit`n"
[System.IO.File]::WriteAllText($initFile, $outer)
function Restrict-ToCurrentUser([string]$file) {
  if ($onWindows) {
    $acl = New-Object System.Security.AccessControl.FileSecurity
    $acl.SetAccessRuleProtection($true, $false)
    $me = [System.Security.Principal.WindowsIdentity]::GetCurrent().User
    $acl.AddAccessRule((New-Object System.Security.AccessControl.FileSystemAccessRule(
      $me, 'FullControl', 'Allow')))
    Set-Acl -LiteralPath $file -AclObject $acl
  } else {
    & chmod 600 $file
  }
}
Restrict-ToCurrentUser $secretsFile
Restrict-ToCurrentUser $initFile
$psi = [System.Diagnostics.ProcessStartInfo]::new()
$psi.FileName               = $duckdbBin
$psi.Arguments              = '-init "' + $initFile + '"'
$psi.RedirectStandardInput  = $true
$psi.RedirectStandardOutput = $false
$psi.RedirectStandardError  = $false
$psi.UseShellExecute        = $false
$psi.WorkingDirectory       = $workDir

try {
  $proc = [System.Diagnostics.Process]::Start($psi)
  # stdin carries nothing; the keep-alive dot-command, not stdin, holds the process open.
  $proc.StandardInput.Close()
  $proc.WaitForExit()
  $exitCode = $proc.ExitCode
} finally {
  Remove-NodeDirectory $nodeDir
}
exit $exitCode
