#!/usr/bin/env bash
#
# Kill the running `spawn-quack-node.sh` wrappers (and the duckdb child each one traps) of ONE
# manager, leaving the manager JVM alone. Use it to nuke stuck child nodes without bouncing the
# manager (e.g. after a crashed reconcile leaves orphaned spawn-quack-node.sh PIDs).
#
# By default it only touches the nodes of the manager on QOD_ON_DEMAND_PORT (default 20900): each
# node's wrapper argv and node directory carry that manager's marker `<rest-port>-<boot id>`, so the
# nodes of another manager on this host (an isolated test stack) are left alone. `--all` sweeps every
# node on the host, whichever manager started it, including unmarked nodes from a manager older
# than the marker; it is the old blunt reset and must be asked for explicitly.
#
# SIGTERM first so the script's `trap` tears down the duckdb child cleanly; SIGKILL after
# FORCE_AFTER seconds for anything still alive.
#
# Overrides via env vars:
#   QOD_ON_DEMAND_PORT   (default 20900 - the manager whose nodes to kill)
#   FORCE_AFTER          (default 5  - seconds before SIGKILL)
#
# Usage:
#   ./scripts/kill-quack-nodes.sh                 # this manager's nodes (QOD_ON_DEMAND_PORT)
#   ./scripts/kill-quack-nodes.sh --port 20960    # the nodes of the manager on REST port 20960
#   ./scripts/kill-quack-nodes.sh --all           # every node on the host, every manager

set -euo pipefail

FORCE_AFTER="${FORCE_AFTER:-5}"
MGR_PORT="${QOD_ON_DEMAND_PORT:-20900}"
ALL=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --all) ALL=1; shift ;;
    --port) MGR_PORT="${2:?--port needs a REST port}"; shift 2 ;;
    -h|--help) sed -n '2,24p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1 (see --help)" >&2; exit 2 ;;
  esac
done
if [[ ! "$MGR_PORT" =~ ^[0-9]+$ ]]; then
  echo "--port must be a number, got '$MGR_PORT'" >&2
  exit 2
fi

if [[ "$ALL" == "1" ]]; then
  # Match the literal script name so we don't grab unrelated `quack-node` matches (e.g. log lines
  # piped through `tee`).
  SPAWN_RE='spawn-quack-node\.sh'
  DUCK_RE='duckdb .*-init .*quack-node\.'
  scope="every manager"
else
  SPAWN_RE="spawn-quack-node\\.sh [0-9]+ ${MGR_PORT}-[0-9a-f]+"
  DUCK_RE="duckdb .*-init .*quack-node\\.${MGR_PORT}-[0-9a-f]+\\."
  scope="the manager on port $MGR_PORT"
fi

pids=$(pgrep -f "$SPAWN_RE" 2>/dev/null || true)

if [[ -z "$pids" ]]; then
  echo "no spawn-quack-node.sh processes running for $scope."
  exit 0
fi

echo "killing spawn-quack-node.sh pids of $scope: $(echo "$pids" | tr '\n' ' ')"

# ---- Graceful ----
for pid in $pids; do
  kill -TERM "$pid" 2>/dev/null || true
done

# ---- Wait for them to exit ----
for ((i=0; i<FORCE_AFTER; i++)); do
  sleep 1
  if ! pgrep -f "$SPAWN_RE" > /dev/null 2>&1; then
    echo "stopped cleanly after ${i}s."
    exit 0
  fi
done

# ---- Force ----
echo "still alive after ${FORCE_AFTER}s; sending SIGKILL."
remaining=$(pgrep -f "$SPAWN_RE" 2>/dev/null || true)
for pid in $remaining; do
  kill -9 "$pid" 2>/dev/null || true
done

# Sweep any orphaned duckdb children whose parent script died before its trap could fire. Nodes
# run as `<abs path>/duckdb -init <tmp>/quack-node.<marker>.XXX/init`, so match that argv (a bare
# `^duckdb` never matches the absolute path the spawn script uses).
orphan_duck=$(pgrep -f "$DUCK_RE" 2>/dev/null || true)
for pid in $orphan_duck; do
  kill -9 "$pid" 2>/dev/null || true
done

sleep 1
if pgrep -f "$SPAWN_RE" > /dev/null 2>&1; then
  echo "WARN: some spawn-quack-node.sh PIDs survived SIGKILL. Investigate with: pgrep -alf spawn-quack-node" >&2
  exit 1
fi
echo "stopped (forced)."
