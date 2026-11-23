#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [[ -z "$NDK" && -d "$SDK/ndk" ]]; then
    NDK="$(find "$SDK/ndk" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)"
fi
if [[ ! -f "$NDK/build/cmake/android.toolchain.cmake" ]]; then
    echo "Falta Android NDK. Instala NDK (Side by side) en SDK Manager o define ANDROID_NDK_HOME." >&2
    exit 1
fi
CMAKE="${CMAKE_COMMAND:-}"
if [[ -z "$CMAKE" ]]; then
    CMAKE="$(command -v cmake || true)"
fi
if [[ -z "$CMAKE" && -d "$SDK/cmake" ]]; then
    CMAKE="$(find "$SDK/cmake" -mindepth 3 -maxdepth 3 -name cmake -type f | sort -V | tail -n 1)"
fi
if [[ ! -x "$CMAKE" ]]; then
    echo "Falta CMake. Instalalo desde SDK Manager o define CMAKE_COMMAND." >&2
    exit 1
fi
command -v git >/dev/null || { echo "Falta git." >&2; exit 1; }
VERSION="v0.18.0"
WORK="$ROOT/build/quickjs-android"
SOURCE="$WORK/source-$VERSION"
OUT="$ROOT/app/build/generated/quickjs/jniLibs"
mkdir -p "$WORK"
if [[ ! -d "$SOURCE/.git" ]]; then
    if [[ -e "$SOURCE" ]]; then
        echo "Directorio de fuentes incompleto: $SOURCE. Revisalo antes de reintentar." >&2
        exit 1
    fi
    git clone --depth 1 --branch "$VERSION" https://github.com/quickjs-ng/quickjs.git "$SOURCE"
fi
if [[ "$(git -C "$SOURCE" describe --tags --exact-match HEAD)" != "$VERSION" ]]; then
    echo "Las fuentes QuickJS no corresponden a $VERSION." >&2
    exit 1
fi
for ABI in arm64-v8a x86_64; do
    BUILD="$WORK/$ABI"
    "$CMAKE" -S "$SOURCE" -B "$BUILD" \
        -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$ABI" -DANDROID_PLATFORM=android-26 \
        -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF \
        -DQJS_BUILD_EXAMPLES=OFF -DQJS_ENABLE_INSTALL=OFF \
        -DCMAKE_EXE_LINKER_FLAGS=-Wl,-z,max-page-size=16384
    "$CMAKE" --build "$BUILD" --target qjs_exe --parallel 2
    test -s "$BUILD/qjs"
    mkdir -p "$OUT/$ABI"
    cp "$BUILD/qjs" "$OUT/$ABI/libqjs.so.tmp"
    chmod 755 "$OUT/$ABI/libqjs.so.tmp"
    mv "$OUT/$ABI/libqjs.so.tmp" "$OUT/$ABI/libqjs.so"
done
mkdir -p "$ROOT/app/build/generated/quickjs/assets"
cp "$SOURCE/LICENSE" "$ROOT/app/build/generated/quickjs/assets/quickjs-ng-LICENSE.txt"
echo "QuickJS-NG $VERSION compilado para arm64-v8a y x86_64."
