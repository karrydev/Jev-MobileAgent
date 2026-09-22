#!/usr/bin/env bash
set -euo pipefail

script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
sdk_root="${JEV_ANDROID_SDK:-${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}}"

if [[ -z "$sdk_root" ]]; then
    user_home="$(CDPATH= cd -- ~ && pwd)"
    if [[ -d "$user_home/Library/Android/sdk" ]]; then
        sdk_root="$user_home/Library/Android/sdk"
    elif [[ -d "$user_home/Android/Sdk" ]]; then
        sdk_root="$user_home/Android/Sdk"
    fi
fi

if [[ -z "$sdk_root" || ! -d "$sdk_root" ]]; then
    echo "Android SDK not found; set JEV_ANDROID_SDK or ANDROID_SDK_ROOT" >&2
    exit 2
fi
if [[ ! -d "$sdk_root/platforms/android-35" ]]; then
    echo "Android SDK at $sdk_root is missing platforms/android-35" >&2
    exit 2
fi

export ANDROID_SDK_ROOT="$sdk_root"
export ANDROID_HOME="$sdk_root"
exec "$script_dir/gradlew" "$@"
