#!/bin/sh
set -eu

TASK_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=${JEV_REPO_ROOT:-/tmp/jev-v1-worktrees/standalone-recovery}
SDK_ROOT=${JEV_ANDROID_SDK:-${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/Users/liangkairui/Library/Android/sdk}}}
SOURCE_SHA=8d3bcc52aa10c86188f8d4b2b2ab09159e55de8e
EXPECTED_APK_SHA256=9c66e641c1c61e29889703ff423a8a053074767dafe4f4a58dcd2bad1fc84a1a
ANDROID_JAR=$SDK_ROOT/platforms/android-35/android.jar
D8=$SDK_ROOT/build-tools/35.0.0/d8
APP_CLASSES=$REPO_ROOT/android-app/app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes
APP_APK=/tmp/recovery27-evidence/guard-recovery-candidate.apk
FIXTURE_SOURCE=$REPO_ROOT/android-app/app/src/test/resources/com/jev/mobileagent/visual-change-guard
BUILD_DIR=$TASK_DIR/build

actual_source=$(git -C "$REPO_ROOT" rev-parse HEAD)
[ "$actual_source" = "$SOURCE_SHA" ] || {
    printf 'Expected source %s, found %s\n' "$SOURCE_SHA" "$actual_source" >&2
    exit 1
}
[ -f "$ANDROID_JAR" ] && [ -x "$D8" ] && [ -d "$APP_CLASSES" ] || {
    printf 'Missing Android SDK, D8, or compiled production classes\n' >&2
    exit 1
}
actual_apk_sha=$(shasum -a 256 "$APP_APK" | awk '{print $1}')
[ "$actual_apk_sha" = "$EXPECTED_APK_SHA256" ] || {
    printf 'Expected APK SHA-256 %s, found %s\n' "$EXPECTED_APK_SHA256" "$actual_apk_sha" >&2
    exit 1
}

rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/classes" "$BUILD_DIR/dex" "$BUILD_DIR/fixtures"
javac -source 8 -target 8 -Xlint:-options \
    -classpath "$ANDROID_JAR:$APP_CLASSES" \
    -d "$BUILD_DIR/classes" \
    "$TASK_DIR/VisualGuardRuntimeRunner.java"
"$D8" --min-api 26 \
    --lib "$ANDROID_JAR" \
    --classpath "$APP_CLASSES" \
    --output "$BUILD_DIR/dex" \
    "$BUILD_DIR/classes/com/jev/mobileagent/VisualGuardRuntimeRunner.class"
for fixture in "$FIXTURE_SOURCE"/*.json "$FIXTURE_SOURCE"/*.png; do
    cp "$fixture" "$BUILD_DIR/fixtures/"
done

printf 'Source: %s\nAPK SHA-256: %s\nRunner dex: %s\n' \
    "$actual_source" "$actual_apk_sha" "$BUILD_DIR/dex/classes.dex"
shasum -a 256 "$BUILD_DIR/dex/classes.dex" "$BUILD_DIR/fixtures"/*
