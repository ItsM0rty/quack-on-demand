"""Stopping one manager only touches that manager's nodes.

Every node's wrapper argv and node directory carry its manager's marker `<rest-port>-<boot id>`
(LocalQuackBackend.managerMarker), and the stop paths match on it. These tests start two wrappers
of the real spawn script, with stand-in duckdb nodes, under two markers whose REST ports no real
manager uses, then stop one manager and check the other's node survives.
"""

import os
import pathlib
import subprocess
import sys
import time

import pytest

pytestmark = pytest.mark.skipif(sys.platform == "win32", reason="bash scripts")

REPO = pathlib.Path(__file__).resolve().parents[2]
SPAWN = REPO / "scripts" / "spawn-quack-node.sh"
KILL = REPO / "scripts" / "kill-quack-nodes.sh"
# REST ports no real manager on this host listens on; the markers only need to be distinct.
MARK_A = "29991-aaaa1111"
MARK_B = "29992-bbbb2222"


def _fake_duckdb(path: pathlib.Path) -> pathlib.Path:
    # Answers the spawn script's .shell probe, then holds like a real node until signalled.
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        "#!/bin/sh\n"
        '[ "$1" = -no-init ] && { echo qod-shell-ok; exit 0; }\n'
        'cat "$2" > /dev/null & reader=$!\n'
        "trap 'kill $reader 2>/dev/null; exit 143' TERM INT\n"
        "wait $reader\n"
        "while :; do sleep 1; done\n"
    )
    path.chmod(0o700)
    return path


def _spawn(tmp_path, name, node_port, marker, duckdb):
    from qod_cli.fleet_join import write_secrets_dir
    secrets = write_secrets_dir(tmp_path / f"secrets-{name}", {"QOD_NODE_TOKEN": "tok-scope"})
    tmpdir = tmp_path / "tmpdir"
    tmpdir.mkdir(exist_ok=True)
    env = {"PATH": os.environ["PATH"], "TMPDIR": str(tmpdir), "kind": "memory", "dbName": "m",
           "schemaName": "main", "DUCKDB_BIN": str(duckdb), "QOD_SECRETS_DIR": str(secrets),
           "QOD_SECRETS_DIR_EPHEMERAL": "1"}
    start = tmp_path / f"start-{name}"
    start.mkdir()
    return subprocess.Popen(["bash", str(SPAWN), str(node_port), marker], cwd=start, env=env,
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def _wait_for(pred, timeout=20.0):
    deadline = time.monotonic() + timeout
    while not pred() and time.monotonic() < deadline:
        time.sleep(0.1)
    return pred()


def _pgrep(pattern):
    out = subprocess.run(["pgrep", "-f", pattern], capture_output=True, text=True).stdout
    return {int(p) for p in out.split()}


def test_the_node_directory_carries_the_manager_marker(tmp_path):
    duckdb = _fake_duckdb(tmp_path / "bin" / "duckdb")
    a = _spawn(tmp_path, "a", 23461, MARK_A, duckdb)
    try:
        assert _wait_for(lambda: _pgrep(rf"duckdb -init .*quack-node\.{MARK_A}\."))
        assert any(p.name.startswith(f"quack-node.{MARK_A}.") for p in (tmp_path / "tmpdir").iterdir())
    finally:
        a.terminate()
        a.wait(timeout=20)


def test_an_invalid_marker_is_refused(tmp_path):
    duckdb = _fake_duckdb(tmp_path / "bin" / "duckdb")
    p = _spawn(tmp_path, "bad", 23462, "not;a-marker", duckdb)
    assert p.wait(timeout=20) == 1


def test_kill_quack_nodes_stops_only_its_own_managers_nodes(tmp_path):
    duckdb = _fake_duckdb(tmp_path / "bin" / "duckdb")
    a = _spawn(tmp_path, "a", 23463, MARK_A, duckdb)
    b = _spawn(tmp_path, "b", 23464, MARK_B, duckdb)
    try:
        assert _wait_for(lambda: _pgrep(rf"duckdb -init .*quack-node\.{MARK_A}\.")
                         and _pgrep(rf"duckdb -init .*quack-node\.{MARK_B}\."))
        # The scoped pattern sees exactly manager A's wrapper, never B's nor anything else.
        assert _pgrep(r"spawn-quack-node\.sh [0-9]+ 29991-[0-9a-f]+") == {a.pid}
        res = subprocess.run(["bash", str(KILL), "--port", "29991"], capture_output=True, text=True,
                             env={"PATH": os.environ["PATH"], "FORCE_AFTER": "5"}, timeout=60)
        assert res.returncode == 0, res.stdout + res.stderr
        assert a.wait(timeout=20) is not None
        assert b.poll() is None, "manager B's node was stopped by a stop of manager A"
        assert _pgrep(rf"duckdb -init .*quack-node\.{MARK_B}\.")
        assert not _pgrep(rf"duckdb -init .*quack-node\.{MARK_A}\.")
    finally:
        for p in (a, b):
            if p.poll() is None:
                p.terminate()
                p.wait(timeout=20)


def test_kill_quack_nodes_documents_all_in_its_usage():
    res = subprocess.run(["bash", str(KILL), "--help"], capture_output=True, text=True, timeout=30)
    assert res.returncode == 0
    assert "--all" in res.stdout and "--port" in res.stdout


def test_qod_stop_matches_only_its_managers_nodes(monkeypatch):
    from qod_cli.commands import stop as stop_cmd
    seen = []
    monkeypatch.setattr(stop_cmd, "_listening_pid", lambda port: None)
    monkeypatch.setattr(stop_cmd, "_pgrep", lambda pattern: seen.append(pattern) or [])
    monkeypatch.setenv("QOD_ON_DEMAND_PORT", "29993")
    stop_cmd._stop_manager_and_nodes()
    assert seen and all("29993-" in p for p in seen), seen
