#!/bin/bash
# System libraries for the OrcaSlicer CLI and the OrcaSlicer AppImage itself (extracted, no FUSE needed).
#   install_orca.sh deps            — apt packages (Ubuntu 24.04)
#   install_orca.sh orca <dir> <v>  — download OrcaSlicer <v> and extract it into <dir>
set -euo pipefail

case "${1:-}" in
deps)
    export DEBIAN_FRONTEND=noninteractive
    apt-get update -qq
    apt-get install -y -qq --no-install-recommends \
        python3 ca-certificates curl \
        libgtk-3-0t64 libwebkit2gtk-4.1-0 libsoup-3.0-0 \
        libgstreamer1.0-0 libgstreamer-plugins-base1.0-0 \
        libgl1 libegl1 libglx0 libopengl0 libglu1-mesa \
        libwayland-egl1 libwayland-client0 libwayland-server0 \
        libxkbcommon0 libsm6 libice6 libsecret-1-0 libdbus-1-3 libfontconfig1 >/dev/null
    rm -rf /var/lib/apt/lists/*
    ;;
orca)
    DIR="$2"; VERSION="$3"
    ARCH="$(uname -m)"
    [ "$ARCH" = "x86_64" ] || { echo "OrcaSlicer publishes Linux builds for x86_64 only (got $ARCH)" >&2; exit 1; }
    URL="https://github.com/OrcaSlicer/OrcaSlicer/releases/download/v${VERSION}/OrcaSlicer_Linux_AppImage_Ubuntu2404_V${VERSION}.AppImage"
    TMP="$(mktemp -d)"
    curl -fsSL --retry 5 --retry-delay 5 -o "$TMP/orca.AppImage" "$URL"
    chmod +x "$TMP/orca.AppImage"
    (cd "$TMP" && ./orca.AppImage --appimage-extract >/dev/null)
    rm -rf "$DIR" && mkdir -p "$(dirname "$DIR")" && mv "$TMP/squashfs-root" "$DIR"
    rm -rf "$TMP"
    ;;
*)
    echo "usage: $0 deps | orca <dir> <version>" >&2; exit 2 ;;
esac
