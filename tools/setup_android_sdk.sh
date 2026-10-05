#!/usr/bin/env bash
# 在本机（NixOS）安装 Android SDK 组件。sdkmanager 需要 JDK 与可写目录，
# 所以 SDK 装在 ~/android-sdk，不用 nix store（只读）。
set -euo pipefail

export JAVA_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$HOME/.nix-profile/bin/java")")")}"
SDK="$HOME/android-sdk"
SDKMGR="$SDK/cmdline-tools/latest/bin/sdkmanager"

echo "JAVA_HOME=$JAVA_HOME"
java -version

# 坑：用 `python3 -m zipfile -e` 解压 cmdline-tools 会丢失可执行位，
# sdkmanager 报 "Permission denied"（exit 126）。防御性补回。
chmod +x "$SDK/cmdline-tools/latest/bin/"* 2>/dev/null || true

# 预先接受所有许可
yes | "$SDKMGR" --sdk_root="$SDK" --licenses >/dev/null 2>&1 || true

"$SDKMGR" --sdk_root="$SDK" --install \
  "platform-tools" \
  "platforms;android-35" \
  "build-tools;35.0.0" \
  "emulator" \
  "system-images;android-34;google_apis;x86_64" \
  2>&1 | tail -30

echo "=== installed ==="
ls "$SDK"
ls "$SDK/platforms" "$SDK/build-tools" "$SDK/system-images/android-34/google_apis" 2>/dev/null
echo "SETUP_DONE"
