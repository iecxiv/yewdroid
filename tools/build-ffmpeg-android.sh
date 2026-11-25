#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
: "${ANDROID_NDK_HOME:?Define ANDROID_NDK_HOME con un NDK instalado}"
: "${FFMPEG_SOURCE:?Define FFMPEG_SOURCE con las fuentes extraidas de FFmpeg}"
: "${LAME_SOURCE:?Define LAME_SOURCE con las fuentes extraidas de LAME}"
FFMPEG_SOURCE="$(cd "$FFMPEG_SOURCE" && pwd)"
LAME_SOURCE="$(cd "$LAME_SOURCE" && pwd)"
TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64"
test -x "$TOOLCHAIN/bin/llvm-ar"
test -x "$FFMPEG_SOURCE/configure"
test -x "$LAME_SOURCE/configure"
for tool in make python3; do command -v "$tool" >/dev/null; done
WORK="$ROOT/build/ffmpeg-android"
OUT="$ROOT/app/build/generated/ffmpeg/jniLibs"
ASSETS="$ROOT/app/build/generated/ffmpeg/assets/licenses"
mkdir -p "$WORK" "$OUT" "$ASSETS"
export PATH="$TOOLCHAIN/bin:$PATH"
for ABI in arm64-v8a x86_64; do
    case "$ABI" in
        arm64-v8a) TARGET=aarch64-linux-android; ARCH=aarch64; CPU=armv8-a ;;
        x86_64) TARGET=x86_64-linux-android; ARCH=x86_64; CPU=x86-64 ;;
    esac
    CC="$TOOLCHAIN/bin/${TARGET}26-clang"
    CXX="$TOOLCHAIN/bin/${TARGET}26-clang++"
    test -x "$CC"
    PREFIX="$WORK/$ABI/prefix"
    LAME_BUILD="$WORK/$ABI/lame"
    FFMPEG_BUILD="$WORK/$ABI/ffmpeg"
    mkdir -p "$PREFIX" "$LAME_BUILD" "$FFMPEG_BUILD"
    (
        cd "$LAME_BUILD"
        CC="$CC" CXX="$CXX" AR=llvm-ar RANLIB=llvm-ranlib NM=llvm-nm \
        CFLAGS="-O2 -fPIC" LDFLAGS="-Wl,-z,max-page-size=16384" \
        "$LAME_SOURCE/configure" --host="$TARGET" --prefix="$PREFIX" \
            --disable-shared --enable-static --disable-frontend
        make -j"${JOBS:-2}"
        make install
    )
    (
        cd "$FFMPEG_BUILD"
        "$FFMPEG_SOURCE/configure" --target-os=android --arch="$ARCH" --cpu="$CPU" \
            --enable-cross-compile --cc="$CC" --cxx="$CXX" --ar=llvm-ar \
            --ranlib=llvm-ranlib --nm=llvm-nm --strip=llvm-strip \
            --sysroot="$TOOLCHAIN/sysroot" --prefix="$PREFIX" \
            --enable-static --disable-shared --enable-pic --enable-jni \
            --disable-gpl --disable-nonfree --enable-libmp3lame \
            --disable-autodetect --disable-network --disable-doc --disable-ffplay \
            --disable-x86asm --extra-cflags="-I$PREFIX/include -fPIE" \
            --extra-ldflags="-L$PREFIX/lib -pie -Wl,-z,max-page-size=16384"
        make -j"${JOBS:-2}"
        test -s ffmpeg
        test -s ffprobe
        grep -q 'CONFIG_LIBMP3LAME 1' config.h
        grep -q 'CONFIG_NONFREE 0' config.h
        grep -q 'CONFIG_GPL 0' config.h
        mkdir -p "$OUT/$ABI"
        install -m 755 ffmpeg "$OUT/$ABI/libffmpeg.so"
        install -m 755 ffprobe "$OUT/$ABI/libffprobe.so"
        cp config.h "$WORK/$ABI/ffmpeg-config.h"
        cp ffbuild/config.mak "$WORK/$ABI/ffmpeg-config.mak"
    )
done
cp "$FFMPEG_SOURCE/COPYING.LGPLv2.1" "$ASSETS/FFmpeg-LGPL-2.1.txt"
cp "$LAME_SOURCE/COPYING" "$ASSETS/LAME-COPYING.txt"
python3 - "$OUT" "$WORK" <<'PY'
import hashlib, json, sys
from pathlib import Path
out, work = map(Path, sys.argv[1:])
files = [{'file': str(p.relative_to(out)), 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()} for p in sorted(out.rglob('*.so'))]
(work / 'binary-manifest.json').write_text(json.dumps({'files': files, 'native_execution_tested': False, 'network_disabled': True}, indent=2) + '\n')
PY
printf '%s\n' 'Binarios generados. Falta verificar ejecucion en Android y conectar Gradle, yt-dlp y encode.'
