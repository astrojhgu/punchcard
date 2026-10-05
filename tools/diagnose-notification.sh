#!/usr/bin/env bash
# 诊断「提醒不响」。连着手机跑，一次把所有相关证据抓下来。
#
# 排查顺序对应「通知全链路」的每一环，从最容易被国产 ROM 掐掉的地方开始：
#   权限 -> 渠道 -> 精确闹钟 -> 电池白名单 -> 进程状态 -> 闹钟是否真的排上了 -> 日志
#
# 用法: bash tools/diagnose-notification.sh
set -uo pipefail

cd "$(dirname "$0")/.."
export PATH="$HOME/android-sdk/platform-tools:$PATH"
PKG=com.gujunhua.attendance

if ! adb get-state >/dev/null 2>&1; then
  echo "没有可用设备。先确认 adb devices 能看到手机（且不是 no permissions）。" >&2
  exit 1
fi

hr() { printf '\n===== %s =====\n' "$1"; }

hr "0. 包与进程"
adb shell dumpsys package "$PKG" | grep -E "versionName|firstInstallTime|lastUpdateTime" | head -3
echo "--- 进程 ---"
adb shell ps -A 2>/dev/null | grep "$PKG" || echo "  (app 进程当前不在运行)"
echo "--- 是否被强制停止/禁用 ---"
adb shell dumpsys package "$PKG" | grep -E "stopped=|enabled=|hidden=" | head -3

hr "1. 通知权限（Android 13+ 运行时权限）"
adb shell dumpsys package "$PKG" | grep -i "POST_NOTIFICATIONS" | head -3
echo "--- NotificationManager 层面是否允许 ---"
adb shell cmd notification allow_listener 2>/dev/null | head -1
adb shell dumpsys notification_manager 2>/dev/null | grep -i "$PKG" | head -5

hr "2. 通知渠道是否被关闭（MIUI 会单独关渠道）"
adb shell dumpsys notification_manager 2>/dev/null | grep -A12 "$PKG" | grep -E "channel|importance|banned|Block" | head -12

hr "3. 精确闹钟授权（Android 12+，国产 ROM 还会二次拦截）"
adb shell appops get "$PKG" SCHEDULE_EXACT_ALARM 2>&1 | head -3
adb shell dumpsys alarm 2>/dev/null | grep -i "exact_alarm\|canScheduleExactAlarms" | head -3

hr "4. 电池优化白名单（不在白名单 = Doze 时闹钟会被推迟）"
adb shell dumpsys deviceidle whitelist 2>/dev/null | grep "$PKG" || echo "  不在白名单里"
echo "--- 待机模式状态 ---"
adb shell dumpsys deviceidle 2>/dev/null | grep -E "mState=|mLightState=" | head -3

hr "5. 这个 app 排上的闹钟（关键：看有没有、下一次什么时候）"
adb shell dumpsys alarm 2>/dev/null | grep -B3 -A6 "$PKG" | head -40 || echo "  dumpsys alarm 里完全没有这个包 —— 说明闹钟根本没排上"

hr "6. 最近的通知记录（发出去过没有）"
adb shell dumpsys notification --noredact 2>/dev/null | grep -A4 "$PKG" | head -20 || echo "  当前通知栏里没有它的通知"

hr "7. 最近日志（app 自身的报错/广播记录）"
adb logcat -d -t 400 2>/dev/null | grep -iE "gujunhua|ReminderReceiver|AndroidRuntime|FATAL" | tail -25 || echo "  (无相关日志)"

hr "8. MIUI 特有：自启动与后台限制状态"
adb shell dumpsys appops 2>/dev/null | grep -A2 "$PKG" | grep -iE "RUN_IN_BACKGROUND|RUN_ANY_IN_BACKGROUND|START_FOREGROUND" | head -6

echo
echo "把上面全部输出贴回去，我来判断断在哪一环。"
