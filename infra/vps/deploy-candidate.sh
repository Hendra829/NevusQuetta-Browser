#!/usr/bin/env bash
set -Eeuo pipefail

BUILD_ID="${1:?build id wajib diisi}"
SOURCE_DIR="${2:?direktori kandidat wajib diisi}"
ROOT_DIR="/srv/nevusquetta"
CANDIDATE_DIR="$ROOT_DIR/candidates/$BUILD_ID"

[[ -d "$SOURCE_DIR" ]] || { echo "Direktori sumber tidak ditemukan" >&2; exit 1; }
[[ -f "$SOURCE_DIR/NevusQuetta.apk" ]] || { echo "NevusQuetta.apk tidak ditemukan" >&2; exit 1; }
[[ -f "$SOURCE_DIR/NevusQuetta-source.zip" ]] || { echo "NevusQuetta-source.zip tidak ditemukan" >&2; exit 1; }
[[ -f "$SOURCE_DIR/SHA256SUMS.txt" ]] || { echo "SHA256SUMS.txt tidak ditemukan" >&2; exit 1; }

mkdir -p "$CANDIDATE_DIR"
rsync -a --delete "$SOURCE_DIR/" "$CANDIDATE_DIR/"

(
  cd "$CANDIDATE_DIR"
  sha256sum -c SHA256SUMS.txt
)

echo "CANDIDATE_DEPLOY=PASS"
echo "PATH=$CANDIDATE_DIR"
