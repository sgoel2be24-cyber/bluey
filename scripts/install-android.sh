#!/bin/zsh
# Builds the Android app and installs it on the phone plugged in over USB (USB debugging on).
# Needs the Android SDK (Android Studio installs it) and JDK 17 or 21.
set -euo pipefail
cd "$(dirname "$0")/../android"

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
[[ -f local.properties ]] || echo "sdk.dir=$SDK" > local.properties

# Gradle 8.9 runs on JDK 17–22. Pick one if the default java is newer.
if [[ -z "${JAVA_HOME:-}" ]]; then
  for v in 21 17; do
    if JH=$(/usr/libexec/java_home -v $v 2>/dev/null); then export JAVA_HOME="$JH"; break; fi
  done
fi

./gradlew assembleRelease
APK=app/build/outputs/apk/release/app-release.apk
echo "Built android/$APK"

ADB="$SDK/platform-tools/adb"
if "$ADB" get-state >/dev/null 2>&1; then
  "$ADB" install -r "$APK"
  sleep 1
  "$ADB" shell am start -W -n dev.googly.bluey/.MainActivity >/dev/null
  echo "Installed and opened Googly Eyes on $("$ADB" shell getprop ro.product.model | tr -d '\r')"
else
  echo "No phone found over USB. Plug it in with USB debugging on and run this again,"
  echo "or copy the APK to the phone and open it there to install."
fi
