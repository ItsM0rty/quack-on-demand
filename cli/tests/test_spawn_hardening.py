"""The spawn script refuses dot-command init SQL, and its stale-dir sweep trusts nothing it did not make.

A line starting with `.` in the init SQL is a DuckDB CLI dot-command, and `.shell` / `.system` run an
OS command as the node's user: the script refuses one in every value it did not author, whatever
launched it (the manager screens the same values first, InitSqlScreen.scala). The sweep of earlier
node directories walks TMPDIR, often the shared /tmp: it must never follow a symlink, delete a path a
node directory merely names, or let a stray entry abort the spawn.

Every test runs the bundled script (what `qod fleet join` runs) in dry-run mode with a sandbox TMPDIR
and starting directory; no duckdb is ever started.
"""

import os
import pathlib
import stat
import subprocess
import sys

import pytest

pytestmark = pytest.mark.skipif(sys.platform == "win32", reason="bash spawn script")

ROOT = pathlib.Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "cli" / "src" / "qod_cli" / "scripts" / "spawn-quack-node.sh"
STOP_JAR = ROOT / "scripts" / "stop-jar.sh"


def _dry_run(tmp_path, secrets=None, **env_extra):
    from qod_cli.fleet_join import write_secrets_dir
    d = write_secrets_dir(tmp_path / "secrets", {"QOD_NODE_TOKEN": "tok-hard", **(secrets or {})})
    tmpdir = tmp_path / "tmpdir"
    tmpdir.mkdir(exist_ok=True)
    start = tmp_path / "start"
    start.mkdir(exist_ok=True)
    env = {"PATH": os.environ["PATH"], "TMPDIR": str(tmpdir), "kind": "memory", "dbName": "m",
           "schemaName": "main", "QOD_SECRETS_DIR": str(d), "QOD_SECRETS_DIR_EPHEMERAL": "1",
           "QOD_SPAWN_DRY_RUN": "1"}
    env.update(env_extra)
    return subprocess.run(["bash", str(SCRIPT), "21999"], cwd=start, env=env, capture_output=True,
                          text=True, timeout=60)


def _dead_pid():
    p = subprocess.Popen(["true"])
    p.wait()
    return p.pid


DOT_VALUES = [".shell touch qod-pwned", "SET threads = 2;\n  .shell id",
              "SET threads = 2;\r.system id", "SELECT 1;\n\t.read /etc/passwd"]


@pytest.mark.parametrize("value", DOT_VALUES)
@pytest.mark.parametrize("key,secret", [("dbInitSql", False), ("lockdownSql", False),
                                        ("extraSetupSql", True), ("objectStoreSql", True)])
def test_a_dot_command_line_in_any_value_refuses_the_spawn(tmp_path, key, secret, value):
    res = (_dry_run(tmp_path, secrets={key: value}) if secret
           else _dry_run(tmp_path, **{key: value}))
    assert res.returncode == 1, res.stdout + res.stderr
    assert f"ERROR: {key} holds a line starting with '.'" in res.stderr
    # Refused before any init SQL is assembled or any node directory is made.
    assert res.stdout == ""
    assert list((tmp_path / "tmpdir").iterdir()) == []


def test_a_dot_command_smuggled_through_a_script_value_is_refused_too(tmp_path):
    # The proxy is not a tenant value, but it is interpolated into the init SQL all the same.
    res = _dry_run(tmp_path, HTTP_PROXY="proxy:3128\n.shell id")
    assert res.returncode == 1, res.stdout + res.stderr
    assert "the assembled init SQL holds a line starting with '.'" in res.stderr
    assert res.stdout == ""


def test_ordinary_init_sql_keeps_only_the_scripts_own_dot_commands(tmp_path):
    res = _dry_run(tmp_path, secrets={"extraSetupSql": "SELECT 1.5 AS x;\nSELECT '.not a command';"},
                   dbInitSql="SET threads = 2;\n  SELECT 2;", lockdownSql="SET enable_external_access = false;")
    assert res.returncode == 0, res.stderr
    dots = [line for line in res.stdout.splitlines() if line.lstrip().startswith(".")]
    assert dots[0] == ".bail off"
    assert dots[1].startswith('.shell "tail --pid=')
    assert dots[2:] == [".exit"]


def test_the_sweep_never_follows_a_symlinked_node_directory(tmp_path):
    victim = tmp_path / "victim"
    victim.mkdir()
    (victim / "owner.pid").write_text(str(_dead_pid()))
    # Shaped like a spill directory of this host, so only the symlink check keeps it.
    spill = tmp_path / "start" / ".tmp" / "qod-node.Lnk001"
    spill.mkdir(parents=True)
    (victim / "spill.path").write_text(str(spill))
    (tmp_path / "tmpdir").mkdir()
    (tmp_path / "tmpdir" / "quack-node.link").symlink_to(victim)
    res = _dry_run(tmp_path)
    assert res.returncode == 0, res.stderr
    assert (victim / "owner.pid").exists()
    assert spill.exists()


def test_the_sweep_deletes_only_a_spill_directory_it_could_have_made(tmp_path):
    tmpdir = tmp_path / "tmpdir"
    planted = tmpdir / "quack-node.PLANT1"
    planted.mkdir(parents=True)
    (planted / "owner.pid").write_text(str(_dead_pid()))
    victim = tmp_path / "victim" / "qod-node.ABCDEF"
    victim.mkdir(parents=True)
    (victim / "keep").write_text("data")
    (planted / "spill.path").write_text(str(victim))
    real = tmpdir / "quack-node.REAL01"
    real.mkdir()
    (real / "owner.pid").write_text(str(_dead_pid()))
    spill = tmp_path / "start" / ".tmp" / "qod-node.Zx9Qa1"
    spill.mkdir(parents=True)
    (real / "spill.path").write_text(str(spill))
    res = _dry_run(tmp_path)
    assert res.returncode == 0, res.stderr
    # Outside every spill root: the stale directory goes, the path it names stays.
    assert not planted.exists()
    assert (victim / "keep").read_text() == "data"
    # Under this host's spill root: removed with its node directory.
    assert not real.exists()
    assert not spill.exists()


def test_the_sweep_never_chmods_through_a_symlinked_secrets_entry(tmp_path):
    tmpdir = tmp_path / "tmpdir"
    stale = tmpdir / "quack-node.SECRT1"
    stale.mkdir(parents=True)
    (stale / "owner.pid").write_text(str(_dead_pid()))
    target = tmp_path / "protected"
    target.mkdir()
    target.chmod(0o500)
    (stale / "secrets").symlink_to(target)
    try:
        res = _dry_run(tmp_path)
        assert res.returncode == 0, res.stderr
        assert stat.S_IMODE(target.stat().st_mode) == 0o500
        assert not stale.exists()
    finally:
        target.chmod(0o700)


def test_an_undeletable_stale_entry_never_aborts_the_spawn(tmp_path):
    tmpdir = tmp_path / "tmpdir"
    stale = tmpdir / "quack-node.STUCK1"
    locked = stale / "locked"
    locked.mkdir(parents=True)
    (locked / "f").write_text("x")
    (stale / "owner.pid").write_text(str(_dead_pid()))
    locked.chmod(0o500)
    try:
        res = _dry_run(tmp_path)
        assert res.returncode == 0, res.stderr
        assert "SELECT listen_url FROM quack_serve" in res.stdout
    finally:
        locked.chmod(0o700)


def test_a_live_owner_of_another_user_keeps_its_directory(tmp_path):
    # pid 1 is always alive; for a non-root user `kill -0 1` fails with EPERM, which must not read
    # as dead.
    tmpdir = tmp_path / "tmpdir"
    live = tmpdir / "quack-node.LIVE01"
    live.mkdir(parents=True)
    (live / "owner.pid").write_text("1")
    res = _dry_run(tmp_path)
    assert res.returncode == 0, res.stderr
    assert live.exists()


def _stop_jar_line(prefix):
    lines = [line for line in STOP_JAR.read_text().splitlines() if line.startswith(prefix)]
    assert len(lines) == 1, f"stop-jar.sh has {len(lines)} lines starting with {prefix!r}"
    return lines[0]


@pytest.mark.parametrize("pgrep_body,expected", [("return 1", "0"), ("echo 4242", "1")])
def test_stop_jar_counts_unmarked_wrappers_without_aborting(pgrep_body, expected):
    # Under `set -euo pipefail` a pgrep that matches nothing exits 1; the count must still be 0 and
    # the script must carry on to the teardown.
    harness = (f"set -euo pipefail\npgrep() {{ {pgrep_body}; }}\n{_stop_jar_line('unmarked=')}\n"
               'echo "unmarked=$unmarked"\n')
    res = subprocess.run(["bash", "-c", harness], capture_output=True, text=True, timeout=30)
    assert res.returncode == 0, res.stderr
    assert res.stdout.strip() == f"unmarked={expected}"


def test_no_pgrep_pipeline_under_set_e_can_abort_a_script():
    # The same hazard anywhere else: a `$(pgrep ... | ...)` without `|| true` aborts under pipefail.
    scripts = sorted((ROOT / "scripts").glob("*.sh")) + sorted(SCRIPT.parent.glob("*.sh"))
    bad = []
    for path in scripts:
        for n, line in enumerate(path.read_text().splitlines(), 1):
            code = line.split("#", 1)[0]
            if "pgrep" in code and "|" in code.replace("||", "") and "|| true" not in code:
                bad.append(f"{path.name}:{n}: {line.strip()}")
    assert bad == []
