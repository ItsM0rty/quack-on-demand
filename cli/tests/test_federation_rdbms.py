"""Typed postgres / mysql flags on `qod federation create`.

The body assertions read the bytes httpx actually put on the wire (respx records the real
request), as test_federation_iceberg.py does, so a flag that never reaches `call()` cannot pass.
"""

import json

import httpx
import pytest

BASE = "http://localhost:20900"
SOURCES = f"{BASE}/api/tenants/acme/tenant-dbs/db1/federated-sources"


@pytest.fixture(autouse=True)
def api_key(monkeypatch):
    monkeypatch.setenv("QOD_API_KEY", "k")


def _routes(respx_mock, secret_status=200):
    """Mock the create and the secret endpoints. Registered even by the tests that expect the CLI to
    refuse before calling, so a lost guard surfaces as an assertion rather than a real request."""
    create = respx_mock.post(SOURCES).mock(return_value=httpx.Response(200, json={"alias": "x"}))
    secret = respx_mock.put(f"{SOURCES}/shop_pg/secrets").mock(
        return_value=httpx.Response(secret_status, json={"name": "PASSWORD"})
    )
    return create, secret


def _invoke(runner, *args, input=None):
    from qod_cli.main import app

    return runner.invoke(app, ["federation", "create", "acme", "db1", *args], input=input)


def _body(route):
    return json.loads(route.calls[-1].request.content.decode())


PG = ("--alias", "shop_pg", "--type", "postgres", "--host", "pg.example.com",
      "--database", "shop", "--user", "reader")


def test_postgres_flags_with_a_secret_reference(runner, respx_mock):
    create, secret = _routes(respx_mock)
    result = _invoke(runner, *PG, "--port", "6432", "--password", "{{secret.PG_PWD}}",
                     "--schema", "sales", "--ssl-mode", "require")
    assert result.exit_code == 0, result.output
    body = _body(create)
    assert body["sourceType"] == "postgres"
    assert body["connection"] == {
        "host": "pg.example.com",
        "port": 6432,
        "database": "shop",
        "user": "reader",
        "password": "{{secret.PG_PWD}}",
        "schema": "sales",
        "sslMode": "require",
    }
    assert "config" not in body and "setupSql" not in body
    # read-only is the manager's default for a typed source; the CLI sends nothing unless told.
    assert "readOnly" not in body
    assert not secret.called


def test_password_stdin_stores_the_value_as_a_secret_never_in_the_source(runner, respx_mock):
    create, secret = _routes(respx_mock)
    result = _invoke(runner, *PG, "--password-stdin", input="s3cr3t'pw\n")
    assert result.exit_code == 0, result.output
    assert _body(create)["connection"]["password"] == "{{secret.PASSWORD}}"
    assert "s3cr3t" not in create.calls[-1].request.content.decode()
    assert _body(secret) == {"name": "PASSWORD", "value": "s3cr3t'pw"}
    # The source first: its secret can only be written once it exists.
    assert create.calls[-1].request is not None and secret.call_count == 1


def test_password_prompt_and_custom_secret_name(runner, respx_mock):
    create, secret = _routes(respx_mock)
    result = _invoke(runner, *PG, "--password-prompt", "--password-secret", "PG_PWD",
                     input="hunter2\nhunter2\n")
    assert result.exit_code == 0, result.output
    assert _body(create)["connection"]["password"] == "{{secret.PG_PWD}}"
    assert _body(secret) == {"name": "PG_PWD", "value": "hunter2"}


def test_a_literal_password_flag_is_refused(runner, respx_mock):
    create, _ = _routes(respx_mock)
    result = _invoke(runner, *PG, "--password", "hunter2")
    assert result.exit_code != 0
    assert "shell history" in result.output
    assert not create.called


def test_a_password_is_required(runner, respx_mock):
    create, _ = _routes(respx_mock)
    result = _invoke(runner, *PG)
    assert result.exit_code != 0
    assert "needs a password" in result.output
    assert not create.called


def test_host_database_user_are_required(runner, respx_mock):
    create, _ = _routes(respx_mock)
    result = _invoke(runner, "--alias", "shop_pg", "--type", "postgres",
                     "--password", "{{secret.X}}")
    assert result.exit_code != 0
    assert "--host" in result.output and "--database" in result.output
    assert not create.called


def test_whole_connection_json_wins(runner, respx_mock):
    create, _ = _routes(respx_mock)
    conn = {"host": "h", "database": "d", "user": "u", "password": "{{secret.P}}"}
    result = _invoke(runner, "--alias", "shop_pg", "--type", "mysql",
                     "--connection", json.dumps(conn))
    assert result.exit_code == 0, result.output
    body = _body(create)
    assert body["sourceType"] == "mysql"
    assert body["connection"] == conn


def test_connection_flags_are_refused_for_other_types(runner, respx_mock):
    create, _ = _routes(respx_mock)
    result = _invoke(runner, "--alias", "x", "--setup-sql", "ATTACH 'x';", "--host", "h")
    assert result.exit_code != 0
    assert "--type postgres or mysql" in result.output
    assert not create.called


def test_setup_sql_is_refused_for_postgres(runner, respx_mock):
    create, _ = _routes(respx_mock)
    result = _invoke(runner, *PG, "--password", "{{secret.X}}", "--setup-sql", "ATTACH 'x';")
    assert result.exit_code != 0
    assert not create.called


def test_a_failed_secret_write_says_how_to_finish(runner, respx_mock):
    create, secret = _routes(respx_mock, secret_status=400)
    result = _invoke(runner, *PG, "--password-stdin", input="pw\n")
    assert result.exit_code != 0
    assert create.called and secret.called
    assert "qod federation secret set acme db1 shop_pg --name PASSWORD" in result.output
