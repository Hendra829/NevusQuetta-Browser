#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(git rev-parse --show-toplevel)"
DIST="${1:-$ROOT/dist}"
APK="${2:-$ROOT/B-Android/app/build/outputs/apk/release/app-release.apk}"
STAGE="$DIST/.source-stage"

[[ -s "$APK" ]] || { echo "Signed release APK not found: $APK" >&2; exit 1; }

rm -rf "$DIST"
mkdir -p "$DIST" "$STAGE"
cp "$APK" "$DIST/NevusQuetta.apk"

git -C "$ROOT" archive --format=tar HEAD | tar -xf - -C "$STAGE"

find "$STAGE" -type f \(     -iname '*v0.8*.zip' -o     -iname '*v0.8*.tar' -o     -iname '*v0.8*.tar.gz' -o     -iname '*v0.8*.tgz' -o     -iname '*v0.8*.7z' \) -delete

find "$STAGE" -depth -type d \(     -name build -o     -name .gradle -o     -name cache -o     -name caches -o     -name node_modules \) -exec rm -rf {} +

mapfile -t forbidden < <(
    find "$STAGE" -type f \(         -name '.env' -o -name '.env.*' -o         -name '*.jks' -o -name '*.keystore' -o         -name 'local.properties' -o         -name 'secrets.properties' -o         -name 'credentials.json'     \) -print
)
if (( ${#forbidden[@]} > 0 )); then
    printf 'Forbidden release source file: %s\n' "${forbidden[@]}" >&2
    exit 1
fi

(
    cd "$STAGE"
    zip -X -q -r "$DIST/NevusQuetta-source.zip" .
)
rm -rf "$STAGE"

zipinfo -1 "$DIST/NevusQuetta-source.zip" > "$DIST/source-files.txt"
if grep -Eiq '(^|/)(build|\.gradle|cache|caches|node_modules)/|(^|/)\.env($|\.)|\.(jks|keystore)$|(^|/)local\.properties$|(^|/)secrets\.properties$' "$DIST/source-files.txt"; then
    echo "Final source ZIP contains forbidden content" >&2
    exit 1
fi
if grep -Eiq 'v0\.8.*\.(zip|tar|tar\.gz|tgz|7z)$' "$DIST/source-files.txt"; then
    echo "Legacy V0.8 binary archive leaked into final ZIP" >&2
    exit 1
fi

(
    cd "$DIST"
    sha256sum NevusQuetta.apk NevusQuetta-source.zip > SHA256SUMS.txt
)

echo "PACKAGE_RELEASE=PASS"
echo "APK=$DIST/NevusQuetta.apk"
echo "SOURCE_ZIP=$DIST/NevusQuetta-source.zip"
