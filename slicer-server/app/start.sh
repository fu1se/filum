#!/bin/bash
# Filum Slicer without building an image: runs in a stock ubuntu:24.04 container with this
# directory mounted at /app (used by the TrueNAS "no build" variant, see deploy/).
# 1) once per container: system libraries; 2) once per version: OrcaSlicer into /data; 3) the server.
set -euo pipefail

ORCA_VERSION="${ORCA_VERSION:-2.4.2}"
DATA="${SLICER_DATA:-/data}"
ORCA_ROOT="$DATA/orca"
MARKER=/var/lib/filum-slicer-deps-v1

if [ ! -f "$MARKER" ]; then
    echo "[filum-slicer] installing system packages (first start of the container only)…"
    bash /app/install_orca.sh deps
    touch "$MARKER"
fi

DIR="$ORCA_ROOT/$ORCA_VERSION"
if [ ! -x "$DIR/AppRun" ]; then
    echo "[filum-slicer] downloading OrcaSlicer ${ORCA_VERSION}…"
    bash /app/install_orca.sh orca "$DIR" "$ORCA_VERSION"
    # old versions are no longer needed
    find "$ORCA_ROOT" -mindepth 1 -maxdepth 1 -type d ! -name "$ORCA_VERSION" ! -name current -exec rm -rf {} +
fi
ln -sfn "$DIR" "$ORCA_ROOT/current"

export ORCA_DIR="$ORCA_ROOT/current" SLICER_DATA="$DATA" PYTHONUNBUFFERED=1
exec python3 /app/server.py
