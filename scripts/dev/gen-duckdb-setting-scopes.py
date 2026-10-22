#!/usr/bin/env python3
"""Generates src/main/scala/ai/starlake/quack/edge/sql/DuckDbSettingScopes.scala: every core
DuckDB setting and every option of the extensions a QoD node can load, with its aliases and the
scope a bare `SET` / `RESET` / `PRAGMA x = v` lands in. NodeSettingScreen judges node-wide
settings from it. Re-run it at every engine pin bump, then
`sbt "scalafmtOnly src/main/scala/ai/starlake/quack/edge/sql/DuckDbSettingScopes.scala"` and
`sbt "testOnly *DuckDbSettingScopesSpec"` (the drift spec fails until the table matches the engine
the tests load).

Read from the engine source, never from a running DuckDB: `duckdb_settings().scope` reports where
the current value lives, not where a bare SET goes, and hides the debug settings and the aliases.

  core       src/main/config.cpp (internal_options: DUCKDB_SETTING / _CALLBACK take the struct's
             Scope; DUCKDB_GLOBAL has only a global setter, DUCKDB_LOCAL only a local one,
             DUCKDB_GLOBAL_LOCAL both, and a bare SET picks the local one first: see
             PhysicalSet::GetSettingScope) + src/include/duckdb/main/settings.hpp (Name, Scope,
             IsDebug) + setting_aliases.
  extensions every `AddExtensionOption(name, ..., default_scope)` call: the sixth argument, default
             SetScope::SESSION (config.hpp). A bare SET of an extension option lands in its
             default scope; an explicit SESSION or GLOBAL is always accepted (PhysicalSet).

Engine: $DUCKDB_SRC, else $QOD_PRERELEASE_WORK/duckdb (default ~/.cache/qod-duckdb-prerelease,
what scripts/dev/build-duckdb-prerelease.sh builds). In-tree extensions are read from its
extension/ dir. Out-of-tree ones are read at the GIT_TAG the engine pins in
.github/config/extensions/<name>.cmake: from the engine build's _deps when it is at that commit,
else from $QOD_PRERELEASE_WORK/ext-src/<name>, fetched (shallow, that one commit) when missing.
"""

import os
import re
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
OUT = REPO / "src/main/scala/ai/starlake/quack/edge/sql/DuckDbSettingScopes.scala"

# What a node may load: linked into the node build (build-duckdb-prerelease.sh), installed by the
# spawn scripts and the federation setup SQL (httpfs, azure, postgres, iceberg + avro, aws, mysql).
IN_TREE = ["core_functions", "json", "parquet", "icu", "autocomplete", "tpch", "tpcds"]
OUT_OF_TREE = {
    "quack": "duckdb-quack",
    "httpfs": "duckdb-httpfs",
    "ducklake": "ducklake",
    "postgres_scanner": "duckdb-postgres",
    "mysql_scanner": "duckdb-mysql",
    "iceberg": "duckdb-iceberg",
    "avro": "duckdb-avro",
    "aws": "duckdb-aws",
    "azure": "duckdb-azure",
}

WORK = Path(os.environ.get("QOD_PRERELEASE_WORK", Path.home() / ".cache/qod-duckdb-prerelease"))
ENGINE = Path(os.environ.get("DUCKDB_SRC", WORK / "duckdb"))


def die(msg):
    sys.exit(f"gen-duckdb-setting-scopes: {msg}")


def git(d, *args):
    return subprocess.run(["git", "-C", str(d), *args], check=True, capture_output=True, text=True).stdout.strip()


# ---- C++ reading: just enough for these call sites -------------------------------------------


def strip_comments(src):
    out, i, n = [], 0, len(src)
    while i < n:
        c = src[i]
        if c == '"' or c == "'":
            j = i + 1
            while j < n and src[j] != c:
                j += 2 if src[j] == "\\" else 1
            out.append(src[i : j + 1])
            i = j + 1
        elif src.startswith("//", i):
            j = src.find("\n", i)
            i = n if j < 0 else j
        elif src.startswith("/*", i):
            j = src.find("*/", i + 2)
            i = n if j < 0 else j + 2
        else:
            out.append(c)
            i += 1
    return "".join(out)


def call_args(src, open_paren):
    """Top-level comma-separated arguments of the call whose '(' is at open_paren."""
    depth, i, start, args = 0, open_paren, open_paren + 1, []
    while i < len(src):
        c = src[i]
        if c == '"' or c == "'":
            j = i + 1
            while src[j] != c:
                j += 2 if src[j] == "\\" else 1
            i = j
        elif c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
            if depth == 0:
                args.append(src[start:i].strip())
                return args
        elif c == "," and depth == 1:
            args.append(src[start:i].strip())
            start = i + 1
        i += 1
    die("unterminated call")


def string_value(expr, constants):
    """A C++ string expression: adjacent literals, or a named constant."""
    lits = re.findall(r'"((?:[^"\\]|\\.)*)"', expr)
    if lits and re.fullmatch(r'(\s*"(?:[^"\\]|\\.)*"\s*)+', expr):
        return "".join(lits)
    if expr in constants:
        return constants[expr]
    return None


def constants_of(root):
    consts = {}
    pat = re.compile(r'(?:#define\s+(\w+)\s+"([^"]*)"|\b(\w+)\s*(?:\[\])?\s*=\s*"([^"]*)"\s*;)')
    for f in root.rglob("*"):
        if f.suffix in (".cpp", ".hpp", ".h") and f.is_file():
            for m in pat.finditer(f.read_text(errors="replace")):
                k, v = (m.group(1), m.group(2)) if m.group(1) else (m.group(3), m.group(4))
                consts.setdefault(k, v)
    return consts


# ---- core -------------------------------------------------------------------------------------


def core_settings():
    hpp = strip_comments((ENGINE / "src/include/duckdb/main/settings.hpp").read_text())
    structs = {}
    for m in re.finditer(r"\nstruct (\w+) \{(.*?)\n\};", hpp, re.S):
        body = m.group(2)
        name = re.search(r'Name = "([^"]+)"', body)
        if not name:
            continue
        scope = re.search(r"Scope = SettingScopeTarget::(\w+);", body)
        debug = re.search(r"IsDebug = (true|false);", body)
        structs[m.group(1)] = (name.group(1), scope and scope.group(1), debug and debug.group(1) == "true")

    cpp = strip_comments((ENGINE / "src/main/config.cpp").read_text())
    table = re.search(r"internal_options\[\] = \{(.*?)FINAL_SETTING", cpp, re.S).group(1)
    settings = {}
    for kind, struct in re.findall(r"DUCKDB_(SETTING_CALLBACK|SETTING|GLOBAL_LOCAL|GLOBAL|LOCAL)\((\w+)\)", table):
        if struct not in structs:
            die(f"config.cpp names {struct}, not found in settings.hpp")
        name, target, debug = structs[struct]
        if kind in ("SETTING", "SETTING_CALLBACK"):
            scope = {
                "GLOBAL_ONLY": "GlobalOnly",
                "GLOBAL_DEFAULT": "GlobalDefault",
                "LOCAL_DEFAULT": "LocalDefault",
                "LOCAL_ONLY": "LocalOnly",
            }.get(target)
            if scope is None:
                die(f"{name}: no valid Scope ({target})")
        else:
            scope = {"GLOBAL": "GlobalOnly", "LOCAL": "LocalOnly", "GLOBAL_LOCAL": "LocalDefault"}[kind]
        settings[name.lower()] = (scope, "core", debug)
    if len(settings) < 150:
        die(f"only {len(settings)} core settings read: config.cpp layout changed?")

    aliases_src = re.search(r"setting_aliases\[\] = \{(.*?)FINAL_ALIAS", cpp, re.S).group(1)
    aliases = {}
    for alias, struct in re.findall(r'DUCKDB_SETTING_ALIAS\("([^"]+)",\s*(\w+)\)', aliases_src):
        aliases[alias.lower()] = structs[struct][0].lower()
    return settings, aliases


# ---- extensions -------------------------------------------------------------------------------


def extension_source(name, repo):
    cmake = ENGINE / f".github/config/extensions/{name}.cmake"
    tag = re.search(r"GIT_TAG\s+([0-9a-f]{40})", cmake.read_text()).group(1)
    patches = ENGINE / f".github/patches/extensions/{name}"
    if patches.is_dir():
        for p in patches.iterdir():
            if any(l.startswith("+") and ("AddExtensionOption" in l or "SetScope" in l) for l in p.read_text(errors="replace").splitlines()):
                die(f"patch {p} changes extension options: apply it before reading {name}")
    deps = ENGINE / f"build/release/_deps/{name}_extension_fc-src"
    if deps.is_dir() and git(deps, "rev-parse", "HEAD") == tag:
        return deps, tag
    d = WORK / "ext-src" / name
    if not (d / ".git").is_dir():
        d.mkdir(parents=True, exist_ok=True)
        git(d, "init", "-q")
        git(d, "remote", "add", "origin", f"https://github.com/duckdb/{repo}")
    if subprocess.run(["git", "-C", str(d), "rev-parse", "HEAD"], capture_output=True, text=True).stdout.strip() != tag:
        git(d, "fetch", "-q", "--depth", "1", "origin", tag)
        git(d, "checkout", "-q", "--detach", "FETCH_HEAD")
    return d, tag


def class_constant(src, expr):
    """`Class::NAME` declared in `src` as `NAME = "..."` inside `class Class` (or struct)."""
    m = re.fullmatch(r"(\w+)::(\w+)", expr.strip())
    if not m:
        return None
    cls = re.search(rf"\b(?:class|struct)\s+{m.group(1)}\b[^;{{]*\{{", src)
    if not cls:
        return None
    val = re.search(rf'\b{m.group(2)}\s*=\s*"((?:[^"\\]|\\.)*)"', src[cls.end():])
    return val.group(1) if val else None


def option_object(src, f, opt, arg):
    """(scope argument, debug) of the `ExtensionOption <var>(...)` the two-argument
    `AddExtensionOption(name, std::move(<var>))` registers: the constructor takes
    (description, type, set_function, default_value, default_scope, is_debug, is_deprecated)."""
    var = re.fullmatch(r"(?:std::move\(\s*)?(\w+)\s*\)?", arg.strip())
    ctor = var and re.search(rf"\bExtensionOption\s+{var.group(1)}\s*\(", src)
    if not ctor:
        die(f"{f}: {opt}: cannot find the ExtensionOption {arg!r} it registers")
    cargs = call_args(src, ctor.end() - 1)
    if len(cargs) < 5:
        die(f"{f}: {opt}: ExtensionOption {var.group(1)} sets no default scope")
    # A bare SET of an option that refuses the session scope lands in the global one.
    no_session = re.search(rf"\b{var.group(1)}\.supports_session\s*=\s*false\b", src)
    scope_arg = "SetScope::GLOBAL" if no_session else cargs[4]
    return scope_arg, len(cargs) > 5 and cargs[5] == "true"


def extension_options(name, root):
    consts = constants_of(root)
    opts = {}
    for f in sorted(root.rglob("*.cpp")) + sorted(root.rglob("*.hpp")):
        if f.relative_to(root).parts[0] in ("duckdb", "test", "third_party", "extension-ci-tools"):
            continue
        src = strip_comments(f.read_text(errors="replace"))
        for m in re.finditer(r"\bAddExtensionOption\s*\(", src):
            args = call_args(src, m.end() - 1)
            if args and re.match(r"(const\s+)?(Identifier|string|std::string)\b", args[0]):
                continue  # a declaration, not a call
            opt = string_value(args[0], consts) or class_constant(src, args[0])
            if opt is None:
                die(f"{f}: cannot read the option name {args[0]!r}")
            if len(args) == 2:
                scope_arg, debug = option_object(src, f, opt, args[1])
            else:
                scope_arg = args[5] if len(args) > 5 else "SetScope::SESSION"
                debug = len(args) > 6 and args[6] == "true"
            sm = re.fullmatch(r"SetScope::(\w+)", scope_arg)
            if not sm:
                die(f"{f}: {opt}: cannot read the scope {scope_arg!r}")
            scope = "GlobalDefault" if sm.group(1) == "GLOBAL" else "LocalDefault"
            prev = opts.get(opt.lower())
            if prev and prev[0] != scope:
                scope = "GlobalDefault"
            opts[opt.lower()] = (scope, name, debug)
    return opts


def main():
    if not (ENGINE / "src/main/config.cpp").is_file():
        die(f"no DuckDB source at {ENGINE} (set DUCKDB_SRC or QOD_PRERELEASE_WORK)")
    source_id = git(ENGINE, "rev-parse", "HEAD")[:10]
    settings, aliases = core_settings()
    pins = []
    sources = [(e, ENGINE / "extension" / e, "in-tree") for e in IN_TREE if (ENGINE / "extension" / e).is_dir()]
    for e, repo in OUT_OF_TREE.items():
        d, tag = extension_source(e, repo)
        sources.append((e, d, tag))
    for e, d, tag in sources:
        pins.append((e, tag))
        for opt, entry in extension_options(e, d).items():
            if opt in settings or opt in aliases:
                continue  # the engine looks a core name up first (PhysicalSet::SetVariable)
            prev = settings.get(opt)
            if prev and prev[0] != entry[0]:
                entry = ("GlobalDefault", prev[1] + "," + e, entry[2])
            settings[opt] = entry

    lines = [
        "package ai.starlake.quack.edge.sql",
        "",
        "// GENERATED by scripts/dev/gen-duckdb-setting-scopes.py from the DuckDB source; do not edit.",
        "",
        "/** Every core DuckDB setting and every option of the extensions a QoD node can load, by",
        "  * lowercased canonical name, with the scope a bare `SET` / `RESET` / `PRAGMA name = value`",
        "  * lands in (DuckDB 2.0 resolves an unscoped statement to the setting's registered scope,",
        "  * `PhysicalSet::GetSettingScope`), plus the core aliases. Read by [[NodeSettingScreen]].",
        "  *",
        "  * An extension option's own set callback can also refuse `SET SESSION`",
        "  * (httpfs_client_implementation, httpfs_connection_caching, s3_url_scheme_aliases): the table",
        "  * still says GlobalDefault there, which only admits a statement the engine refuses.",
        "  *",
        "  * Regenerate at every engine pin bump: `python3 scripts/dev/gen-duckdb-setting-scopes.py`",
        "  * (reads the engine source build-duckdb-prerelease.sh checks out, and each extension at the",
        "  * commit the engine pins), then scalafmtOnly this file; `DuckDbSettingScopesSpec` fails until",
        "  * the table matches the engine the tests load.",
        "  */",
        "object DuckDbSettingScopes:",
        "",
        "  /** Where an unscoped SET / RESET lands, and whether an explicit `SET SESSION` is accepted. */",
        "  enum Scope:",
        "    /** Node-wide; `SET SESSION` is refused by the engine. */",
        "    case GlobalOnly",
        "",
        "    /** Node-wide when unscoped; `SET SESSION` keeps it to the session. */",
        "    case GlobalDefault",
        "",
        "    /** The session when unscoped; `SET GLOBAL` makes it node-wide. */",
        "    case LocalDefault",
        "",
        "    /** The session only. */",
        "    case LocalOnly",
        "",
        "  /** `origin` is `core` or the extension that registers the option. */",
        "  final case class Entry(scope: Scope, origin: String, debug: Boolean)",
        "",
        f'  val EngineSourceId = "{source_id}"',
        "",
        "  /** The extension commits the table was read at. */",
        "  val ExtensionPins: Map[String, String] = Map(",
    ]
    lines.append(",\n".join(f'    "{e}" -> "{t}"' for e, t in pins))
    lines += ["  )", "", "  import Scope.*", "", "  val Settings: Map[String, Entry] = Map("]
    lines.append(
        ",\n".join(
            f'    "{n}" -> Entry({s}, "{o}", {str(d).lower()})' for n, (s, o, d) in sorted(settings.items())
        )
    )
    lines += ["  )", "", "  /** Core alias -> canonical name, both lowercased. */", "  val Aliases: Map[String, String] = Map("]
    lines.append(",\n".join(f'    "{a}" -> "{c}"' for a, c in sorted(aliases.items())))
    lines += ["  )", ""]
    OUT.write_text("\n".join(lines))
    core = sum(1 for v in settings.values() if v[1] == "core")
    print(f"wrote {OUT.relative_to(REPO)}: {core} core settings, {len(settings) - core} extension options, {len(aliases)} aliases (engine {source_id})")


main()
