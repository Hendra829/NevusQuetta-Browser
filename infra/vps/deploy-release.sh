#!/usr/bin/env bash
set -Eeuo pipefail

VERSION="${1:?versi rilis wajib diisi}"
SOURCE_DIR="${2:?direktori paket rilis wajib diisi}"
ROOT_DIR="/srv/nevusquetta"
RELEASE_DIR="$ROOT_DIR/releases/$VERSION"
CURRENT_LINK="$ROOT_DIR/current"

[[ -d "$SOURCE_DIR" ]] || { echo "Direktori sumber tidak ditemukan: $SOURCE_DIR" >&2; exit 1; }
[[ -f "$SOURCE_DIR/NevusQuetta.apk" ]] || { echo "NevusQuetta.apk tidak ditemukan" >&2; exit 1; }
[[ -f "$SOURCE_DIR/NevusQuetta-source.zip" ]] || { echo "NevusQuetta-source.zip tidak ditemukan" >&2; exit 1; }
[[ -f "$SOURCE_DIR/SHA256SUMS.txt" ]] || { echo "SHA256SUMS.txt tidak ditemukan" >&2; exit 1; }

mkdir -p "$RELEASE_DIR"
rsync -a --delete "$SOURCE_DIR/" "$RELEASE_DIR/"

(
  cd "$RELEASE_DIR"
  sha256sum -c SHA256SUMS.txt
)

ln -sfn "$RELEASE_DIR" "$ROOT_DIR/current.next"
mv -Tf "$ROOT_DIR/current.next" "$CURRENT_LINK"

echo "DEPLOY=PASS"
echo "VERSION=$VERSION"
echo "CURRENT=$CURRENT_LINK"
