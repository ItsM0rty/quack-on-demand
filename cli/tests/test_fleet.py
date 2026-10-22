import sys

import pytest
from typer.testing import CliRunner
from qod_cli.main import app
import qod_cli.rest as rest

runner = CliRunner()

def test_fleet_servers_and_ops(monkeypatch):
    calls = []
    def fake_request(self, method, path, params=None, body=None, text=False):
        calls.append((method, path, body))
        return {"servers": []} if method == "GET" else None
    monkeypatch.setattr(rest.RestClient, "request", fake_request)
    assert runner.invoke(app, ["fleet", "servers"]).exit_code == 0
    assert runner.invoke(app, ["fleet", "drain", "srv-1"]).exit_code == 0
    assert runner.invoke(app, ["fleet", "undrain", "srv-1"]).exit_code == 0
    assert runner.invoke(app, ["fleet", "remove", "srv-1"]).exit_code == 0
    assert runner.invoke(app, ["fleet", "approve", "srv-1"]).exit_code == 0
    assert calls == [
        ("GET", "/api/fleet/servers", None),
        ("POST", "/api/fleet/server/drain", {"name": "srv-1"}),
        ("POST", "/api/fleet/server/undrain", {"name": "srv-1"}),
        ("POST", "/api/fleet/server/remove", {"name": "srv-1"}),
        ("POST", "/api/fleet/server/approve", {"name": "srv-1"}),
    ]


def test_fleet_approve_prints_source_unknown_and_fails(monkeypatch):
    message = ("server 'srv-1' has no known source address yet; approve it after its next heartbeat, "
               "and check QOD_FLEET_TRUSTED_PROXIES if the manager sits behind a proxy")
    def fake_request(self, method, path, params=None, body=None, text=False):
        raise rest.ApiError(409, "source_unknown", message)
    monkeypatch.setattr(rest.RestClient, "request", fake_request)
    out = runner.invoke(app, ["fleet", "approve", "srv-1"])
    assert out.exit_code == 1
    assert "409 source_unknown" in out.output and "QOD_FLEET_TRUSTED_PROXIES" in out.output


def _pools_with_nodes():
    node = lambda nid, server, state: {
        "nodeId": nid, "role": "Dual", "host": "127.0.0.1", "port": 23101, "healthy": True,
        "quarantined": False, "inFlight": 0, "serverName": server, "serverState": state,
    }
    return {"pools": [
        {"tenant": "acme", "tenantDb": "acme_tpch", "pool": "bi",
         "nodes": [node("quack-acme-acme-tpch-bi-1", "s1", "reachable"), node("quack-acme-acme-tpch-bi-2", "s2", "dead")]},
        {"tenant": "globex", "tenantDb": "globex_db", "pool": "etl",
         "nodes": [node("quack-globex-globex-db-etl-1", None, None)]},
    ]}


def test_node_list_flattens_pools_with_a_server_column(monkeypatch):
    import json
    calls = []
    def fake_request(self, method, path, params=None, body=None, text=False):
        calls.append((method, path))
        return _pools_with_nodes()
    monkeypatch.setattr(rest.RestClient, "request", fake_request)
    out = runner.invoke(app, ["--json", "node", "list"])
    assert out.exit_code == 0, out.output
    rows = json.loads(out.stdout)
    assert calls == [("GET", "/api/pool/list")]
    assert [(r["node"], r["server"], r["serverState"]) for r in rows] == [
        ("quack-acme-acme-tpch-bi-1", "s1", "reachable"),
        ("quack-acme-acme-tpch-bi-2", "s2", "dead"),
        ("quack-globex-globex-db-etl-1", None, None),
    ]
    assert rows[0]["tenant"] == "acme" and rows[0]["db"] == "acme_tpch" and rows[0]["pool"] == "bi"
    table = runner.invoke(app, ["node", "list", "--tenant", "acme"])
    assert table.exit_code == 0 and "s2" in table.output and "globex" not in table.output


def test_fleet_join_is_a_fleet_subcommand_and_agent_is_gone():
    help_out = runner.invoke(app, ["fleet", "join", "--help"])
    assert help_out.exit_code == 0, help_out.output
    assert "--manager" in help_out.output and "--join-token" in help_out.output
    assert "join" in runner.invoke(app, ["fleet", "--help"]).output
    # No alias: the pre-rename top-level command must not exist.
    old = runner.invoke(app, ["agent", "--help"])
    assert old.exit_code != 0


@pytest.mark.skipif(sys.platform == "win32", reason="qod fleet join is POSIX-only")
def test_fleet_join_reads_its_options_from_the_environment(monkeypatch, tmp_path):
    import qod_cli.commands.fleet as fleet_cmd
    captured = {}

    class FakeMember:
        def __init__(self, manager, token, **kw):
            captured.update(kw, manager=manager, token=token)
        def run_forever(self):
            pass

    monkeypatch.setattr(fleet_cmd, "FleetMember", FakeMember)
    monkeypatch.setattr(fleet_cmd.signal, "signal", lambda *a: None)
    monkeypatch.setattr(fleet_cmd.launcher, "default_cache_dir", lambda: tmp_path)
    monkeypatch.setattr(fleet_cmd.launcher, "materialize_spawn_scripts",
                        lambda d: (tmp_path / "spawn.sh", tmp_path / "spawn.ps1"))
    monkeypatch.setattr(fleet_cmd, "probe_duckdb_version", lambda b: "1.5.6")
    env = {"QOD_MANAGER_URL": "http://mgr:20900", "QOD_FLEET_JOIN_TOKEN": "tok",
           "QOD_FLEET_NAME": "worker-1", "QOD_FLEET_ADVERTISE_HOST": "host.docker.internal",
           "QOD_FLEET_BIND_HOST": "0.0.0.0", "QOD_FLEET_NODE_PORT": "21901"}
    out = runner.invoke(app, ["fleet", "join", "--insecure", "--duckdb-bin", str(tmp_path / "duckdb")], env=env)
    assert out.exit_code == 0, out.output
    assert (captured["name"], captured["advertise_host"], captured["bind_host"], captured["node_port"]) == \
        ("worker-1", "host.docker.internal", "0.0.0.0", 21901)
    assert captured["duckdb_version"] == "1.5.6"


@pytest.mark.skipif(sys.platform == "win32", reason="qod fleet join is POSIX-only")
def test_fleet_join_reports_the_cached_binarys_probed_version_not_the_pin(monkeypatch, tmp_path):
    # A cache provisioned by an older release must show up as such in the fleet listing.
    import qod_cli.commands.fleet as fleet_cmd
    captured, probed = {}, []

    class FakeMember:
        def __init__(self, manager, token, **kw):
            captured.update(kw)
        def run_forever(self):
            pass

    monkeypatch.setattr(fleet_cmd, "FleetMember", FakeMember)
    monkeypatch.setattr(fleet_cmd.signal, "signal", lambda *a: None)
    monkeypatch.setattr(fleet_cmd.launcher, "default_cache_dir", lambda: tmp_path)
    monkeypatch.setattr(fleet_cmd.launcher, "materialize_spawn_scripts",
                        lambda d: (tmp_path / "spawn.sh", tmp_path / "spawn.ps1"))
    monkeypatch.setattr(fleet_cmd.launcher, "ensure_duckdb_cli", lambda cache: tmp_path / "bin")
    monkeypatch.setattr(fleet_cmd, "probe_duckdb_version", lambda b: probed.append(b) or "1.5.6")
    env = {"QOD_MANAGER_URL": "http://mgr:20900", "QOD_FLEET_JOIN_TOKEN": "tok",
           "QOD_FLEET_ADVERTISE_HOST": "10.0.0.5"}
    out = runner.invoke(app, ["fleet", "join", "--insecure"], env=env)
    assert out.exit_code == 0, out.output
    assert probed == [tmp_path / "bin" / "duckdb"] == [captured["duckdb_bin"]]
    assert captured["duckdb_version"] == "1.5.6"
