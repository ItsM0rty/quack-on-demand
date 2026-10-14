#!/usr/bin/env bash
#
# Refuses a tree that only builds on a developer machine: a pre-release DuckDB
# JDBC (`Versions.duckdbJdbc` containing `-pre-`, minted into ~/.m2 by
# scripts/dev/build-duckdb-prerelease.sh, served by no remote repository) or a
# `Resolver.mavenLocal` in the build. Either one makes every CI sbt job, the
# manager Docker build and fresh clones fail at dependency resolution, while the
# node and worker image workflows (no sbt) would still publish on the new
# contract.
#
# Run by .github/workflows/release-pins.yml on pull requests targeting main and
# pushes to main, and as the first step of every snapshot / release publishing
# workflow. A pre-release branch is free to carry both until release day; it
# just cannot reach main.
#
# Usage: scripts/release/check-release-pins.sh [repo dir]
set -euo pipefail
REPO_DIR="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
cd "$REPO_DIR"

problems=()

jdbc_line="$(grep -E '^[[:space:]]*val duckdbJdbc[[:space:]]*=' project/Versions.scala || true)"
[[ -n "$jdbc_line" ]] || problems+=("project/Versions.scala has no \`val duckdbJdbc\` line")
if [[ "$jdbc_line" == *-pre-* ]]; then
  problems+=("project/Versions.scala pins a pre-release DuckDB JDBC (${jdbc_line#"${jdbc_line%%[![:space:]]*}"}): it exists only in a developer's ~/.m2. Release day: \`val duckdbJdbc = duckdb\` (Maven Central).")
fi

# Comment lines do not count; anything else naming mavenLocal does.
for f in build.sbt project/*.sbt project/*.scala; do
  [[ -f "$f" ]] || continue
  while IFS= read -r hit; do
    line="${hit#*:}"
    problems+=("$f:${hit%%:*} resolves from ~/.m2 (${line#"${line%%[![:space:]]*}"}): drop the mavenLocal resolver.")
  done < <(grep -nE 'mavenLocal' "$f" | grep -vE '^[0-9]+:[[:space:]]*//' || true)
done

if [[ ${#problems[@]} -gt 0 ]]; then
  echo "release-pin guard: this tree cannot build outside a developer machine:" >&2
  for p in "${problems[@]}"; do echo "  - $p" >&2; done
  echo "The release-day steps: scripts/release/RELEASE-DAY-DUCKDB-2.0.md" >&2
  [[ -n "${GITHUB_ACTIONS:-}" ]] && echo "::error::release-pin guard failed: pre-release DuckDB JDBC or mavenLocal resolver (see log)"
  exit 1
fi
echo "release-pin guard OK: no pre-release JDBC, no mavenLocal resolver"
