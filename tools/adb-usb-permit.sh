#!/usr/bin/env bash
# 给 Android 手机的 USB 节点开权限，让 adb 能用。
#
# 为什么需要这个：
#   本机 seat0 的活跃会话是 gdm-greeter，而 logind 的 uaccess ACL 只加给
#   「seat 上的活跃会话用户」。我们当前这个会话类型是 manager 且不挂在 seat 上，
#   所以永远拿不到 ACL，adb 一直报 "no permissions"（注意不是 unauthorized，
#   那是手机端没点授权弹窗，和这个不是一回事）。
#   nixpkgs 又把 android-udev-rules 移除了（理由写的是"已被 uaccess 取代"），
#   可 uaccess 在这个场景下恰好不管用，于是只能显式 chmod。
#
# 注意：这是**一次性**的。手机重新插拔、或切换 USB 模式后，udev 会重建节点，
# 权限回到 0644，需要再跑一遍。想永久解决见文件末尾。
#
# 用法: sudo bash tools/adb-usb-permit.sh

set -euo pipefail

if [ "$(id -u)" -ne 0 ]; then
  echo "需要 root 权限：sudo bash $0" >&2
  exit 1
fi

# 常见 Android 厂商的 USB VID。
#   18d1 = Google/AOSP（很多国产机切到 adb 模式后用的是这个，本机 Redmi K60 就是）
#   2717 = 小米
ANDROID_VIDS='18d1|2717|04e8|0bb4|12d1|2a70|22b8|1004|05c6|0fce|1ebf|19d2|0930|413c|0489|0955|18d1'

echo "扫描 Android USB 设备..."
found=0
for devdir in /sys/bus/usb/devices/*/; do
  [ -f "$devdir/idVendor" ] || continue
  vid=$(cat "$devdir/idVendor" 2>/dev/null || true)
  [[ "$vid" =~ ^($ANDROID_VIDS)$ ]] || continue

  bus=$(cat "$devdir/busnum" 2>/dev/null || true)
  dev=$(cat "$devdir/devnum" 2>/dev/null || true)
  [ -n "$bus" ] && [ -n "$dev" ] || continue

  node="/dev/bus/usb/$(printf '%03d' "$bus")/$(printf '%03d' "$dev")"
  [ -e "$node" ] || continue

  name="$(cat "${devdir}manufacturer" 2>/dev/null || echo '?') $(cat "${devdir}product" 2>/dev/null || echo '?')"
  echo "  授权 $node  ($vid:$(cat "${devdir}idProduct") $name)"
  chmod 0666 "$node"
  found=1
done

if [ "$found" -eq 0 ]; then
  cat >&2 <<'EOF'
没找到 Android 设备。检查三件事：
  1. 数据线插好了（换根线试试，很多线只能充电不能传数据）
  2. 手机 USB 模式选「传输文件 / MTP」，不要停在「仅充电」
  3. 开发者选项里「USB 调试」开着
EOF
  exit 1
fi

echo
echo "授权完成。回到终端执行下面这条让 adb 重新枚举（不要用 sudo）："
echo "    export PATH=\"\$HOME/android-sdk/platform-tools:\$PATH\"; adb kill-server; adb devices"
echo
cat <<'EOF'
------------------------ 想永久解决 ------------------------
把下面这段加进 /etc/nixos/configuration.nix，然后 sudo nixos-rebuild switch：

  services.udev.extraRules = ''
    # Android adb：显式给权限，不依赖 logind 的 uaccess
    # （本机活跃会话是 gdm-greeter，非 seat 会话拿不到 uaccess ACL）
    SUBSYSTEM=="usb", ATTR{idVendor}=="18d1", MODE="0666"
    SUBSYSTEM=="usb", ATTR{idVendor}=="2717", MODE="0666"
  '';

这样每次插拔都自动生效，不用再跑本脚本。
EOF
