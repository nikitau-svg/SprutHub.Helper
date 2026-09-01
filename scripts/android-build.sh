#!/bin/sh
set -eu

project_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
toolchain_root=${SPRUTHUB_ANDROID_TOOLCHAIN_ROOT:-"$HOME/.local/share/spruthub-android-toolchain"}

if [ -z "${JAVA_HOME:-}" ] && [ -x "$toolchain_root/temurin-17/Contents/Home/bin/java" ]; then
    export JAVA_HOME="$toolchain_root/temurin-17/Contents/Home"
fi

if [ -z "${ANDROID_HOME:-}" ] && [ -d "$toolchain_root/android-sdk" ]; then
    export ANDROID_HOME="$toolchain_root/android-sdk"
fi

if [ -n "${ANDROID_HOME:-}" ] && [ -z "${ANDROID_SDK_ROOT:-}" ]; then
    export ANDROID_SDK_ROOT="$ANDROID_HOME"
fi

export GRADLE_USER_HOME=${GRADLE_USER_HOME:-"$project_root/.gradle/user-home"}
android_preferences=${ANDROID_USER_HOME:-${ANDROID_SDK_HOME:-"$project_root/.android"}}
# AGP 8.9's metrics component still ignores ANDROID_USER_HOME. Feed the same
# workspace-local directory through its legacy variable, but never set both:
# AGP treats two preference-location variables as a configuration error.
unset ANDROID_USER_HOME
export ANDROID_SDK_HOME="$android_preferences"

mkdir -p "$GRADLE_USER_HOME" "$android_preferences"

if [ -x "$toolchain_root/gradle-8.11.1/bin/gradle" ]; then
    gradle_command="$toolchain_root/gradle-8.11.1/bin/gradle"
else
    gradle_command="$project_root/gradlew"
fi

exec "$gradle_command" --no-daemon --no-watch-fs "$@"
