# Android 模拟器的 FHS 沙箱。
#
# 为什么需要它：Google 预编译的 emulator 二进制动态链接了 libX11/libGL/libasound 等
# 一堆库，而 NixOS 没有 /usr/lib，直接跑只会得到
#   "error while loading shared libraries: libX11.so.6: cannot open shared object file"。
# buildFHSEnv 造一个带 /usr/lib 的沙箱，把这些库放进去。
#
# 用法:
#   nix-build tools/emulator-fhs.nix -o build/emulator-fhs
#   build/emulator-fhs/bin/android-emulator-fhs -c 'emulator -avd att34 ...'
{ pkgs ? import <nixpkgs> { } }:

pkgs.buildFHSEnv {
  name = "android-emulator-fhs";
  targetPkgs = pkgs: with pkgs; [
    xorg.libX11
    xorg.libXext
    xorg.libXrender
    xorg.libXrandr
    xorg.libXfixes
    xorg.libxcb
    xorg.libXi
    xorg.libXtst
    xorg.libXdamage
    xorg.libXcomposite
    libGL
    libGLU
    alsa-lib
    pulseaudio
    zlib
    glib
    dbus
    nspr
    nss
    expat
    freetype
    fontconfig
    stdenv.cc.cc.lib
  ];
  runScript = "bash";
}
