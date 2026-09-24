#!/usr/bin/env bash
set -Eeuo pipefail

VERSION="${1:?versi rilis wajib diisi}"
SOURCE_DIR="${2:?direktori paket rilis wajib diisi}"
ROOT_DIR="/srv/nevusquetta"
RELEASES_DIR="$ROOT_DIR/releases"
FINAL_DIR="$RELEASES_DIR/$VERSION"
STAGE_DIR="$RELEASES_DIR/.incoming-$VERSION-$$"
CURRENT_LINK="$ROOT_DIR/current"
LINK_TMP="$ROOT_DIR/.current-$VERSION-$$"

[[ "$VERSION" =~ ^[0-9A-Za-z._-]+$ ]] || {
  echo "Versi mengandung karakter tidak aman: $VERSION" >&2
  exit 1
}
[[ -d "$SOURCE_DIR" ]] || { echo "Direktori sumber tidak ditemukan: $SOURCE_DIR" >&2; exit 1; }
[[ -f "$SOURCE_DIR/NevusQuetta.apk" ]] || { echo "NevusQuetta.apk tidak ditemukan" >&2; exit 1; }
[[ -f "$SOURCE_DIR/NevusQuetta-source.zip" ]] || { echo "NevusQuetta-source.zip tidak ditemukan" >&2; exit 1; }
[[ -f "$SOURCE_DIR/SHA256SUMS.txt" ]] || { echo "SHA256SUMS.txt tidak ditemukan" >&2; exit 1; }
[[ ! -e "$FINAL_DIR" ]] || { echo "Release immutable sudah ada: $FINAL_DIR" >&2; exit 1; }

cleanup() {
  rm -rf "$STAGE_DIR"
  rm -f "$LINK_TMP"
}
trap cleanup EXIT

mkdir -p "$RELEASES_DIR"
mkdir "$STAGE_DIR"
rsync -a --delete "$SOURCE_DIR/" "$STAGE_DIR/"

(
  cd "$STAGE_DIR"
  sha256sum -c SHA256SUMS.txt
)

[[ -s "$STAGE_DIR/NevusQuetta.apk" ]] || { echo "APK hasil staging kosong" >&2; exit 1; }
[[ -s "$STAGE_DIR/NevusQuetta-source.zip" ]] || { echo "ZIP hasil staging kosong" >&2; exit 1; }

mv "$STAGE_DIR" "$FINAL_DIR"
ln -s "releases/$VERSION" "$LINK_TMP"
mv -Tf "$LINK_TMP" "$CURRENT_LINK"

resolved="$(readlink -f "$CURRENT_LINK")"
expected="$(readlink -f "$FINAL_DIR")"
[[ "$resolved" == "$expected" ]] || {
  echo "Symlink release final tidak menunjuk ke release yang baru" >&2
  exit 1
}

trap - EXIT
echo "DEPLOY=PASS"
echo "VERSION=$VERSION"
echo "CURRENT=$CURRENT_LINK"
echo "CURRENT_TARGET=$resolved"
