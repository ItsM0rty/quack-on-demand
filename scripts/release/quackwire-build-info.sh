# shellcheck shell=bash
#
# Reads and checks the build marker compiled into a libquackwire binary
# (kQuackwireBuildInfo in native/quackwire/src/quackwire.cpp, values supplied by
# native/quackwire/CMakeLists.txt):
#
#   quackwire-build-info:protocol=3;duckdb=v2.0.0;duckdb_source=<id>;quack=<sha>
#
# libquackwire/binaries/VERSION is stamped for the whole set, so it cannot tell
# a binary left over from an earlier DuckDB from a fresh one; the marker can.
# The manager runs the same check at boot (QuackNativeSupport.bootCheck, which
# also compares the loaded libduckdb); this one runs on the files, without
# loading them, so it works for every platform on any host.
#
# Sourced by release-lib.sh (verify_quackwire_binaries) and
# refresh-quackwire-binaries.sh. Needs only grep.

# The Quack protocol generation the manager's node client speaks. Pinned against
# NativeBuildInfo.ProtocolVersion by QuackNativeSupportSpec.
QUACKWIRE_PROTOCOL=3

# quackwire_build_marker <file> - prints the marker, or nothing when the binary
# has none (a native built before markers existed). The literal is plain ASCII
# in every format (ELF .rodata, Mach-O __cstring, PE .rdata).
quackwire_build_marker() {
  LC_ALL=C grep -a -o 'quackwire-build-info:[ -~]*' "$1" 2>/dev/null | head -n1
}

# quackwire_check_build_marker <file> <libquackwireVersion> - returns 0 when the
# binary's marker matches the pin (protocol, DuckDB release, duckdb-quack
# commit), else prints why to stderr and returns 1.
quackwire_check_build_marker() {
  local file="$1" pin="$2" marker abi quack12 protocol duckdb quack release
  [[ "$pin" =~ ^([0-9]+\.[0-9]+\.[0-9]+)-([0-9a-f]+)- ]] \
    || { echo "libquackwireVersion '$pin' is not a <duckdb>-<quack sha>-<rev> stamp" >&2; return 1; }
  abi="${BASH_REMATCH[1]}"
  quack12="${BASH_REMATCH[2]}"
  marker="$(quackwire_build_marker "$file")"
  [[ -n "$marker" ]] \
    || { echo "$file has no quackwire-build-info marker: it predates build markers (a stale DuckDB 1.5.x / Quack v1 build), so it cannot match $pin. Rebuild or refresh it (scripts/release/refresh-quackwire-binaries.sh)." >&2; return 1; }
  [[ "$marker" =~ protocol=([0-9]+) ]] && protocol="${BASH_REMATCH[1]}" || protocol=""
  [[ "$marker" =~ \;duckdb=([^\;]+) ]] && duckdb="${BASH_REMATCH[1]}" || duckdb=""
  [[ "$marker" =~ \;quack=([0-9a-f]+) ]] && quack="${BASH_REMATCH[1]}" || quack=""
  release="${duckdb#v}"
  [[ "$release" =~ ^([0-9]+\.[0-9]+\.[0-9]+) ]] && release="${BASH_REMATCH[1]}" || release=""
  if [[ "$protocol" != "$QUACKWIRE_PROTOCOL" ]]; then
    echo "$file speaks Quack protocol '${protocol}', the manager needs $QUACKWIRE_PROTOCOL ($marker)" >&2
    return 1
  fi
  if [[ "$release" != "$abi" ]]; then
    echo "$file was built against DuckDB '${duckdb}', the pin is $abi ($pin; marker: $marker)" >&2
    return 1
  fi
  if [[ -z "$quack" || "$quack" != "$quack12"* ]]; then
    echo "$file was built from duckdb-quack '${quack:0:12}', the pin is $quack12 ($pin; marker: $marker)" >&2
    return 1
  fi
  return 0
}
