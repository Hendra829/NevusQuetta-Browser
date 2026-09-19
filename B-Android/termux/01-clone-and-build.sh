#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

repo_url='https://github.com/Hendra829/NevusQuetta-Browser.git'
project_root="$HOME/NevusQuetta-Browser"

pkg update -y
pkg install -y git gh openjdk-17 gradle

if [ -d "$project_root/.git" ]; then
  git -C "$project_root" fetch --prune origin
else
  gh repo clone Hendra829/NevusQuetta-Browser "$project_root"
fi

git -C "$project_root" switch feat/v09ab
git -C "$project_root" pull --ff-only origin feat/v09ab

cd "$project_root/B-Android"
if [ ! -f local.properties ]; then
  printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
fi

./gradlew --no-daemon clean testDebugUnitTest lintDebug assembleDebug
sha256sum app/build/outputs/apk/debug/app-debug.apk | tee app-debug.apk.sha256
printf 'APK: %s\n' "$PWD/app/build/outputs/apk/debug/app-debug.apk"

