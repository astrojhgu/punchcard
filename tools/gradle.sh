#!/usr/bin/env bash
# 本机 Gradle 包装：NixOS 上 JDK 来自 nix profile，Android SDK 在 ~/android-sdk。
# 用法: tools/gradle.sh :app:assembleDebug
set -euo pipefail

if [ -z "${JAVA_HOME:-}" ]; then
  JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$HOME/.nix-profile/bin/java")")")"
  export JAVA_HOME
fi
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

exec "$HOME/android-tools/gradle-8.11.1/bin/gradle" "$@"
