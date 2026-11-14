#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
# Rebuilds app/src/main/jniLibs/<abi>/libdroneswipe.so: FUTO swipe-library + ExecuTorch + native/droneswipe.
#
#   tools/build-swipe-lib.sh [work_dir] [abi...]      default: all four ABIs
#
# Needs: git, cmake >= 3.29, python3 (the swipe-library Makefile creates its own venv with torch
# for ExecuTorch's code generation), GNU sed (macOS: brew install gnu-sed), Android NDK 28.
#
# Build paths never end up in the libraries: every compile maps the work dir, the repo and the NDK
# to neutral names (-ffile-prefix-map covers __FILE__, assert strings and debug info), the result
# is stripped, and the script fails if an absolute path is left in a library.
set -euo pipefail

SWIPE_LIBRARY_URL=https://gitlab.futo.org/keyboard/swipe-library.git
SWIPE_LIBRARY_REV=1b13f2c85d6b347f6ea3fbc4b3aaf01fce42429a
NDK_VERSION=28.0.13004108

ROOT=$(cd "$(dirname "$0")/.." && pwd)
WORK=${1:-$ROOT/build/swipe-native}
shift || true
ABIS=${*:-arm64-v8a armeabi-v7a x86_64 x86}
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
NDK=$SDK/ndk/$NDK_VERSION
case "$(uname -s)" in
  Darwin) HOST=darwin-x86_64; export PATH=/opt/homebrew/opt/gnu-sed/libexec/gnubin:$PATH ;;
  *) HOST=linux-x86_64 ;;
esac
STRIP=$NDK/toolchains/llvm/prebuilt/$HOST/bin/llvm-strip
JOBS=$(getconf _NPROCESSORS_ONLN)

mkdir -p "$WORK"
WORK=$(cd "$WORK" && pwd -P)
if [ ! -d "$WORK/swipe-library" ]; then
  git clone "$SWIPE_LIBRARY_URL" "$WORK/swipe-library"
fi
git -C "$WORK/swipe-library" checkout -q "$SWIPE_LIBRARY_REV"
git -C "$WORK/swipe-library" submodule update --init --recursive

# The swipe-library Makefile creates its venv with `python3` and needs torch 2.10's torchgen, which
# has wheels only up to Python 3.13: put a 3.12 first in PATH (uv provides one if needed).
PY312=$(uv python find 3.12 2>/dev/null || { uv python install 3.12 >&2 && uv python find 3.12; })
mkdir -p "$WORK/py312"
ln -sf "$PY312" "$WORK/py312/python3"
export PATH="$WORK/py312:$PATH"
if [ -x "$WORK/swipe-library/.venv/bin/python3" ] && ! "$WORK/swipe-library/.venv/bin/python3" -c "import torchgen" 2>/dev/null; then
  rm -rf "$WORK/swipe-library/.venv"  # a half-made venv from a failed run
fi

# the more specific prefixes come last (clang uses the last matching map)
PREFIX_MAP="-ffile-prefix-map=$NDK=ndk -ffile-prefix-map=$ROOT=droneboard -ffile-prefix-map=$WORK=swipe-native"
ET_ROOT=third_party/executorch
ANDROID_FLAGS="-DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake -DANDROID_PLATFORM=android-27 -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"

for ABI in $ABIS; do
  # ExecuTorch static libs for this ABI, built with the prefix maps (a marker tells an old,
  # unmapped build apart, which is then rebuilt from scratch)
  ET_OUT="$WORK/swipe-library/$ET_ROOT/cmake-out-$ABI"
  if [ -d "$ET_OUT" ] && [ ! -f "$ET_OUT/.droneboard-prefix-map" ]; then rm -rf "$ET_OUT"; fi
  make -C "$WORK/swipe-library" "$ET_ROOT/cmake-out-$ABI/libexecutorch.a" ANDROID_NDK_HOME="$NDK" JOBS="$JOBS" \
    ANDROID_CMAKE_FLAGS="$ANDROID_FLAGS \"-DCMAKE_C_FLAGS=$PREFIX_MAP\" \"-DCMAKE_CXX_FLAGS=$PREFIX_MAP\" \"-DCMAKE_ASM_FLAGS=$PREFIX_MAP\""
  touch "$ET_OUT/.droneboard-prefix-map"

  cmake -S "$ROOT/native/droneswipe" -B "$WORK/droneswipe-$ABI" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ABI" -DANDROID_PLATFORM=android-27 -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON \
    -DANDROID_STL=c++_static -DCMAKE_BUILD_TYPE=Release \
    "-DCMAKE_C_FLAGS=$PREFIX_MAP" "-DCMAKE_CXX_FLAGS=$PREFIX_MAP" \
    -DSWIPE_LIBRARY_DIR="$WORK/swipe-library"
  cmake --build "$WORK/droneswipe-$ABI" -j"$JOBS" --target droneswipe
  OUT="$ROOT/app/src/main/jniLibs/$ABI/libdroneswipe.so"
  mkdir -p "$(dirname "$OUT")"
  "$STRIP" --strip-all -o "$OUT" "$WORK/droneswipe-$ABI/libdroneswipe.so"
  if strings "$OUT" | grep -E "/Users/|/private/|/home/|/tmp/"; then
    echo "error: $OUT contains absolute build paths (above)" >&2
    exit 1
  fi
  echo "built app/src/main/jniLibs/$ABI/libdroneswipe.so"
done

# the encoder model (FUTO Model Weights License, see assets/swipe/honorable_sturgeon/LICENSE.md);
# FUTO's training run directory is dropped from the metadata, nothing reads it
MODEL_DIR="$WORK/swipe-library/models/honorable_sturgeon"
if [ ! -f "$MODEL_DIR/model_int8.pte" ]; then
  # model weights are in Git LFS upstream; without git-lfs the committed copy in the app stays
  echo "note: $MODEL_DIR/model_int8.pte not present (git lfs pull?); keeping the bundled model" >&2
  exit 0
fi
cp "$MODEL_DIR/model_int8.pte" "$ROOT/app/src/main/assets/swipe/honorable_sturgeon/"
python3 -I -c 'import json, sys
d = json.load(open(sys.argv[1]))
d.pop("run_dir", None)
open(sys.argv[2], "w").write(json.dumps(d, indent=2) + "\n")' \
  "$WORK/swipe-library/models/honorable_sturgeon/metadata.json" "$ROOT/app/src/main/assets/swipe/honorable_sturgeon/metadata.json"
