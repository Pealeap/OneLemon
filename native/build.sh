#!/usr/bin/env bash
# Builds the static aarch64 zygote launcher used by OneLemon.
# Requires an Android NDK (ANDROID_NDK_HOME or /opt/android-ndk).
set -euo pipefail
cd "$(dirname "$0")"
NDK="${ANDROID_NDK_HOME:-/opt/android-ndk}"
CC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android21-clang"
[ -x "$CC" ] || { echo "NDK clang not found at $CC" >&2; exit 1; }
"$CC" -static -O2 -s -Wall -Wextra -o ../assets/zygote_exec zygote_launcher.c
echo "Built ../assets/zygote_exec"
file ../assets/zygote_exec
