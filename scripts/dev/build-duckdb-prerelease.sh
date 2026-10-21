#!/usr/bin/env bash
# Builds the DuckDB 2.0 pre-release toolchain QoD pins until v2.0.0 ships (design spec
# docs/superpowers/specs/2026-10-10-duckdb-2-0-bump-design.md, "Pre-release pin"). Nothing is
# published for a dev engine commit (extension repositories 404, no JDBC on Central), so the
# engine, its extensions and duckdb-java are built here from one commit.
#
# Env: DUCKDB_SHA (engine pin), DUCKDB_JAVA_REF, QOD_PRERELEASE_WORK (build dir),
#      DUCKDB_CACHE_DIR (cache root, default <main checkout>/.duckdb), PG_ROOT (libpq >= 18),
#      OPENSSL_ROOT_DIR, JOBS.
set -euo pipefail

DUCKDB_SHA="${DUCKDB_SHA:-b15358c371daf10dad799a43b6167f6ccdce84d8}"
# Full commit SHA (v2.0-cyanoptera, vendors engine b15358c371): pinned so the build is reproducible.
DUCKDB_JAVA_REF="${DUCKDB_JAVA_REF:-dd398c408ac52939265d47ae0e8eb672fb3efb66}"
ABI="2.0.0"
JDBC_VERSION="2.0.0.0-pre-${DUCKDB_SHA:0:7}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
MAIN_DIR="$(cd "$(git -C "$REPO_DIR" rev-parse --path-format=absolute --git-common-dir)/.." && pwd)"
WORK="${QOD_PRERELEASE_WORK:-$HOME/.cache/qod-duckdb-prerelease}"
CACHE="${DUCKDB_CACHE_DIR:-$MAIN_DIR/.duckdb}/$ABI"
JOBS="${JOBS:-$(getconf _NPROCESSORS_ONLN)}"

case "$(uname -s)" in
  Darwin)
    PG_ROOT="${PG_ROOT:-$(brew --prefix libpq)}"
    OPENSSL_ROOT_DIR="${OPENSSL_ROOT_DIR:-$(brew --prefix openssl@3)}"
    LIBEXT=dylib ;;
  Linux)
    PG_ROOT="${PG_ROOT:-/usr}"
    OPENSSL_ROOT_DIR="${OPENSSL_ROOT_DIR:-/usr}"
    LIBEXT=so ;;
  *) echo "unsupported OS $(uname -s)" >&2; exit 1 ;;
esac
grep -q 'PQAUTHDATA_OAUTH_BEARER_TOKEN' "$PG_ROOT/include/libpq-fe.h" 2>/dev/null || {
  echo "libpq >= 18 headers required at $PG_ROOT/include (set PG_ROOT)" >&2; exit 1; }

mkdir -p "$WORK"

# ---- 0. duckdb-java at the same engine commit (its version string is the engine's) -------
if [[ ! -d "$WORK/duckdb-java/.git" ]]; then
  git clone --filter=blob:none https://github.com/duckdb/duckdb-java.git "$WORK/duckdb-java"
fi
git -C "$WORK/duckdb-java" fetch origin "$DUCKDB_JAVA_REF"
git -C "$WORK/duckdb-java" checkout --detach FETCH_HEAD
PRAGMA_CPP="$WORK/duckdb-java/src/duckdb/src/function/table/version/pragma_version.cpp"
[[ -f "$PRAGMA_CPP" ]] || { echo "missing $PRAGMA_CPP (duckdb-java layout changed?)" >&2; exit 1; }
vendored="$(grep -o 'DUCKDB_SOURCE_ID "[0-9a-f]*"' "$PRAGMA_CPP" | head -1 | cut -d'"' -f2)"
[[ "${DUCKDB_SHA:0:10}" == "$vendored" ]] || {
  echo "duckdb-java $DUCKDB_JAVA_REF vendors $vendored, not ${DUCKDB_SHA:0:10}: DUCKDB_JAVA_REF must be a FULL 40-character duckdb-java commit SHA vendoring the pinned engine" >&2
  exit 1; }
ENGINE_VERSION="$(grep -o 'DUCKDB_VERSION "[^"]*"' "$PRAGMA_CPP" | head -1 | cut -d'"' -f2)"
[[ -n "$ENGINE_VERSION" ]] || { echo "no DUCKDB_VERSION in duckdb-java's vendored engine" >&2; exit 1; }

# ---- 1. engine + extensions ---------------------------------------------------------------
if [[ ! -d "$WORK/duckdb/.git" ]]; then
  git clone --filter=blob:none https://github.com/duckdb/duckdb.git "$WORK/duckdb"
fi
git -C "$WORK/duckdb" fetch --tags origin v2.0-cyanoptera
git -C "$WORK/duckdb" checkout --detach "$DUCKDB_SHA"

# DuckDB's own extension pins for this commit (what the release builds), plus in-tree ones.
# duckdb_extension_load only BUILDS in 2.0; linking into the CLI/libduckdb is opt-in.
cat > "$WORK/qod_extensions.cmake" <<EOF
duckdb_extension_load(core_functions)
duckdb_extension_load(json)
duckdb_extension_load(parquet)
duckdb_extension_load(icu)
duckdb_extension_load(autocomplete)
duckdb_extension_load(tpch)
include($WORK/duckdb/.github/config/extensions/quack.cmake)
include($WORK/duckdb/.github/config/extensions/httpfs.cmake)
include($WORK/duckdb/.github/config/extensions/ducklake.cmake)
include($WORK/duckdb/.github/config/extensions/postgres_scanner.cmake)
duckdb_extension_statically_link(core_functions json parquet icu autocomplete tpch quack httpfs ducklake postgres_scanner)
EOF

LINK="-L$PG_ROOT/lib -lpq"
# A loadable extension only loads in an engine with the same version string, and the JDBC's string comes from duckdb-java's vendoring.
# DUCKDB_EXPLICIT_VERSION beats both the DUCKDB_VERSION and OVERRIDE_GIT_DESCRIBE env knobs in DuckDB's CMakeLists.
cmake -S "$WORK/duckdb" -B "$WORK/duckdb/build/release" -G Ninja \
  -DCMAKE_BUILD_TYPE=Release \
  -DDUCKDB_EXPLICIT_VERSION="$ENGINE_VERSION" \
  -DDUCKDB_EXTENSION_CONFIGS="$WORK/qod_extensions.cmake" \
  -DOPENSSL_ROOT_DIR="$OPENSSL_ROOT_DIR" \
  -DPostgreSQL_ROOT="$PG_ROOT" -DCMAKE_PREFIX_PATH="$PG_ROOT" \
  -DCMAKE_EXE_LINKER_FLAGS="$LINK" -DCMAKE_SHARED_LINKER_FLAGS="$LINK"
cmake --build "$WORK/duckdb/build/release" -j "$JOBS"

B="$WORK/duckdb/build/release"
rm -rf "$CACHE"
mkdir -p "$CACHE/bin" "$CACHE/lib" "$CACHE/include" "$CACHE/extensions"
cp "$B/duckdb" "$CACHE/bin/duckdb"
cp "$B/src/libduckdb.$LIBEXT" "$CACHE/lib/"
cp -R "$WORK/duckdb/src/include/." "$CACHE/include/"
find "$B/extension" -name '*.duckdb_extension' -exec cp {} "$CACHE/extensions/" \;

# ---- 2. sanity: version and linked extensions ---------------------------------------------
"$CACHE/bin/duckdb" -csv -noheader -c "SELECT version(), source_id FROM pragma_version()"
ver="$("$CACHE/bin/duckdb" -csv -noheader -c "SELECT version()")"
[[ "$ver" == "$ENGINE_VERSION" ]] || {
  echo "built CLI reports $ver, expected $ENGINE_VERSION (duckdb-java's vendored version)" >&2; exit 1; }
missing="$("$CACHE/bin/duckdb" -csv -noheader -c \
  "SELECT coalesce(string_agg(extension_name, ','), '') FROM duckdb_extensions()
   WHERE extension_name IN ('quack','ducklake','postgres_scanner','httpfs','json','parquet','tpch')
     AND install_mode <> 'STATICALLY_LINKED'")"
[[ -z "$missing" ]] || { echo "not statically linked: $missing" >&2; exit 1; }
for e in quack ducklake postgres_scanner httpfs; do
  [[ -f "$CACHE/extensions/$e.duckdb_extension" ]] || { echo "missing loadable $e" >&2; exit 1; }
done

sid="$("$CACHE/bin/duckdb" -csv -noheader -c "SELECT source_id FROM pragma_version()")"
[[ "$sid" == "${DUCKDB_SHA:0:10}" ]] || {
  echo "built CLI source_id $sid, expected ${DUCKDB_SHA:0:10}" >&2; exit 1; }

# A source build auto-installs into its own build repository, not `core`, so iceberg's load-time
# auto-install of avro finds nothing there and every Iceberg ATTACH fails. An official release
# auto-installs from `core`; pre-installing avro from `core` gives the same result.
"$CACHE/bin/duckdb" -no-agent -c "INSTALL avro FROM core"
# ---- 3. build and install duckdb-java ----------------------------------------------------
( cd "$WORK/duckdb-java" && GEN=ninja make release )
M2="$HOME/.m2/repository/org/duckdb/duckdb_jdbc/$JDBC_VERSION"
mkdir -p "$M2"
cp "$WORK/duckdb-java/build/release/duckdb_jdbc.jar" "$M2/duckdb_jdbc-$JDBC_VERSION.jar"
cat > "$M2/duckdb_jdbc-$JDBC_VERSION.pom" <<POM
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>org.duckdb</groupId>
  <artifactId>duckdb_jdbc</artifactId>
  <version>$JDBC_VERSION</version>
  <packaging>jar</packaging>
</project>
POM

echo
echo "DuckDB pre-release toolchain ready:"
echo "  cache     : $CACHE"
echo "  jdbc      : org.duckdb:duckdb_jdbc:$JDBC_VERSION (~/.m2)"
echo "  for the manager and tests, export:"
echo "    export QOD_DUCKDB_EXTENSION_DIR=$CACHE/extensions"
echo "    export PATH=$CACHE/bin:\$PATH"
