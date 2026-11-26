# YewDroid

[Español](#español) · [English](#english) · [Dependencias / Dependencies](#dependencias--dependencies)

Repositorio / Repository: https://github.com/iecxiv/yewdroid

## Español

YewDroid es una adaptación de yewtube, descendiente de mps-youtube, con interfaz Android nativa en Kotlin y Jetpack Compose. El motor de comandos Python se ejecuta mediante Chaquopy y la reproducción utiliza Media3/ExoPlayer en lugar de un reproductor externo de escritorio.

Modified version of yewtube, adapted and simplified for native Android use.

### Características

- Cliente nativo con pestañas Buscar, Listas, Historial y Descargas, además de la terminal Compose con colores ANSI y entrada de comandos.
- Los resultados de Buscar se conservan en memoria al pasar a Historial o a una lista: al volver no se repite la búsqueda. Si esa copia se pierde (por ejemplo, tras reiniciarse el proceso), se repite la última búsqueda.
- Listas locales: al abrir una, se muestra dentro de la pestaña Listas, sin cuadro de búsqueda y con flecha para volver. Se pueden quitar elementos de la lista abierta y añadir resultados a una lista existente o nueva; los cambios se guardan al momento.
- Reproducción mediante un servicio multimedia con controles en la notificación y la pantalla de bloqueo. Pasar la app a segundo plano no la detiene; cerrarla desde la lista de recientes detiene la reproducción y quita la notificación.
- Descargas de audio y de vídeo con selector de calidad (yt-dlp), cola y pestaña Descargas. Un servicio en primer plano muestra el progreso con botón de cancelar y avisa al terminar. Los archivos se guardan en `Descargas/YewDroid`.
- La lista de descargas se conserva al cerrar la app (`downloads.json` en el almacenamiento privado) hasta que se quita a mano, con «Quitar terminadas» o con el botón de cada fila. Quitar una fila no borra el archivo guardado. Una descarga en curso al cerrar la app queda como «Interrumpida».
- Conversión de las descargas de audio con el preajuste de codificación elegido en Ajustes (por ejemplo mp3), mediante FFmpeg empaquetado en la app. Por defecto solo se guarda el archivo convertido; el interruptor «Conservar el audio original» guarda también el original. Ver [FFMPEG-INTEGRATION.md](FFMPEG-INTEGRATION.md).
- Ajustes en la interfaz: idioma de la app (Sistema, English o Español; «Sistema» sigue el del dispositivo si está traducido y, si no, usa inglés), permisos, idioma de la terminal, opciones de yewtube (formatos de audio y vídeo, resolución máxima, orden, columnas, mostrar vídeo, repetir, historial), preajustes de codificación con su editor, y cookies de YouTube importables.
- Apertura de enlaces en aplicaciones Android y uso del selector de compartir.
- Adaptadores Python que ajustan el comportamiento de yewtube en tiempo de ejecución; no es un reemplazo independiente de ese proyecto.
- QuickJS-NG integrado para los desafíos JavaScript utilizados por yt-dlp.

Identificador y namespace: `iecxiv.yewdroid`.

### Comandos básicos

```text
/search-term        Buscar vídeos
1                  Reproducir el primer resultado
open nombre        Abrir una playlist local por nombre
open ID            Abrir una playlist local por ID
openwith 1         Abrir el enlace del primer resultado en Android
share 1            Mostrar el selector para compartir su enlace
h openwith         Ayuda de apertura de enlaces
h share            Ayuda para compartir enlaces
h                  Ayuda general
```

`open` se reserva para las playlists locales. `openwith` y `share` utilizan la lista actual de vídeos o playlists de YouTube. Compartir no envía nada automáticamente: la confirmación se realiza en la aplicación elegida.

La interfaz nativa usa además comandos internos, no pensados para escribirse a mano: `pladd NOMBRE N` y `plrm NOMBRE N` (añadir o quitar un elemento de una lista guardada), `plreload NOMBRE`, y `viewstash` y `viewrestore` (guardar y recuperar los resultados de Buscar).

### Requisitos y compilación

La compilación está pensada para un equipo **Linux x86_64** (los scripts de QuickJS y FFmpeg usan la toolchain Linux del NDK).

- Dispositivo Android 8.0 o posterior, API 26 como mínimo.
- Arquitecturas configuradas: `arm64-v8a` y `x86_64`.
- Android Studio y un JDK compatible; la configuración local documentada utiliza JBR 21. El código Java/Kotlin tiene como objetivo Java 17.
- Android SDK Platform 35, Android NDK (Linux) y CMake, instalables desde el SDK Manager.
- Python 3.13 en el equipo de compilación, con las herramientas requeridas por Chaquopy.
- Paquetes del sistema (ver abajo).

#### Dependencias del sistema (Debian / Ubuntu)

```sh
sudo apt update
sudo apt install build-essential pkg-config python3 gnupg git cmake
```

| Paquete | Para qué se usa |
| --- | --- |
| `build-essential` | `gcc`, `make` y binutils (`objdump`, `strip`). El `configure` de FFmpeg compila herramientas auxiliares con el compilador nativo del equipo; sin él falla con `Host compiler lacks C11 support`. |
| `pkg-config` | FFmpeg localiza libmp3lame, libvorbis y libogg a través de pkg-config. |
| `python3` | Ejecuta `tools/prepare-media-sources.py` y los scripts de compilación. |
| `gnupg` | `prepare-media-sources.py` verifica la firma de FFmpeg con `gpg`. |
| `git`, `cmake` | Requisitos de la compilación de QuickJS-NG y del repositorio. |

En otras distribuciones, instala los equivalentes. Antes de compilar FFmpeg, Gradle comprueba `make`, `pkg-config`, `python3`, `gcc` y `objdump` y se detiene con un mensaje que lista los que faltan.

#### Pasos

1. Clona este repositorio y ábrelo en Android Studio.
2. Instala en el SDK Manager la plataforma 35, un NDK de Linux y CMake. Gradle usa el NDK más reciente del SDK; también admite `-PffmpegNdk=...`, `ANDROID_NDK_HOME` o `ANDROID_NDK_ROOT`.
3. Sincroniza Gradle y ejecuta `:app:assembleDebug`, o compila e instala con Android Studio.
4. El paso `preBuild` compila QuickJS-NG mediante `tools/build-quickjs-android.sh` para ambas arquitecturas.
5. `enableFfmpeg=true` está activado en `gradle.properties`, así que `preBuild` también prepara y compila FFmpeg: descarga y verifica las fuentes si faltan (`prepareMediaSources`) y compila FFmpeg para las dos arquitecturas (`buildFfmpeg`). La primera vez tarda bastante; después se reutiliza mientras no se haga `clean`, que borra `app/build/generated`. Para compilar sin FFmpeg, usa `-PenableFfmpeg=false`. Detalles y solución de problemas: [FFMPEG-INTEGRATION.md](FFMPEG-INTEGRATION.md).
6. La APK debug se genera en `app/build/outputs/apk/debug/app-debug.apk` cuando la compilación termina correctamente.

Para comprobar que FFmpeg está en la APK: `unzip -l app/build/outputs/apk/debug/app-debug.apk | grep -E "ffmpeg|ffprobe"`. En la app, Ajustes > Codificación indica si FFmpeg está incluido.

El cambio de una dependencia no sustituye la compilación y validación en un dispositivo. Un error `Failed to resolve 'www.youtube.com'` señala un fallo de resolución DNS; ese mensaje por sí solo no demuestra que el vídeo haya sido eliminado.

### Estado de validación (26 de noviembre de 2026)

Comprobado por el autor en un dispositivo: la compilación de FFmpeg y la conversión de audio al descargar (queda solo el archivo convertido), la vista de lista con quitar y añadir elementos, la recuperación de los resultados de Buscar, la recarga de la lista abierta tras añadir, el selector de idioma de la app y el editor de preajustes traducido.

Sin comprobar: el interruptor «Conservar el audio original» (posterior a esas pruebas), la exportación de audio en Android 8 y 9 (la corrección procede de leer el código, no de una prueba), los siete perfiles de codificación, la alineación de 16 KB de los binarios y la compatibilidad de licencias de una APK concreta. Las notificaciones de descarga, la persistencia de su lista, las opciones de Ajustes y el cierre desde recientes tampoco constan como confirmados.

Limitaciones conocidas: la conversión solo cubre audio, su progreso es indeterminado y, si el preajuste no admite la extensión descargada, el cliente guarda el original sin avisar. Los avisos de áñadido y quitado de las listas dependen del catálogo de traducción de yewtube y pueden salir en inglés. En Android 8 y 9 las descargas no se exportan a la carpeta pública Descargas.

### Licencia

Las contribuciones originales de YewDroid se ofrecen bajo GNU General Public License, versión 3 o, a tu elección, cualquier versión posterior: `GPL-3.0-or-later`. Se mantienen los derechos, licencias y avisos de yewtube, mps-youtube y los demás componentes de terceros.

Texto de GPLv3: https://www.gnu.org/licenses/gpl-3.0.html

La integración se documenta bajo GPLv3 o posterior por su relación con el código GPL de yewtube/mps-youtube. Esto no cambia las licencias originales de las dependencias ni convierte todo el software incluido en código propiedad de YewDroid. No se añade una restricción de uso personal o no comercial.

Antes de redistribuir APKs, hay que incluir el texto completo de GPL y los avisos exigidos por las dependencias realmente empaquetadas, y facilitar el código fuente correspondiente y las instrucciones de compilación según las licencias aplicables. Si la APK incluye FFmpeg, LAME, libogg o libvorbis, también se aplican sus licencias (ver FFMPEG-INTEGRATION.md). El README y los enlaces no sustituyen esos materiales. Su incorporación y la revisión de una APK concreta siguen pendientes.

## English

YewDroid adapts yewtube, a descendant of mps-youtube, with a native Android interface written in Kotlin and Jetpack Compose. The Python command engine runs through Chaquopy, while Media3/ExoPlayer replaces an external desktop player.

Modified version of yewtube, adapted and simplified for native Android use.

### Features

- Native client with Search, Playlists, History and Downloads tabs, plus the Compose terminal with ANSI colors and command input.
- Search results are kept in memory when you switch to History or a playlist, so returning does not repeat the search. If that copy is lost (for example after the process restarts), the last search is repeated.
- Local playlists: opening one shows it inside the Playlists tab, without the search box and with a back arrow. You can remove entries from the open playlist and add results to an existing or new playlist; changes are saved immediately.
- Playback through a media service with notification and lock-screen controls. Moving the app to the background does not stop it; closing it from the recents list stops playback and removes the notification.
- Audio and video downloads with a quality picker (yt-dlp), a queue and a Downloads tab. A foreground service shows progress with a cancel button and announces the result. Files are saved to `Downloads/YewDroid`.
- The download list survives closing the app (`downloads.json` in private storage) until removed manually, with "Clear finished" or each row's button. Removing a row does not delete the saved file. A download that was running when the app closed shows as "Interrupted".
- Audio downloads can be converted with the encoding preset selected in Settings (for example mp3), using the FFmpeg packaged in the app. Only the converted file is saved by default; the "Keep the original audio" switch saves the original too. See [FFMPEG-INTEGRATION.md](FFMPEG-INTEGRATION.md).
- Settings in the interface: app language (System, English or Español; "System" follows the device language when it is translated and otherwise uses English), permissions, terminal language, yewtube options (audio and video format, maximum resolution, order, columns, show video, repeat, history), encoding presets with their editor, and importable YouTube cookies.
- Link opening in Android applications and access to the Android share chooser.
- Python adapters that adjust yewtube at runtime; this is not an independent replacement for that project.
- Embedded QuickJS-NG for the JavaScript challenges used by yt-dlp.

Application ID and namespace: `iecxiv.yewdroid`.

### Basic commands

```text
/search-term        Search for videos
1                  Play the first result
open name          Open a local playlist by name
open ID            Open a local playlist by ID
openwith 1         Open the first result's link in Android
share 1            Show the share chooser for its link
h openwith         Link-opening help
h share            Link-sharing help
h                  General help
```

`open` is reserved for local playlists. `openwith` and `share` use the current video or YouTube playlist list. Sharing never sends anything automatically: confirmation takes place in the selected application.

The native interface also uses internal commands that are not meant to be typed by hand: `pladd NAME N` and `plrm NAME N` (add or remove an entry of a saved playlist), `plreload NAME`, and `viewstash` and `viewrestore` (keep and recover the Search results).

### Requirements and building

Building is designed for a **Linux x86_64** machine (the QuickJS and FFmpeg scripts use the NDK's Linux toolchain).

- Android 8.0 or newer, minimum API 26.
- Configured architectures: `arm64-v8a` and `x86_64`.
- Android Studio and a compatible JDK; the documented local setup uses JBR 21. Java/Kotlin source targets Java 17.
- Android SDK Platform 35, a Linux Android NDK and CMake, installable from the SDK Manager.
- Python 3.13 on the build machine, with the tools required by Chaquopy.
- System packages (see below).

#### System dependencies (Debian / Ubuntu)

```sh
sudo apt update
sudo apt install build-essential pkg-config python3 gnupg git cmake
```

| Package | What it is for |
| --- | --- |
| `build-essential` | `gcc`, `make` and binutils (`objdump`, `strip`). FFmpeg's `configure` builds helper tools with the machine's native compiler; without it, it fails with `Host compiler lacks C11 support`. |
| `pkg-config` | FFmpeg finds libmp3lame, libvorbis and libogg through pkg-config. |
| `python3` | Runs `tools/prepare-media-sources.py` and the build scripts. |
| `gnupg` | `prepare-media-sources.py` verifies FFmpeg's signature with `gpg`. |
| `git`, `cmake` | Requirements of the QuickJS-NG build and the repository. |

On other distributions, install the equivalents. Before building FFmpeg, Gradle checks for `make`, `pkg-config`, `python3`, `gcc` and `objdump` and stops with a message listing any that are missing.

#### Steps

1. Clone this repository and open it in Android Studio.
2. In the SDK Manager, install platform 35, a Linux NDK and CMake. Gradle uses the newest NDK in the SDK; it also accepts `-PffmpegNdk=...`, `ANDROID_NDK_HOME` or `ANDROID_NDK_ROOT`.
3. Sync Gradle and run `:app:assembleDebug`, or build and install from Android Studio.
4. The `preBuild` step builds QuickJS-NG through `tools/build-quickjs-android.sh` for both architectures.
5. `enableFfmpeg=true` is set in `gradle.properties`, so `preBuild` also prepares and builds FFmpeg: it downloads and verifies the sources if missing (`prepareMediaSources`) and builds FFmpeg for both architectures (`buildFfmpeg`). The first build takes a long time; later builds reuse the result until `clean`, which deletes `app/build/generated`. To build without FFmpeg, use `-PenableFfmpeg=false`. Details and troubleshooting: [FFMPEG-INTEGRATION.md](FFMPEG-INTEGRATION.md).
6. A successful debug build produces `app/build/outputs/apk/debug/app-debug.apk`.

To check that FFmpeg is in the APK: `unzip -l app/build/outputs/apk/debug/app-debug.apk | grep -E "ffmpeg|ffprobe"`. In the app, Settings > Encoding says whether FFmpeg is included.

Changing a dependency does not replace building and testing on a device. A `Failed to resolve 'www.youtube.com'` error indicates a DNS resolution failure; that message alone does not establish that a video has been removed.

### Validation status (26 November 2026)

Checked by the author on a device: the FFmpeg build and audio conversion on download (only the converted file remains), the playlist view with removing and adding entries, recovery of the Search results, reloading the open playlist after adding, the app language selector and the translated preset editor.

Not checked: the "Keep the original audio" switch (added after those tests), audio export on Android 8 and 9 (the fix comes from reading the code, not from a test), the seven encoding profiles, the 16 KB alignment of the binaries and license compatibility of a specific APK. Download notifications, persistence of their list, the Settings options and closing from recents are not recorded as confirmed either.

Known limitations: conversion covers audio only, its progress is indeterminate and, if the preset does not accept the downloaded extension, the client keeps the original without a warning. The added and removed messages of playlists depend on yewtube's translation catalogue and may appear in English. On Android 8 and 9, downloads are not exported to the public Downloads folder.

### License

Original YewDroid contributions are offered under the GNU General Public License, version 3 or, at your option, any later version: `GPL-3.0-or-later`. The rights, licenses and notices of yewtube, mps-youtube and other third-party components are retained.

GPLv3 text: https://www.gnu.org/licenses/gpl-3.0.html

The integration is documented under GPLv3 or later because of its relationship with the GPL code from yewtube/mps-youtube. This does not replace the dependencies' original licenses or make all bundled software the property of YewDroid. No personal-use-only or non-commercial restriction is added.

Before redistributing APKs, include the complete GPL text and the notices required by the dependencies actually packaged, and provide the corresponding source code and build instructions as required by the applicable licenses. If the APK includes FFmpeg, LAME, libogg or libvorbis, their licenses also apply (see FFMPEG-INTEGRATION.md). This README and its links are not substitutes for those materials. Adding them and reviewing a specific APK remain pending.

## Dependencias / Dependencies

Resumen de los componentes principales declarados o incorporados por la configuración revisada. No es un inventario completo de una APK.

Summary of the main components declared or incorporated by the reviewed configuration. This is not a complete APK inventory.

| Componente / Component | Versión / Version | Licencia principal / Main license | Fuente / Source |
| --- | --- | --- | --- |
| yewtube / mps-youtube | yewtube sin fijar / unpinned | GPL-3.0-or-later; conservar avisos / retain notices | https://github.com/mps-youtube/yewtube |
| yt-dlp | 2026.11.23 | Unlicense; revisar avisos de código incorporado / review bundled-code notices | https://github.com/yt-dlp/yt-dlp |
| yt-dlp-ejs | 0.8.0 | Unlicense; wheels: MIT e ISC adicionales / additional MIT and ISC | https://pypi.org/project/yt-dlp-ejs/0.8.0/ |
| Chaquopy | 17.2.0 | MIT | https://github.com/chaquo/chaquopy |
| QuickJS-NG | v0.18.0 | MIT | https://github.com/quickjs-ng/quickjs/tree/v0.18.0 |
| Python | 3.13 | PSF-2.0 y avisos incorporados / and incorporated-software notices | https://docs.python.org/3.13/license.html |
| AndroidX Media3 / ExoPlayer | 1.5.1 | Apache-2.0 | https://github.com/androidx/media |
| Kotlin Coroutines Android | 1.9.0 | Apache-2.0 | https://github.com/Kotlin/kotlinx.coroutines |
| FFmpeg (con `enableFfmpeg` / with `enableFfmpeg`) | 9.1 | LGPL-2.1-or-later (`--disable-gpl --disable-nonfree`) | https://ffmpeg.org/releases/ffmpeg-9.1.tar.xz |
| LAME (con `enableFfmpeg` / with `enableFfmpeg`) | 3.100 | LGPL | https://downloads.sourceforge.net/project/lame/lame/3.100/lame-3.100.tar.gz |
| libogg (con `enableFfmpeg` / with `enableFfmpeg`) | 1.3.6 | BSD-style (Xiph); revisar el texto incluido / review the bundled text | https://downloads.xiph.org/releases/ogg/libogg-1.3.6.tar.xz |
| libvorbis (con `enableFfmpeg` / with `enableFfmpeg`) | 1.3.7 | BSD-style (Xiph); revisar el texto incluido / review the bundled text | https://downloads.xiph.org/releases/vorbis/libvorbis-1.3.7.tar.xz |

Compose/Material3, AndroidX Core, Activity y DataStore también se declaran en `app/build.gradle.kts`. La revisión completa debe registrar sus artefactos y licencias exactos, las dependencias transitivas resueltas por Gradle y pip, y las bibliotecas nativas y recursos de la APK. La dependencia `yewtube` no está fijada: su versión efectiva debe registrarse en cada compilación distribuida.

Compose/Material3, AndroidX Core, Activity and DataStore are also declared in `app/build.gradle.kts`. A complete review must record their exact artifacts and licenses, the transitive dependencies resolved by Gradle and pip, and the APK's native libraries and resources. The `yewtube` dependency is unpinned: its effective version must be recorded for each distributed build.

Compatibilidad de licencias / License compatibility: https://www.gnu.org/licenses/license-list.html

## Créditos / Credits

- yewtube: https://github.com/mps-youtube/yewtube
- mps-youtube: https://github.com/np1/mps-youtube
- Autores y colaboradores de todas las dependencias anteriores / Authors and contributors of all dependencies listed above.

YewDroid no está afiliado a YouTube ni a Google. Las licencias del software no conceden derechos sobre los vídeos, el audio ni las marcas de terceros.

YewDroid is not affiliated with YouTube or Google. Software licenses do not grant rights to third-party videos, audio or trademarks.
