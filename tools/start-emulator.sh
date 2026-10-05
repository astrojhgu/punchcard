#!/usr/bin/env bash
# 启动 Android 模拟器（无窗口 + adb 截图验证）。
#
# 前置：nix-build tools/emulator-fhs.nix -o build/emulator-fhs
# 用法：bash tools/start-emulator.sh [avd名]
set -euo pipefail

cd "$(dirname "$0")/.."

export ANDROID_HOME="$HOME/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
# avdmanager 把 AVD 放在 XDG 目录下，而 emulator 只按
# $ANDROID_AVD_HOME -> $ANDROID_SDK_HOME/avd -> $HOME/.android/avd 的顺序找，
# 所以必须显式指过去，否则报 "Unknown AVD name"。
export ANDROID_USER_HOME="${ANDROID_USER_HOME:-$HOME/.config/.android}"
export ANDROID_AVD_HOME="${ANDROID_AVD_HOME:-$ANDROID_USER_HOME/avd}"

AVD="${1:-att34}"
FHS="build/emulator-fhs/bin/android-emulator-fhs"

if [ ! -x "$FHS" ]; then
  echo "缺少 FHS 沙箱，先执行: nix-build tools/emulator-fhs.nix -o build/emulator-fhs" >&2
  exit 1
fi

exec "$FHS" -c "
  export ANDROID_HOME='$ANDROID_HOME'
  export ANDROID_SDK_ROOT='$ANDROID_SDK_ROOT'
  export ANDROID_USER_HOME='$ANDROID_USER_HOME'
  export ANDROID_AVD_HOME='$ANDROID_AVD_HOME'
  export ANDROID_EMULATOR_HOME='$ANDROID_USER_HOME'
  exec '$ANDROID_HOME/emulator/emulator' -avd '$AVD' \
    -no-window -no-audio -no-boot-anim -no-snapshot \
    -gpu swiftshader_indirect -accel on
"
