import os
from pathlib import Path

import typer

from .. import launcher
from ._launch import _exec, resolve_jar, resolve_java


def run_demo(ctx: typer.Context, version: str | None, jar: Path | None) -> None:
    """The `qod serve --demo` path (deprecated alias: `qod start --demo`): the
    self-contained quack-on-demand demo (embedded ephemeral Postgres, seeded
    data). Kept in its own module so the demo provisioning stays independent
    of the real-manager start path."""
    java = resolve_java()
    jar_path = jar if jar is not None else resolve_jar(version)

    # The jar spawns nodes through the spawn script and the `duckdb` CLI, and
    # its native client links libduckdb; all three only exist in a repo
    # checkout. Provision them next to the jar cache so the demo runs from any
    # working directory.
    app_home = launcher.default_cache_dir()
    try:
        duckdb_bin = launcher.ensure_duckdb_cli(app_home)
        libduckdb = launcher.ensure_libduckdb(app_home)
    except Exception as e:
        typer.echo(f"could not provision duckdb: {e}", err=True)
        raise typer.Exit(1)
    spawn_sh, spawn_ps1 = launcher.materialize_spawn_scripts(app_home / "scripts")
    env = launcher.runtime_env(
        dict(os.environ), app_home, duckdb_bin, spawn_sh, spawn_ps1, libduckdb_lib=libduckdb
    )

    _exec(launcher.build_jar_command(java, str(jar_path), ["demo", *ctx.args]), env)