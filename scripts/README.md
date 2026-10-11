# scripts

Run every script from the repository root (`./scripts/...`). Most scripts come
in pairs: a start/stop pair, or a bash script and its PowerShell twin (`.sh` /
`.ps1`) for Windows. Each script's header comment documents its full set of
environment variables.

The top level holds what operators type and what the manager itself invokes.
Everything else lives in a subfolder by audience.

## Top level

| Scripts | Purpose |
|---|---|
| `run-jar.sh` / `stop-jar.sh` | Boot the manager from the release jar (`QOD_VERSION=latest\|<version>\|latest-snapshot\|BUILD\|LOCAL`), with TLS cert auto-generation, a Postgres probe and optional demo data (`LOAD_TPCH`, `LOAD_TPCDS`, `LOAD_SSB`, `NUKE`, `DEMO`). Stop it with `stop-jar.sh` (SIGTERM, wait, SIGKILL, reaping child nodes). Never kill the JVM by hand: orphaned DuckDB nodes keep holding ports `21900+`. |
| `run-jar.ps1` / `stop-jar.ps1` | PowerShell twins of the pair above for running natively on Windows. |
| `spawn-quack-node.sh` / `spawn-quack-node.ps1` | Start one DuckDB Quack node. **Invoked by `LocalQuackBackend`, never by hand.** The default `spawnScript` path in `application.conf`, the Docker images (`/app/scripts/`) and the CLI wheel (`cli/src/qod_cli/scripts/`, byte-identical copy) all depend on this location. |
| `load-tpch-dbgen.sh` / `.ps1` | Seed a DuckLake database with TPC-H (`dbgen()`, default `acme_tpch.tpch1`). |
| `load-tpcds-dbgen.sh` / `.ps1` | Seed TPC-DS (`dsdgen()`, default `globex_tpcds.tpcds1`; needs the `tpcds` extension download). |
| `load-ssb-dbgen.sh` / `.ps1` | Seed the Star Schema Benchmark, derived from TPC-H (default `acme_tpch.ssb1`). |
| `_load-common.sh` / `_load-common.ps1` | Shared helpers sourced by the three loaders (functions only). The loaders stay at the top level because `run-jar`, the Docker image and the CLI wheel all ship them next to the spawn script. |
| `kill-quack-nodes.sh` | Kill one manager's stray `spawn-quack-node.sh` processes and their DuckDB children without touching the manager (`--port <rest-port>`, default `QOD_ON_DEMAND_PORT`); `--all` sweeps every node on the host. This is the recovery step after an unclean manager exit. |
| `install.sh` | `curl \| sh` installer: bootstraps `uv`, then installs the `qod` CLI. Its URL is published, so it must not move. |

## `docker/`: container launchers

| Scripts | Purpose |
|---|---|
| `run-docker.sh` / `stop-docker.sh` | Run the single container image against an **external** Postgres (`docker run --rm`), then stop it. Same `QOD_VERSION` contract as `run-jar.sh`. |
| `run-docker-compose.sh` / `stop-docker-compose.sh` | Bring up the full bundled stack from `docker-compose.yml` (Postgres, object store, optional observability and Starflow profiles, demo seeding through the in-image loaders), then drain it down (`NUKE=1` also wipes host state). |
| `_docker-common.sh` | Shared proxy and loopback helpers sourced by both launchers. |

## `release/`: maintainer release tooling

| Scripts | Purpose |
|---|---|
| `release.sh` | Cut a release: verify, stamp versions, tag, push. Pushing the tag makes CI (`release.yml`) publish everything. |
| `release-lib.sh` | Shared helpers sourced by `release.sh`, `release-docker.sh` and `release.yml`. |
| `release-docker.sh` | Manual fallback for publishing the Docker image when the CI channel is broken. |
| `announce-release-discord.sh` | Post a version's CHANGELOG section to Discord. Run by CI, and can be re-run by hand. |
| `refresh-quackwire-binaries.sh` | Rebuild and download the vendored `libquackwire` binaries, then stop so you can review and commit them. |

## `bench/`: clients and load tests

| Scripts | Purpose |
|---|---|
| `adbc.sh` | Run one SQL query against a FlightSQL endpoint through ADBC and print the result. |
| `tpch-load-test/tpch-load-test.py` | Multi-threaded FlightSQL load tester (throughput, success rate, latency percentiles). |
| `load-22/run.sh` (+ `benchmark.py`) | TPC-H 22-query benchmark that produces a CSV and HTML report. See `load-22/README.md`. |

## `dev/`: local developer shortcuts

Hardcoded dev values, not meant for real deployments.

| Scripts | Purpose |
|---|---|
| `fleet-start.sh` / `fleet-agent1-start.sh` / `fleet-agent2-start.sh` | Local fleet-mode loop: a manager in fleet mode with short heartbeats, plus two `qod fleet join` servers on ports 23101 / 23102. Run each in its own terminal. |
| `run-wsl-jar.sh` | One-liner for WSL: source build, TPC-H seed, nuke. |
| `start-quack-ducklake.sh` | A standalone Quack server over a DuckLake catalog with no manager involved, for poking at DuckDB/Quack directly. |

## `fixtures/`: test fixtures

| File | Purpose |
|---|---|
| `iceberg-fixture.yml` | Docker Compose Iceberg REST catalog (unauthenticated) on rustfs, used by the Iceberg end-to-end specs and the `iceberg.yml` CI workflow. |
