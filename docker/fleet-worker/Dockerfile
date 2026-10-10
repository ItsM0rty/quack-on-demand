# syntax=docker/dockerfile:1.7
#
# Fleet server image: one quack node per container, assigned by a manager running with
# QOD_RUNTIME_TYPE=fleet. Runs `qod fleet join` (heartbeats, runs the assigned node through the
# bundled spawn script). Contract (see entrypoint.sh):
#
#   required  QOD_MANAGER_URL, QOD_FLEET_JOIN_TOKEN, QOD_FLEET_NAME, QOD_FLEET_ADVERTISE_HOST
#   optional  QOD_FLEET_NODE_PORT (21900; publish the SAME number: -p 21901:21901),
#             QOD_FLEET_INSECURE=true (plain http:// manager URL), QOD_S3_* / QOD_AZURE_*, PG*
#
# Build: docker build -t starlakeai/quack-on-demand-worker:local -f docker/fleet-worker/Dockerfile .

# The qod wheel from this checkout, never a PyPI release that may lag it. Pure Python, so the
# builder's interpreter version does not matter.
FROM python:3.11-slim-bookworm AS wheel
COPY cli/ /src/cli/
RUN pip wheel --no-deps --wheel-dir /wheels /src/cli

FROM debian:bookworm-slim

ARG TARGETARCH
ARG DUCKDB_VERSION=2.0.0

RUN <<EOF
set -eu
apt-get update
apt-get install -y --no-install-recommends \
  ca-certificates curl unzip bash postgresql-client tini python3 python3-venv
rm -rf /var/lib/apt/lists/*
EOF

RUN <<EOF
set -eu
case "${TARGETARCH:-$(dpkg --print-architecture)}" in
  amd64) ARCH=linux-amd64 ;;
  arm64) ARCH=linux-arm64 ;;
  *) echo "unsupported architecture" >&2; exit 1 ;;
esac
curl -fsSL --retry 5 --retry-all-errors --retry-delay 5 \
  "https://github.com/duckdb/duckdb/releases/download/v${DUCKDB_VERSION}/duckdb_cli-${ARCH}.zip" -o /tmp/duckdb.zip
unzip /tmp/duckdb.zip -d /usr/local/bin
chmod +x /usr/local/bin/duckdb
rm /tmp/duckdb.zip
duckdb -c 'SELECT version()'
EOF

# Pre-install the node's extensions so a node needs no outbound internet on first ATTACH.
RUN <<EOF
set -eu
mkdir -p /opt/duckdb-extensions
HOME=/opt/duckdb-extensions duckdb -c "INSTALL ducklake; INSTALL postgres; INSTALL quack; INSTALL httpfs;"
chmod -R a+rX /opt/duckdb-extensions
EOF

COPY --from=wheel /wheels /tmp/wheels
RUN python3 -m venv /opt/qod \
 && /opt/qod/bin/pip install --no-cache-dir /tmp/wheels/*.whl \
 && rm -rf /tmp/wheels \
 && /opt/qod/bin/qod --help >/dev/null

COPY --chmod=0755 docker/fleet-worker/entrypoint.sh /usr/local/bin/fleet-worker-entrypoint

RUN useradd --create-home --shell /bin/bash --uid 1000 quack \
 && chown -R quack:quack /opt/duckdb-extensions
ENV HOME=/home/quack \
    PATH=/opt/qod/bin:$PATH
USER quack
WORKDIR /home/quack
RUN ln -sfn /opt/duckdb-extensions/.duckdb /home/quack/.duckdb

EXPOSE 21900

# tini forwards docker stop's SIGTERM to qod fleet join, which stops its node before exiting.
ENTRYPOINT ["/usr/bin/tini", "--", "/usr/local/bin/fleet-worker-entrypoint"]
