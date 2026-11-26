# Integración de FFmpeg en YewDroid

Este documento describe cómo se compila FFmpeg para la app, cómo se usa desde ella y cómo resolver los fallos más comunes. La visión general del proyecto está en [README.md](README.md).

## Qué se compila

`tools/build-ffmpeg-android.sh` compila, para `arm64-v8a` y `x86_64`, estas bibliotecas estáticas y los ejecutables `ffmpeg` y `ffprobe`:

- FFmpeg 9.1, con `--disable-gpl --disable-nonfree` (LGPL).
- LAME 3.100 (`libmp3lame`), libogg 1.3.6 y libvorbis 1.3.7 (`libvorbis`).
- Codificadores habilitados: `libmp3lame`, `libvorbis`, `aac`, `wmav2`. Contenedores: `mp3`, `ogg`, `ipod`, `asf`, `matroska`.

Los ejecutables se instalan como `app/build/generated/ffmpeg/jniLibs/<abi>/libffmpeg.so` y `libffprobe.so`. Se empaquetan con `useLegacyPackaging = true`, de modo que quedan extraídos en el directorio de bibliotecas nativas de la app. `android_ffmpeg.binaries()` los ejecuta desde allí y no copia ejecutables a almacenamiento escribible.

## Dependencias del sistema (apt)

En Debian o Ubuntu:

```sh
sudo apt update
sudo apt install build-essential pkg-config python3 gnupg git cmake
```

| Paquete | Motivo |
| --- | --- |
| `build-essential` | Aporta `gcc`, `make` y binutils (`objdump`, `strip`). El `configure` de FFmpeg compila herramientas auxiliares con el compilador nativo; sin él se detiene con `Host compiler lacks C11 support`. |
| `pkg-config` | FFmpeg localiza LAME, libogg y libvorbis con `pkg-config --static`. |
| `python3` | Ejecuta `prepare-media-sources.py` y el resumen final del script de compilación. |
| `gnupg` | `prepare-media-sources.py` verifica la firma de FFmpeg con `gpg`. |
| `git`, `cmake` | Requisitos del repositorio y de la compilación de QuickJS-NG. |

Hace falta además un NDK de Linux x86_64 (SDK Manager > SDK Tools > NDK). Antes de compilar FFmpeg, Gradle comprueba `make`, `pkg-config`, `python3`, `gcc` y `objdump`, y se detiene con el comando de instalación si falta alguno. Para la preparación de fuentes comprueba `python3` y `gpg`.

## Compilación automática (la opción por defecto)

`gradle.properties` contiene `enableFfmpeg=true`. Con eso, `preBuild` ejecuta dos tareas de `app/build.gradle.kts`:

1. `prepareMediaSources` ejecuta `tools/prepare-media-sources.py` solo si no existe `build/media-sources/verified`, no se han indicado fuentes propias y FFmpeg no está ya compilado.
2. `buildFfmpeg` ejecuta `tools/build-ffmpeg-android.sh` solo si faltan los `.so` de alguna arquitectura.

Desde Android Studio basta con compilar o ejecutar. La primera vez tarda bastante, y Android Studio no muestra el progreso detallado. Después, mientras existan los `.so`, se empaquetan en cada compilación sin recompilar. `clean` borra `app/build/generated` y obliga a repetir la compilación de FFmpeg. Para forzarla sin `clean`, borra `app/build/generated/ffmpeg`.

Para compilar la app sin FFmpeg, usa `-PenableFfmpeg=false`. Si ya hay binarios en `app/build/generated/ffmpeg/jniLibs`, se empaquetan aunque la propiedad sea `false`.

### Propiedades y variables

| Propiedad Gradle | Variable de entorno | Valor por defecto |
| --- | --- | --- |
| `enableFfmpeg` | — | `true` en `gradle.properties` |
| `ffmpegNdk` | `ANDROID_NDK_HOME` | NDK de Linux más reciente de `<SDK>/ndk` |
| `ffmpegSource` | `FFMPEG_SOURCE` | `build/media-sources/verified/ffmpeg-9.1` |
| `lameSource` | `LAME_SOURCE` | `build/media-sources/verified/lame-3.100` |
| `oggSource` | `OGG_SOURCE` | `build/media-sources/verified/libogg-1.3.6` |
| `vorbisSource` | `VORBIS_SOURCE` | `build/media-sources/verified/libvorbis-1.3.7` |

Si defines `ffmpegSource` o `FFMPEG_SOURCE`, Gradle no ejecuta `prepareMediaSources`: tú aportas las cuatro fuentes.

## Compilación manual (opcional)

Equivale a lo que hace Gradle, paso a paso:

```sh
python3 tools/prepare-media-sources.py
source build/media-sources/verified/sources.env
export ANDROID_NDK_HOME=/ruta/al/ndk
JOBS=4 ./gradlew :app:assembleDebug
```

`prepare-media-sources.py` descarga solo las versiones fijadas, exige hashes correctos, comprueba la firma FFmpeg, extrae con controles de rutas y genera `FFMPEG_SOURCE`, `LAME_SOURCE`, `OGG_SOURCE` y `VORBIS_SOURCE`. Conserva los paquetes originales, la firma, la clave pública y el archivo de bloqueo en `build/media-sources/verified`. No sobrescribe un directorio `verified` existente: conservarlo antes de repetir, o borrarlo para empezar de cero. No compila por sí mismo.

## Cómo se usa en la app

- **Ajustes > Codificación** muestra si FFmpeg está incluido en la compilación, lista los preajustes de yewtube con selección única («None» conserva el original), abre el editor de preajustes y permite restaurar los valores por defecto. La selección es la misma que `set encoder N` en la terminal.
- **Descargas del cliente (solo audio):** al encolar una descarga se guarda el preajuste seleccionado en ese momento. Antes de descargar se valida con `android_encode.validate` que FFmpeg y el codificador existen; si falta algo, la fila aparece al instante con el error. La conversión es una etapa («Convirtiendo») entre la descarga y el guardado, con barra indeterminada porque FFmpeg no informa del progreso. Se puede cancelar durante la conversión. El archivo final conserva el nombre original con la extensión del preajuste.
- Si el preajuste no admite la extensión descargada (campo `valid` del preajuste), el cliente guarda el original sin avisar.
- **Terminal:** las descargas de audio con `da` aplican el perfil si admite la extensión; un perfil no aplicable conserva el original con aviso. `DELETE_ORIGINAL=False` conserva la entrada localmente, mientras que `True` permite eliminarla tras exportar correctamente. `encode [N]` se ha retirado; `h encoder` o `h encode` muestran la ayuda. `d` y `dv` unen sin recodificar.
- El vídeo no se convierte.

Los perfiles y parámetros se cargan del fichero `transcode` original de yewtube sin sobrescribirlo. Editar un preajuste no instala codificadores: solo funcionan los que tiene la compilación de FFmpeg (`libmp3lame`, `libvorbis`, `aac`, `wmav2`).

## Solución de problemas

| Síntoma | Causa y solución |
| --- | --- |
| Ajustes dice «FFmpeg no está incluido en esta compilación» | La APK no lleva `libffmpeg.so` ni `libffprobe.so`. Comprueba `ls -R app/build/generated/ffmpeg/jniLibs` y `unzip -l app/build/outputs/apk/debug/app-debug.apk \| grep -E "ffmpeg\|ffprobe"`. Si faltan, FFmpeg no llegó a compilarse: revisa el error de la tarea `buildFfmpeg`. |
| `Missing tools: ...` al compilar | Faltan herramientas del sistema. Instala con `sudo apt install build-essential pkg-config python3 gnupg`. |
| `Host compiler lacks C11 support` en `buildFfmpeg` | No hay compilador nativo (`gcc`). Instala `build-essential` y repite. |
| `No Linux NDK found` | Instala un NDK desde SDK Manager o define `-PffmpegNdk=/ruta`. Debe ser de Linux x86_64. |
| `... has no configure script` | La carpeta de fuentes indicada no es válida. Borra `build/media-sources` para que Gradle las descargue de nuevo, o corrige la propiedad. |
| `Verified sources already exist` | `prepare-media-sources.py` no sobrescribe. Conserva o borra `build/media-sources/verified`. |
| `Required encoder is missing: ...` al descargar | El preajuste usa un codificador que la compilación no incluye. Elige otro preajuste o amplía la configuración en `build-ffmpeg-android.sh`. |
| La compilación de FFmpeg falla sin mensaje claro | `ffbuild/config.log` dentro de `build/ffmpeg-android/<abi>/ffmpeg` explica el motivo exacto del `configure`. |

## Fuentes multimedia fijadas

- FFmpeg 9.1: https://ffmpeg.org/releases/ffmpeg-9.1.tar.xz
- LAME 3.100: https://downloads.sourceforge.net/project/lame/lame/3.100/lame-3.100.tar.gz
- libogg 1.3.6 y libvorbis 1.3.7: paquetes oficiales de downloads.xiph.org.

Los SHA-256 están fijados en `tools/media-sources.lock.json`. Para Xiph proceden de su página de descargas; el hash FFmpeg coincide con los índices de Fossies y Temno, y el de LAME con el inventario de Suprema y la copia de SourceForge. Además, el preparador verifica la firma de FFmpeg con la huella oficial FCF986EA15E6E293A5644F10B4322F04D67658D8. Es una selección inicial de fuentes, no una actualización automática.

## Distribución y validación pendiente

Los archivos bajo `build/` no se publican automáticamente en GitHub. Incluir los paquetes fuente exactos, los scripts, configuración, parches y avisos con cada distribución que los utilice, además del mecanismo de sustitución o reenlace aplicable a LGPL. Las copias de `COPYING` no certifican cumplimiento completo.

Estado a 26 de noviembre de 2026: la primera compilación automática desde Android Studio compiló LAME, libogg y libvorbis para `arm64-v8a` y se detuvo en el `configure` de FFmpeg por falta de compilador nativo (`Host compiler lacks C11 support`). La compilación cruzada completa de FFmpeg para ambas arquitecturas no se ha completado ni verificado. No se han ejecutado la conversión real en un dispositivo ni los siete perfiles en Android. Verificar persistencia, cancelación, exportación, codificadores y decodificadores, ELF y alineación de 16 KB. No se certifica aún una APK completamente funcional equivalente al original.
