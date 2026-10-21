#!/usr/bin/env bash
# K8s entrypoint for the Quack node image. The manager (KubernetesQuackBackend)
# passes the listen port via QOD_NODE_PORT; every other plain env var (pgHost, pgPort,
# pgUser, dbName, schemaName, dataPath, plus the optional non-secret QOD_S3_* knobs)
# is the merged defaultMetastore + spec.metastore that LocalQuackBackend would read
# in local mode. The sensitive values (token, pgPassword, encryptionKey, federation
# and object-store SQL, object-store credentials) are NOT env vars: they are files
# of the read-only Secret volume named by QOD_SECRETS_DIR, which
# spawn-quack-node.sh reads itself. Only the port is passed on the command line.

set -euo pipefail

: "${QOD_SECRETS_DIR:?QOD_SECRETS_DIR is required (the node secrets volume)}"
PORT="${QOD_NODE_PORT:-8080}"

exec /app/scripts/spawn-quack-node.sh "$PORT"
