#!/usr/bin/env bash
# 产出 WMV 后台转码要用的两个 .so，放进 jniLibs/arm64-v8a：
#   libv2v_android.so —— v2video 内核（Rust，导出 v2v_transcode / v2v_last_error / v2v_hw_supported）
#   libv2vjni.so      —— JNI 桥（C，dlopen 上面的库转发给 WmvTranscoder）
#
# 用法：
#   ANDROID_NDK_HOME=... V2V_REPO=... tools/build-v2v-so.sh
# 缺省用 ~/Library/Android/sdk/ndk/28.2.13676358 与 ~/Developer/TheYouthfulMiracle/v2video。
set -euo pipefail

NDK=${ANDROID_NDK_HOME:-$HOME/Library/Android/sdk/ndk/28.2.13676358}
V2V=${V2V_REPO:-$HOME/Developer/TheYouthfulMiracle/v2video}
HERE=$(cd "$(dirname "$0")" && pwd)
YUKIHUB=$(cd "$HERE/.." && pwd)
TC=$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin
SYSROOT=$NDK/toolchains/llvm/prebuilt/darwin-x86_64/sysroot
API=26
TRIPLE=aarch64-linux-android
ABI=arm64-v8a
JNILIBS=$YUKIHUB/app/src/main/jniLibs/$ABI

[ -d "$NDK" ] || { echo "找不到 NDK：$NDK"; exit 1; }
[ -d "$V2V" ] || { echo "找不到 v2video 仓库：$V2V"; exit 1; }

echo "== 1. Rust 内核（libv2v_android.so）=="
VARG="CARGO_TARGET_$(echo "$TRIPLE" | tr 'a-z-' 'A-Z_')_LINKER"
(cd "$V2V" && \
  env "CC_${TRIPLE//-/_}=$TC/$TRIPLE$API-clang" \
      "AR_${TRIPLE//-/_}=$TC/llvm-ar" \
      "$VARG=$TC/$TRIPLE$API-clang" \
      cargo build --release --target "$TRIPLE" -p v2v-android)

echo "== 2. JNI 桥（libv2vjni.so）=="
$TC/clang -shared -fPIC -O2 --target="$TRIPLE$API" \
  -I"$SYSROOT/usr/include" \
  "$YUKIHUB/app/src/main/cpp/v2v_bridge.c" \
  -o /tmp/libv2vjni.so -ldl -llog

echo "== 3. 装进 jniLibs =="
mkdir -p "$JNILIBS"
cp "$V2V/target/$TRIPLE/release/libv2v_android.so" "$JNILIBS/"
cp /tmp/libv2vjni.so "$JNILIBS/"
$TC/llvm-strip --strip-unneeded "$JNILIBS/libv2v_android.so" "$JNILIBS/libv2vjni.so"

ls -l "$JNILIBS" | grep v2v
echo "完成。重编 APK 即生效。"
