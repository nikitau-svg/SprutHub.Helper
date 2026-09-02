#!/usr/bin/env bash
set -euo pipefail

manifest="${1:-app/build/intermediates/merged_manifests/screenshot/processScreenshotManifest/AndroidManifest.xml}"
apk="${2:-app/build/outputs/apk/screenshot/app-screenshot.apk}"

test -f "$manifest"
test -f "$apk"

grep -Eq 'package="io\.github\.nikitau\.spruthubhelper\.screenshots"' "$manifest"
grep -Eq 'android:name="io\.github\.nikitau\.spruthubhelper\.ScreenshotApplication"' "$manifest"

portrait_activities="$(grep -Ec 'android:screenOrientation="portrait"' "$manifest")"
if (( portrait_activities < 5 )); then
  echo "Screenshot APK не зафиксировал портрет для всех проверяемых экранов" >&2
  exit 1
fi

forbidden='android\.permission\.(INTERNET|ACCESS_NETWORK_STATE|CHANGE_NETWORK_STATE|ACCESS_COARSE_LOCATION|ACCESS_FINE_LOCATION|ACCESS_BACKGROUND_LOCATION|health\.READ_)'
if grep -Eq "$forbidden" "$manifest"; then
  echo "Screenshot APK содержит запрещённое сетевое, географическое или health-разрешение" >&2
  exit 1
fi

receiver_state="$(awk '
  /io\.github\.nikitau\.spruthubhelper\.presence\.PresenceBootReceiver/ { receiver = 1 }
  receiver && /android:enabled=/ { print; exit }
' "$manifest")"
if [[ "$receiver_state" != *'android:enabled="false"'* ]]; then
  echo "Screenshot APK может запускать фоновое восстановление после установки" >&2
  exit 1
fi

echo "Screenshot APK изолирован: отдельный package, портрет, без сети, геопозиции, Health Connect и boot-bootstrap"
