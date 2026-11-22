#!/usr/bin/env bash
set -euo pipefail

source_dir=$1
bridge=$2
target_dir=$3
output=$4
ndk_version=29.0.14206865
sdk_dir=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
ndk_dir=${VOICE_KB_NDK:-${sdk_dir:+$sdk_dir/ndk/$ndk_version}}

case "$(uname -s)-$(uname -m)" in
    Linux-x86_64) host=linux-x86_64 ;;
    Darwin-*) host=darwin-x86_64 ;;
    *) printf 'Unsupported NDK build host\n' >&2; exit 1 ;;
esac

clang="$ndk_dir/toolchains/llvm/prebuilt/$host/bin/aarch64-linux-android31-clang"
if [[ ! -x "$clang" ]]; then
    printf 'Set VOICE_KB_NDK to the location of Android NDK r29.\n' >&2
    exit 1
fi
mkdir -p "$(dirname "$output")"
CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$clang" cargo build --offline --locked --release \
    --manifest-path "$source_dir/Cargo.toml" --features c-api \
    --target aarch64-linux-android --target-dir "$target_dir"
"$clang" -O2 -shared -fPIC -Wl,-z,max-page-size=16384 -Wl,--strip-all \
    -I"$source_dir/include" "$bridge" \
    "$target_dir/aarch64-linux-android/release/libtinyasr.a" \
    -llog -ldl -lm -o "$output"
