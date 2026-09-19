#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

project_root="$HOME/NevusQuetta-Browser"
branch="feat/v09ab-hardening"

pkg update -y
pkg install -y git gh openjdk-17 gradle

if [ -d "$project_root/.git" ]; then
  git -C "$project_root" fetch --prune origin
else
  gh repo clone Hendra829/NevusQuetta-Browser "$project_root"
fi

git -C "$project_root" switch "$branch"
git -C "$project_root" pull --ff-only origin "$branch"

cd "$project_root/B-Android"
if [ -z "${ANDROID_HOME:-}" ]; then
  echo "ANDROID_HOME belum disetel." >&2
  exit 1
fi
printf "sdk.dir=%s\n" "$ANDROID_HOME" > local.properties

./gradlew --no-daemon clean testDebugUnitTest lintDebug assembleDebug
apk="app/build/outputs/apk/debug/app-debug.apk"
test -s "$apk"
sha256sum "$apk" | tee app-debug.apk.sha256
printf "BRANCH: %s\nAPK: %s\n" "$branch" "$PWD/$apk"
