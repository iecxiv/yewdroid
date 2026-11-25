# YewDroid

[Español](#español) · [English](#english) · [Dependencias / Dependencies](#dependencias--dependencies)

Repositorio / Repository: https://github.com/iecxiv/yewtube-android

## Español

YewDroid es una adaptación de yewtube, descendiente de mps-youtube, con interfaz Android nativa en Kotlin y Jetpack Compose. El motor de comandos Python se ejecuta mediante Chaquopy y la reproducción utiliza Media3/ExoPlayer en lugar de un reproductor externo de escritorio.

Modified version of yewtube, adapted and simplified for native Android use.

### Características

- Terminal Compose con colores ANSI, entrada de comandos y listado de resultados.
- Reproducción mediante un servicio multimedia con controles en la notificación y la pantalla de bloqueo.
- Apertura de enlaces en aplicaciones Android y uso del selector de compartir.
- Adaptadores Python que ajustan el comportamiento de yewtube en tiempo de ejecución; no es un reemplazo independiente de ese proyecto.
- QuickJS-NG integrado para los desafíos JavaScript utilizados por yt-dlp.

Identificador y namespace: `iecxiv.yewdroid`.

### Comandos básicos

```text
/canta con blippi   Buscar vídeos
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

### Requisitos y compilación

- Dispositivo Android 8.0 o posterior, API 26 como mínimo.
- Arquitecturas configuradas: `arm64-v8a` y `x86_64`.
- Android Studio y un JDK compatible; la configuración local documentada utiliza JBR 21. El código Java/Kotlin tiene como objetivo Java 17.
- Android SDK Platform 35, Android NDK, CMake, Git y Bash.
- Python 3.13 en el equipo de compilación, con las herramientas requeridas por Chaquopy.

1. Clona este repositorio y ábrelo en Android Studio.
2. Configura el SDK de Android y el NDK. El script también admite `ANDROID_NDK_HOME` o `ANDROID_NDK_ROOT`.
3. Sincroniza Gradle y ejecuta la tarea `:app:assembleDebug`, o compila e instala con Android Studio.
4. El paso `preBuild` compila QuickJS-NG mediante `tools/build-quickjs-android.sh` para ambas arquitecturas.
5. La APK debug se genera en `app/build/outputs/apk/debug/app-debug.apk` cuando la compilación termina correctamente.

El cambio de una dependencia no sustituye la compilación y validación en un dispositivo. Un error `Failed to resolve 'www.youtube.com'` señala un fallo de resolución DNS; ese mensaje por sí solo no demuestra que el vídeo haya sido eliminado.

### Licencia

Las contribuciones originales de YewDroid se ofrecen bajo GNU General Public License, versión 3 o, a tu elección, cualquier versión posterior: `GPL-3.0-or-later`. Se mantienen los derechos, licencias y avisos de yewtube, mps-youtube y los demás componentes de terceros.

Texto de GPLv3: https://www.gnu.org/licenses/gpl-3.0.html

La integración se documenta bajo GPLv3 o posterior por su relación con el código GPL de yewtube/mps-youtube. Esto no cambia las licencias originales de las dependencias ni convierte todo el software incluido en código propiedad de YewDroid. No se añade una restricción de uso personal o no comercial.

Antes de redistribuir APKs, hay que incluir el texto completo de GPL y los avisos exigidos por las dependencias realmente empaquetadas, y facilitar el código fuente correspondiente y las instrucciones de compilación según las licencias aplicables. El README y los enlaces no sustituyen esos materiales. Su incorporación y la revisión de una APK concreta siguen pendientes.

## English

YewDroid adapts yewtube, a descendant of mps-youtube, with a native Android interface written in Kotlin and Jetpack Compose. The Python command engine runs through Chaquopy, while Media3/ExoPlayer replaces an external desktop player.

Modified version of yewtube, adapted and simplified for native Android use.

### Features

- Compose terminal with ANSI colors, command input and result lists.
- Playback through a media service with notification and lock-screen controls.
- Link opening in Android applications and access to the Android share chooser.
- Python adapters that adjust yewtube at runtime; this is not an independent replacement for that project.
- Embedded QuickJS-NG for the JavaScript challenges used by yt-dlp.

Application ID and namespace: `iecxiv.yewdroid`.

### Basic commands

```text
/canta con blippi   Search for videos
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

### Requirements and building

- Android 8.0 or newer, minimum API 26.
- Configured architectures: `arm64-v8a` and `x86_64`.
- Android Studio and a compatible JDK; the documented local setup uses JBR 21. Java/Kotlin source targets Java 17.
- Android SDK Platform 35, Android NDK, CMake, Git and Bash.
- Python 3.13 on the build machine, with the tools required by Chaquopy.

1. Clone this repository and open it in Android Studio.
2. Configure the Android SDK and NDK. The script also accepts `ANDROID_NDK_HOME` or `ANDROID_NDK_ROOT`.
3. Sync Gradle and run `:app:assembleDebug`, or build and install from Android Studio.
4. The `preBuild` step builds QuickJS-NG through `tools/build-quickjs-android.sh` for both architectures.
5. A successful debug build produces `app/build/outputs/apk/debug/app-debug.apk`.

Changing a dependency does not replace building and testing on a device. A `Failed to resolve 'www.youtube.com'` error indicates a DNS resolution failure; that message alone does not establish that a video has been removed.

### License

Original YewDroid contributions are offered under the GNU General Public License, version 3 or, at your option, any later version: `GPL-3.0-or-later`. The rights, licenses and notices of yewtube, mps-youtube and other third-party components are retained.

GPLv3 text: https://www.gnu.org/licenses/gpl-3.0.html

The integration is documented under GPLv3 or later because of its relationship with the GPL code from yewtube/mps-youtube. This does not replace the dependencies' original licenses or make all bundled software the property of YewDroid. No personal-use-only or non-commercial restriction is added.

Before redistributing APKs, include the complete GPL text and the notices required by the dependencies actually packaged, and provide the corresponding source code and build instructions as required by the applicable licenses. This README and its links are not substitutes for those materials. Adding them and reviewing a specific APK remain pending.

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

Compose/Material3, AndroidX Core, Activity y DataStore también se declaran en `app/build.gradle.kts`. La revisión completa debe registrar sus artefactos y licencias exactos, las dependencias transitivas resueltas por Gradle y pip, y las bibliotecas nativas y recursos de la APK. La dependencia `yewtube` no está fijada: su versión efectiva debe registrarse en cada compilación distribuida.

Compose/Material3, AndroidX Core, Activity and DataStore are also declared in `app/build.gradle.kts`. A complete review must record their exact artifacts and licenses, the transitive dependencies resolved by Gradle and pip, and the APK's native libraries and resources. The `yewtube` dependency is unpinned: its effective version must be recorded for each distributed build.

Compatibilidad de licencias / License compatibility: https://www.gnu.org/licenses/license-list.html

## Créditos / Credits

- yewtube: https://github.com/mps-youtube/yewtube
- mps-youtube: https://github.com/np1/mps-youtube
- Autores y colaboradores de todas las dependencias anteriores / Authors and contributors of all dependencies listed above.

YewDroid no está afiliado a YouTube ni a Google. Las licencias del software no conceden derechos sobre los vídeos, el audio ni las marcas de terceros.

YewDroid is not affiliated with YouTube or Google. Software licenses do not grant rights to third-party videos, audio or trademarks.
