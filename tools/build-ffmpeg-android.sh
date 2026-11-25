#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
: "${ANDROID_NDK_HOME:?Define ANDROID_NDK_HOME}"
: "${FFMPEG_SOURCE:?Define FFMPEG_SOURCE}"
: "${LAME_SOURCE:?Define LAME_SOURCE}"
: "${OGG_SOURCE:?Define OGG_SOURCE con las fuentes de libogg}"
: "${VORBIS_SOURCE:?Define VORBIS_SOURCE con las fuentes de libvorbis}"
FFMPEG_SOURCE="$(cd "$FFMPEG_SOURCE" && pwd)"
LAME_SOURCE="$(cd "$LAME_SOURCE" && pwd)"
OGG_SOURCE="$(cd "$OGG_SOURCE" && pwd)"
VORBIS_SOURCE="$(cd "$VORBIS_SOURCE" && pwd)"
for source in "$FFMPEG_SOURCE" "$LAME_SOURCE" "$OGG_SOURCE" "$VORBIS_SOURCE"; do
    test -x "$source/configure" || { echo "Falta configure ejecutable: $source" >&2; exit 1; }
done
TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64"
test -x "$TOOLCHAIN/bin/llvm-ar"
for tool in make python3 pkg-config; do command -v "$tool" >/dev/null; done
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
    FFMPEG_BUILD="$WORK/$ABI/ffmpeg"
    mkdir -p "$PREFIX" "$FFMPEG_BUILD"
    export PKG_CONFIG_LIBDIR="$PREFIX/lib/pkgconfig"
    export PKG_CONFIG_PATH=""
    export PKG_CONFIG_SYSROOT_DIR=""
    build_library() {
        local name="$1" source="$2"
        shift 2
        local build="$WORK/$ABI/$name"
        mkdir -p "$build"
        (
            cd "$build"
            CC="$CC" CXX="$CXX" AR=llvm-ar RANLIB=llvm-ranlib NM=llvm-nm \
            CFLAGS="-O2 -fPIC" CPPFLAGS="-I$PREFIX/include" \
            LDFLAGS="-L$PREFIX/lib -Wl,-z,max-page-size=16384" \
            "$source/configure" --host="$TARGET" --prefix="$PREFIX" \
                --disable-shared --enable-static "$@"
            make -j"${JOBS:-2}"
            make install
        )
    }
    build_library lame "$LAME_SOURCE" --disable-frontend
    build_library ogg "$OGG_SOURCE"
    build_library vorbis "$VORBIS_SOURCE"
    pkg-config --exists ogg vorbis vorbisenc
    (
        cd "$FFMPEG_BUILD"
        "$FFMPEG_SOURCE/configure" --target-os=android --arch="$ARCH" --cpu="$CPU" \
            --enable-cross-compile --cc="$CC" --cxx="$CXX" --ar=llvm-ar \
            --ranlib=llvm-ranlib --nm=llvm-nm --strip=llvm-strip \
            --sysroot="$TOOLCHAIN/sysroot" --prefix="$PREFIX" \
            --pkg-config="$(command -v pkg-config)" --pkg-config-flags=--static \
            --enable-static --disable-shared --enable-pic --enable-jni \
            --disable-gpl --disable-nonfree --enable-libmp3lame --enable-libvorbis \
            --enable-encoder=libmp3lame,libvorbis,aac,wmav2 \
            --enable-muxer=mp3,ogg,ipod,asf,matroska \
            --disable-autodetect --disable-network --disable-doc --disable-ffplay \
            --disable-x86asm --extra-cflags="-I$PREFIX/include -fPIE" \
            --extra-ldflags="-L$PREFIX/lib -pie -Wl,-z,max-page-size=16384"
        make -j"${JOBS:-2}"
        test -s ffmpeg
        test -s ffprobe
        configs=(config.h)
        if [[ -f config_components.h ]]; then configs+=(config_components.h); fi
        for component in LIBMP3LAME LIBVORBIS LIBMP3LAME_ENCODER LIBVORBIS_ENCODER AAC_ENCODER WMAV2_ENCODER MP3_MUXER OGG_MUXER IPOD_MUXER ASF_MUXER MATROSKA_MUXER; do
            grep -q "^#define CONFIG_$component 1" "${configs[@]}" || {
                echo "Falta componente requerido: $component ($ABI)" >&2; exit 1;
            }
        done
        grep -q '^#define CONFIG_NONFREE 0' config.h
        grep -q '^#define CONFIG_GPL 0' config.h
        mkdir -p "$OUT/$ABI"
        install -m 755 ffmpeg "$OUT/$ABI/libffmpeg.so"
        install -m 755 ffprobe "$OUT/$ABI/libffprobe.so"
        cp config.h "$WORK/$ABI/ffmpeg-config.h"
        if [[ -f config_components.h ]]; then cp config_components.h "$WORK/$ABI/ffmpeg-components.h"; fi
        cp ffbuild/config.mak "$WORK/$ABI/ffmpeg-config.mak"
    )
done
cp "$FFMPEG_SOURCE/COPYING.LGPLv2.1" "$ASSETS/FFmpeg-LGPL-2.1.txt"
cp "$LAME_SOURCE/COPYING" "$ASSETS/LAME-COPYING.txt"
cp "$OGG_SOURCE/COPYING" "$ASSETS/libogg-COPYING.txt"
cp "$VORBIS_SOURCE/COPYING" "$ASSETS/libvorbis-COPYING.txt"
python3 - "$OUT" "$WORK" <<'PY'
import hashlib, json, sys
from pathlib import Path
out, work = map(Path, sys.argv[1:])
files = [{'file': str(p.relative_to(out)), 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()} for p in sorted(out.rglob('*.so'))]
(work / 'binary-manifest.json').write_text(json.dumps({'files': files, 'required_encoders': ['libmp3lame', 'libvorbis', 'aac', 'wmav2'], 'native_execution_tested': False, 'network_disabled': True}, indent=2) + '\n')
PY
printf '%s\n' 'Generados con los componentes requeridos. Falta probar cada perfil en Android.'
