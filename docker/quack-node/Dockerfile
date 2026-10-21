

# syntax=docker/dockerfile:1.7
#
# Image for one Quack node pod (the data plane that the manager spawns via
# KubernetesQuackBackend). Wraps the DuckDB CLI + the project's
# spawn-quack-node.sh so the same boot script that LocalQuackBackend uses
# also runs in K8s. Env-var contract:
#
#   QOD_NODE_PORT            - set by the manager's pod template
#   QOD_SECRETS_DIR          - read-only Secret volume: token, pgPassword, encryptionKey,
#                              federation / object-store SQL, object-store credentials
#                              (one file per key; never env vars)
#   pgHost / pgPort / pgUser / dbName / schemaName / dataPath
#                            - merged from defaultMetastore + spec.metastore
#   QOD_S3_ENDPOINT / _REGION / _URL_STYLE / _USE_SSL - optional object-store settings
#   HTTP_PROXY / HTTPS_PROXY           - optional egress proxy
#
# Build: docker build -t starlakeai/quack-on-demand-node:local -f docker/quack-node/Dockerfile .

FROM debian:bookworm-slim AS base

ARG TARGETARCH
ARG DUCKDB_VERSION=2.0.0

RUN <<EOF
set -eu
apt-get update
apt-get install -y --no-install-recommends \
  ca-certificates curl bash postgresql-client tini
rm -rf /var/lib/apt/lists/*
EOF

# DuckDB CLI from GitHub releases (linux-amd64 / linux-arm64).
RUN <<EOF
set -eu
case "${TARGETARCH:-$(dpkg --print-architecture)}" in
  amd64) ARCH=linux-amd64 ;;
  arm64) ARCH=linux-arm64 ;;
  *) echo "unsupported architecture" >&2; exit 1 ;;
esac
curl -fsSL "https://github.com/duckdb/duckdb/releases/download/v${DUCKDB_VERSION}/duckdb-cli-${ARCH}.tar.gz" -o /tmp/duckdb.tar.gz
tar -xzf /tmp/duckdb.tar.gz -C /usr/local/bin duckdb
chmod +x /usr/local/bin/duckdb
rm /tmp/duckdb.tar.gz
duckdb -c 'SELECT version()'
EOF

# Pre-install the three runtime extensions so the pod doesn't need outbound
# internet on first ATTACH. Stored under /opt/duckdb-extensions; the entrypoint
# loads from there via the standard extension search path.
RUN <<EOF
set -eu
mkdir -p /opt/duckdb-extensions
HOME=/opt/duckdb-extensions duckdb -c "INSTALL ducklake; INSTALL postgres; INSTALL quack; INSTALL httpfs;"
chmod -R a+rX /opt/duckdb-extensions
EOF

# The local spawn script is the single source of truth across local + K8s.
COPY scripts/spawn-quack-node.sh           /app/scripts/spawn-quack-node.sh
COPY docker/quack-node/entrypoint.sh       /usr/local/bin/quack-entrypoint
RUN chmod +x /app/scripts/spawn-quack-node.sh /usr/local/bin/quack-entrypoint

# Non-root with a writable HOME for duckdb's extension cache. Re-use the
# pre-installed extension directory. Pre-create the default data path
# (matches values.yaml storage.dataPath = /var/quack/ducklake) so the
# spawn script's `mkdir -p` doesn't hit Permission denied at boot.
RUN useradd --create-home --shell /bin/bash --uid 1000 quack \
 && chown -R quack:quack /opt/duckdb-extensions \
 && install -d -o quack -g quack -m 0755 /var/quack /var/quack/ducklake
ENV HOME=/home/quack
USER quack
WORKDIR /home/quack
# Symlink the pre-installed extensions into the per-user .duckdb so duckdb
# finds them without re-downloading.
RUN ln -sfn /opt/duckdb-extensions/.duckdb /home/quack/.duckdb || true

EXPOSE 8080

# tini reaps zombie children + propagates SIGTERM cleanly to duckdb.
ENTRYPOINT ["/usr/bin/tini", "--", "/usr/local/bin/quack-entrypoint"]