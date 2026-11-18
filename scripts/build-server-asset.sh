#!/usr/bin/env bash
# Build the Linux server binary that ships as app/src/main/assets/server.
#
# The repository root is its own Go module (it covers ./server), so its
# checksums must be present before `go build`. `go mod download` refreshes
# go.sum when go.mod has changed or when the file is missing entirely; without
# it CI fails with:
#   missing go.sum entry for module providing package github.com/pion/dtls/v3
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_FILE="$ROOT_DIR/app/src/main/assets/server"

command -v go >/dev/null 2>&1 || {
  echo "error: go not found in PATH" >&2
  exit 1
}

cd "$ROOT_DIR"
mkdir -p "$(dirname "$OUT_FILE")"

echo "Refreshing Go checksums for the server module"
go mod download

echo "Building linux/amd64 server -> ${OUT_FILE#"$ROOT_DIR"/}"
GOOS=linux GOARCH=amd64 CGO_ENABLED=0 \
  go build -trimpath -o "$OUT_FILE" ./server

echo "Done: $OUT_FILE"
