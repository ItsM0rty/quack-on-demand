"""A provisioned qod duckdb cache for launcher tests, so the real ensure_duckdb_cli /
ensure_libduckdb reuse it without touching the network."""

import pathlib
import sys

from qod_cli import launcher

LIB_NAMES = {"darwin": "libduckdb.dylib", "linux": "libduckdb.so", "win32": "duckdb.dll"}


def plant(cache_dir: pathlib.Path, version: str | None = None, os_name: str = sys.platform):
    """<cache>/duckdb/<version>/{bin,lib} holding a stand-in CLI and libduckdb; returns the
    (bin, lib) dirs."""
    version = version or launcher.DUCKDB_CLI_VERSION
    home = cache_dir / "duckdb" / version
    exe = home / "bin" / ("duckdb.exe" if os_name == "win32" else "duckdb")
    lib = home / "lib" / LIB_NAMES[os_name]
    for f in (exe, lib):
        f.parent.mkdir(parents=True, exist_ok=True)
        f.write_text("")
    exe.chmod(0o755)
    return exe.parent, lib.parent


def preload_target(env: dict, os_name: str = sys.platform) -> pathlib.Path:
    """The libduckdb the manager's LibDuckDbPreload loads for this JVM environment:
    $DUCKDB_CACHE_DIR/<abi>/lib/<libduckdb>, where <abi> is the bundled libquackwire's DuckDB
    version, which the launcher pins as DUCKDB_CLI_VERSION."""
    assert env.get("DUCKDB_CACHE_DIR"), "the JVM gets no DUCKDB_CACHE_DIR"
    return (
        pathlib.Path(env["DUCKDB_CACHE_DIR"])
        / launcher.DUCKDB_CLI_VERSION
        / "lib"
        / LIB_NAMES[os_name]
    )
