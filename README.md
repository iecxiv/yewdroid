# YewDroid

[English](#english) · [Español](#español) · [Dependencies / Dependencias](#dependencies--dependencias)

Repository / Repositorio: https://github.com/iecxiv/yewdroid

## English

YewDroid adapts yewtube, a descendant of mps-youtube, with a native Android interface written in Kotlin and Jetpack Compose. The Python command engine runs through Chaquopy, while Media3/ExoPlayer replaces an external desktop player.

Modified version of yewtube, adapted and simplified for native Android use.

### Features

- Native client with Search, Playlists, History and Downloads tabs and, optionally, the Compose terminal with ANSI colors and command input (see "Optional terminal"). With the terminal enabled, a label in the header switches between Terminal mode and UI mode.
- Optional terminal: in Settings > Terminal, the "Enable the terminal" switch. On a fresh install it is off; when the app is updated instead of installed fresh it stays on, and the first answer is stored. With the terminal off the app shows only the interface: there is no Terminal/UI label and no arrow to expand the header (the Settings button is always shown), and the terminal language section disappears. The view chosen before is kept for when it is turned back on.
- Search: an order picker (Relevance, Date, Views, Title) that uses yewtube's own `order` setting and repeats the search when changed, and an x inside the text box to empty it.
- "Playback" selector (it replaces the auto-queue switch): "Only this video", "What is on screen" (plays the tapped one and queues the rest of the page) and "YouTube suggestions" (plays the tapped one and, once it sounds, adds to the queue the videos YouTube suggests next). Those who had the old switch on start at "What is on screen"; with the switch off, at "Only this video". The suggestions are requested from YouTube's internal API (InnerTube, `youtubei/v1/next`), without yt-dlp, while the stream of the tapped video is being found; if there are none or the request fails, only the tapped video plays. The `[related]` lines in Logcat (tag `python.stderr`) say what happened.
- Search results are kept in memory when you switch to History or a playlist, so returning does not repeat the search. If that copy is lost (for example after the process restarts), the last search is repeated.
- History: a track is recorded when it starts playing, not when it is queued or when it ends, and without repeats: a track played again moves to the top. Clearing the history refreshes the tab at once. Each entry can be removed on its own with "Remove from history" in its menu.
- Local playlists: opening one shows it inside the Playlists tab, without the search box and with a back arrow. You can remove entries from the open playlist and add results to an existing or new playlist; changes are saved immediately.
- Playback through a media service with notification and lock-screen controls. Moving the app to the background does not stop it; closing it from the recents list stops playback and removes the notification. The artist shown by the notification and Bluetooth is the channel name (`uploaderName`); if missing, the channel yt-dlp reports without the " - Topic" suffix, and if there is none either, "YewDroid".
- Player in UI mode: the playback controls sit below the video; in full screen they are placed above the time bar. The video is shown in the expanded player (the tabs never move) and the mini player shows a small video. Rotating the device to landscape with a video on screen enters full screen, and rotating back to portrait leaves it. When the video moves between the mini player, the expanded player and full screen, the track keeps its position. The video in the expanded player and in the terminal keeps its proportions and never takes more than a third of the screen height, so on small screens or with large font or display sizes the controls and the list stay in view; full screen has no such limit.
- The screen stays on only while a video is playing in the terminal, the expanded player or full screen; not with the video in the mini player, nor when paused, nor with an audio-only track.
- Player queue: shuffle (mixes the tracks after the current one) and repeat (off, the list or the track) buttons. The queue controls are hidden while the video is visible. On next, previous or a tap on an entry, that entry is marked at once, without waiting for the new track to start.
- Changing "Show video" in Settings or with `set` applies to the track that is playing: it restarts at the same position with the other kind of stream.
- When a video has no stream within the maximum resolution (`max_res`), an option (stored in `lowres_mode.txt`) chooses between playing it at the next higher resolution or playing audio only.
- Playback runs in its own thread, the same in the terminal and in the UI, so yewtube keeps accepting commands while something plays. The terminal keeps its prompt and shows the time, shuffle and repeat. The UI re-syncs after commands typed in the terminal and remembers its tab and list when switching modes and back. Downloads started from the terminal appear in the Downloads tab.
- Waiting for the video: while the stream of a video is being found (several seconds, especially the first time) the UI shows "Getting the video…", also on next or previous, a tap in the queue, the automatic advance and the restart after "Show video" changes, and also in the player panels. The notice goes away when the track starts, when playback stops, or after 45 s; it is not shown in Terminal mode. The result tapped in Search, Playlists or History is marked in green until then. Tapping the same result again is ignored, so the track does not restart; tapping another result cancels the pending lookup and loads the new one (the cancellation takes effect at the next network access, so it may wait for the current phase to finish, about 2 s of QuickJS or a preload). Next/previous and a tap in the queue during a load do not cancel the pending lookup. If Android does not grant audio focus because the app is in the background, playback starts when you return to the app. To shorten the wait, yewtube's preloads wait until the tapped track has its stream and the preload of the first result of each list is dropped. `[time]` lines in Logcat (tag `python.stderr`) record the duration of each phase.
- Audio and video downloads with a quality picker (yt-dlp), a queue and a Downloads tab. A foreground service shows progress with a cancel button and announces the result. Files are saved to `Downloads/YewDroid`.
- The download list survives closing the app (`downloads.json` in private storage) until removed manually, with "Clear finished" or each row's button. Removing a row does not delete the saved file. A download that was running when the app closed shows as "Interrupted". With the Settings switch "Clear the downloads list on close", the list is emptied instead: when leaving the main screen with the back button or, if Android ends the process, the next time the app starts. Saved files are not deleted.
- If YouTube asks to sign in, the app shows a clear message, retries with other YouTube clients and lets you import cookies, with a guide shown first (secondary account, private export and risks).
- Audio downloads can be converted with the encoding preset selected in Settings (for example mp3), using the FFmpeg packaged in the app. Only the converted file is saved by default; the "Keep the original audio" switch saves the original too. See [FFMPEG-INTEGRATION.md](FFMPEG-INTEGRATION.md).
- Settings in the interface, grouped into sections: app language (System, English or Español; "System" follows the device language when it is translated and otherwise uses English), startup (the tab shown when the app opens: Search, Playlists or History; and clearing the downloads list on close), terminal (the "Enable the terminal" switch), permissions, terminal language (only with the terminal enabled), yewtube options (audio and video format, maximum resolution, order, columns, show video, repeat, history), encoding presets with their editor, importable YouTube cookies and, at the end, "About". The startup tab applies each time the UI-mode screen is opened, including when coming back from the terminal.
- "About", at the end of Settings, shows the app version (read from the installed app itself) and a link to the repository.
- In the terminal, `set` accepts the options that work on Android (for example `max_res`, `video_format` and `order`); search results are sorted locally by `order`.
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

### Validation status

Checked on a device (26 November 2026): the FFmpeg build and audio conversion on download (only the converted file remains), the playlist view with removing and adding entries, recovery of the Search results, reloading the open playlist after adding, the app language selector and the translated preset editor. Also: the "Getting the video…" notice from the first tap until the track sounds, repeated taps being ignored while it loads, playback starting when you return to the app after Android refused audio focus in the background, and the track keeping its position when the video moves between players. On a test phone, the first tap went from about 12.5 s to about 4.6 s until the stream was ready, measured with the `[time]` lines.

Not checked: the "Keep the original audio" switch (added after those tests), audio export on Android 8 and 9 (the fix comes from reading the code, not from a test), the seven encoding profiles, the 16 KB alignment of the binaries and license compatibility of a specific APK. Download notifications, persistence of their list, the Settings options and closing from recents are not recorded as confirmed either.

Not compiled or tested when this was written: the controls below the video, full screen and rotation, the order picker and the search box x, recording the history when a track starts playing, refreshing the tab when the history is cleared, the startup tab and clearing the downloads list on close; shuffle and repeat, history without repeats, the video in the expanded player, changing "Show video" while a track plays, consistency between the UI and the terminal, terminal downloads in the Downloads tab, the retry with other YouTube clients and cookies import; keeping the screen on only with video in the terminal, the expanded player or full screen; the low-resolution option (`lowres_mode`; the cause of the case that prompted it is a deduction, not verified); the "Getting the video" notice in every wait; the immediate mark on the chosen queue entry; cancelling the pending lookup when another result is tapped; the green mark on the tapped result; removing single entries from the history; and the artist shown by the notification and Bluetooth (not tried in a car).

Also not compiled or tested when this was written: the "Enable the terminal" switch and its default depending on fresh install or update (that the first-install and last-update times tell the two cases apart on phones is an assumption); the header without label or arrow with the terminal off; the "Playback" selector and, within it, the YouTube suggestions (the parsing of the InnerTube response covers two known layouts but has not been checked against a real response, and that the player accepts tracks being added to a queue already playing is also an assumption); the one-third height limit for the video; and the "About" section.

Known limitations: conversion covers audio only, its progress is indeterminate and, if the preset does not accept the downloaded extension, the client keeps the original without a warning. The added and removed messages of playlists depend on yewtube's translation catalogue and may appear in English. On Android 8 and 9, downloads are not exported to the public Downloads folder. Finding the stream of a video takes about 4 to 5 s on the test phone (QuickJS, about 2.3 s, and yt-dlp), and switching to a track whose preload has not finished costs another extraction. The YouTube suggestions depend on an unofficial internal API that can change without notice, and without signing in they are likely to differ from the account's personalized recommendations. The repository link in "About" opens only for people with access, because the repository is private.

### License

Original YewDroid contributions are offered under the GNU General Public License, version 3 or, at your option, any later version: `GPL-3.0-or-later`. The rights, licenses and notices of yewtube, mps-youtube and other third-party components are retained.

GPLv3 text: https://www.gnu.org/licenses/gpl-3.0.html

The integration is documented under GPLv3 or later because of its relationship with the GPL code from yewtube/mps-youtube. This does not replace the dependencies' original licenses or make all bundled software the property of YewDroid. No personal-use-only or non-commercial restriction is added.

Before redistributing APKs, include the complete GPL text and the notices required by the dependencies actually packaged, and provide the corresponding source code and build instructions as required by the applicable licenses. If the APK includes FFmpeg, LAME, libogg or libvorbis, their licenses also apply (see FFMPEG-INTEGRATION.md). This README and its links are not substitutes for those materials. Adding them and reviewing a specific APK remain pending.

## Español

YewDroid es una adaptación de yewtube, descendiente de mps-youtube, con interfaz Android nativa en Kotlin y Jetpack Compose. El motor de comandos Python se ejecuta mediante Chaquopy y la reproducción utiliza Media3/ExoPlayer en lugar de un reproductor externo de escritorio.

Versión modificada de yewtube, adaptada y simplificada para su uso nativo en Android.

### Características

- Cliente nativo con pestañas Buscar, Listas, Historial y Descargas y, de forma opcional, la terminal Compose con colores ANSI y entrada de comandos (ver «Terminal opcional»). Con la terminal activada, una etiqueta de la cabecera cambia entre el modo Terminal y el modo UI.
- Terminal opcional: en Ajustes > Terminal, el interruptor «Activar la terminal». En una instalación nueva viene desactivado; cuando la app se actualiza en lugar de instalarse de cero se queda activado, y la primera respuesta se guarda. Con la terminal desactivada la app muestra solo la interfaz: no hay etiqueta Terminal/UI ni flecha para expandir la cabecera (el botón de Ajustes se muestra siempre) y desaparece la sección de idioma de la terminal. La vista elegida antes se conserva para cuando se vuelva a activar.
- Buscar: selector de orden (Relevancia, Fecha, Vistas, Título), que usa el mismo ajuste `order` de yewtube y repite la búsqueda al cambiarlo, y una x dentro del cuadro de texto para vaciarlo.
- Selector «Reproducción» (sustituye al interruptor de auto-cola): «Solo este vídeo», «Lo que se ve en pantalla» (reproduce el tocado y encola el resto de la página) y «Sugerencias de YouTube» (reproduce el tocado y, cuando suena, añade a la cola los vídeos que YouTube sugiere después). Quien tenía activado el interruptor antiguo empieza en «Lo que se ve en pantalla»; con el interruptor apagado, en «Solo este vídeo». Las sugerencias se piden a la API interna de YouTube (InnerTube, `youtubei/v1/next`), sin yt-dlp, mientras se busca el flujo del vídeo tocado; si no las hay o la petición falla, solo suena el vídeo tocado. Los mensajes `[related]` de Logcat (etiqueta `python.stderr`) indican qué ocurrió.
- Los resultados de Buscar se conservan en memoria al pasar a Historial o a una lista: al volver no se repite la búsqueda. Si esa copia se pierde (por ejemplo, tras reiniciarse el proceso), se repite la última búsqueda.
- Historial: una pista se guarda cuando empieza a sonar, no al ponerla en cola ni al terminar, y sin repetidos: una pista reproducida otra vez sube al principio. Vaciar el historial actualiza la pestaña al momento. Cada entrada se puede quitar por separado con «Quitar del historial» en su menú.
- Listas locales: al abrir una, se muestra dentro de la pestaña Listas, sin cuadro de búsqueda y con flecha para volver. Se pueden quitar elementos de la lista abierta y añadir resultados a una lista existente o nueva; los cambios se guardan al momento.
- Reproducción mediante un servicio multimedia con controles en la notificación y la pantalla de bloqueo. Pasar la app a segundo plano no la detiene; cerrarla desde la lista de recientes detiene la reproducción y quita la notificación. El artista que muestran la notificación y Bluetooth es el nombre del canal (`uploaderName`); si falta, el canal que da yt-dlp sin el sufijo « - Topic», y si tampoco hay, «YewDroid».
- Reproductor en modo UI: los controles de reproducción van bajo el vídeo; en pantalla completa se colocan encima de la barra de tiempo. El vídeo se muestra en el reproductor expandido (las pestañas no se mueven) y el reproductor mini muestra un vídeo pequeño. Al girar el dispositivo a horizontal con un vídeo a la vista, entra en pantalla completa, y al volver a vertical sale. Al mover el vídeo entre el mini, el expandido y la pantalla completa, la pista conserva su posición. El vídeo del reproductor expandido y el de la terminal conservan sus proporciones y nunca ocupan más de un tercio del alto de la pantalla, para que en pantallas pequeñas o con tamaños de letra o de pantalla grandes los controles y la lista sigan a la vista; la pantalla completa no tiene ese límite.
- La pantalla se mantiene encendida solo mientras un vídeo se reproduce en la terminal, en el reproductor expandido o en pantalla completa; no con el vídeo en el reproductor mini, ni en pausa, ni con una pista solo de audio.
- Cola del reproductor: botones de aleatorio (mezcla las pistas siguientes a la actual) y de repetir (desactivado, la lista o la pista). Los controles de la cola se ocultan mientras se ve el vídeo. Al pasar a la pista siguiente o anterior, o al tocar una entrada, esa entrada se marca al momento, sin esperar a que la nueva pista empiece.
- Cambiar «Mostrar vídeo» en Ajustes o con `set` se aplica a la pista que suena: se reinicia en el mismo punto con el otro tipo de flujo.
- Si un vídeo no tiene ningún flujo dentro de la resolución máxima (`max_res`), una opción (guardada en `lowres_mode.txt`) elige entre reproducirlo a la siguiente resolución superior o reproducir solo el audio.
- La reproducción corre en su propio hilo, igual en la terminal que en la UI, así que yewtube sigue aceptando comandos mientras suena algo. La terminal conserva su prompt y muestra el tiempo, el aleatorio y el repetir. La UI se resincroniza tras los comandos escritos en la terminal y recuerda la pestaña y la lista al cambiar de modo y volver. Las descargas lanzadas desde la terminal aparecen en la pestaña Descargas.
- Espera del vídeo: mientras se obtiene el flujo de un vídeo (varios segundos, sobre todo la primera vez) la UI muestra «Obteniendo el vídeo…», también al pasar a la pista siguiente o anterior, al tocar la cola, en el avance automático y en el reinicio por «Mostrar vídeo», y también en los paneles del reproductor. El aviso desaparece cuando la pista empieza, cuando se detiene la reproducción o a los 45 s; en el modo Terminal no se muestra. El resultado tocado en Buscar, Listas o Historial se marca en verde hasta entonces. Tocar otra vez el mismo resultado se ignora, para no reiniciar la pista; tocar otro resultado cancela la búsqueda pendiente y carga el nuevo (la cancelación llega en el siguiente acceso a la red, de modo que puede esperar a que termine la fase en curso, unos 2 s de QuickJS o una precarga). Siguiente/anterior y tocar la cola durante una carga no cancelan la búsqueda pendiente. Si Android no concede el audio porque la app está en segundo plano, la reproducción empieza al volver a la app. Para reducir la espera, las precargas de yewtube esperan a que la pista tocada tenga su flujo y se descarta la del primer resultado de cada lista. Las marcas `[time]` de Logcat (etiqueta `python.stderr`) registran la duración de cada fase.
- Descargas de audio y de vídeo con selector de calidad (yt-dlp), cola y pestaña Descargas. Un servicio en primer plano muestra el progreso con botón de cancelar y avisa al terminar. Los archivos se guardan en `Descargas/YewDroid`.
- La lista de descargas se conserva al cerrar la app (`downloads.json` en el almacenamiento privado) hasta que se quita a mano, con «Quitar terminadas» o con el botón de cada fila. Quitar una fila no borra el archivo guardado. Una descarga en curso al cerrar la app queda como «Interrumpida». Con el interruptor «Borrar la lista de descargas al cerrar» de Ajustes, la lista se vacía en su lugar: al salir de la pantalla principal con el botón atrás, o, si Android cierra el proceso, al volver a arrancar la app. Los archivos guardados no se borran.
- Si YouTube pide iniciar sesión, la app muestra un mensaje claro, reintenta con otros clientes de YouTube y permite importar cookies, con una guía previa (cuenta secundaria, exportación privada y riesgos).
- Conversión de las descargas de audio con el preajuste de codificación elegido en Ajustes (por ejemplo mp3), mediante FFmpeg empaquetado en la app. Por defecto solo se guarda el archivo convertido; el interruptor «Conservar el audio original» guarda también el original. Ver [FFMPEG-INTEGRATION.md](FFMPEG-INTEGRATION.md).
- Ajustes en la interfaz, agrupados por secciones: idioma de la app (Sistema, English o Español; «Sistema» sigue el del dispositivo si está traducido y, si no, usa inglés), inicio (pestaña que se muestra al abrir la app: Buscar, Listas o Historial; y el borrado de la lista de descargas al cerrar), terminal (el interruptor «Activar la terminal»), permisos, idioma de la terminal (solo con la terminal activada), opciones de yewtube (formatos de audio y vídeo, resolución máxima, orden, columnas, mostrar vídeo, repetir, historial), preajustes de codificación con su editor, cookies de YouTube importables y, al final, «Acerca de». La pestaña de inicio se aplica cada vez que se abre la pantalla del modo UI, también al volver desde la terminal.
- «Acerca de», al final de Ajustes, muestra la versión de la app (leída de la propia app instalada) y un enlace al repositorio.
- En la terminal, `set` admite las opciones que funcionan en Android (por ejemplo `max_res`, `video_format` y `order`); la búsqueda se ordena localmente según `order`.
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

### Estado de validación

Comprobado en un dispositivo (26 de noviembre de 2026): la compilación de FFmpeg y la conversión de audio al descargar (queda solo el archivo convertido), la vista de lista con quitar y añadir elementos, la recuperación de los resultados de Buscar, la recarga de la lista abierta tras añadir, el selector de idioma de la app y el editor de preajustes traducido. También: el aviso «Obteniendo el vídeo…» desde el primer toque hasta que suena, que se ignoren los toques repetidos mientras carga, que la reproducción empiece al volver a la app cuando Android negó el audio en segundo plano, y que la pista conserve su posición al mover el vídeo entre reproductores. En un móvil de prueba, el primer toque pasó de unos 12,5 s a unos 4,6 s hasta tener el flujo, medido con las marcas `[time]`.

Sin comprobar: el interruptor «Conservar el audio original» (posterior a esas pruebas), la exportación de audio en Android 8 y 9 (la corrección procede de leer el código, no de una prueba), los siete perfiles de codificación, la alineación de 16 KB de los binarios y la compatibilidad de licencias de una APK concreta. Las notificaciones de descarga, la persistencia de su lista, las opciones de Ajustes y el cierre desde recientes tampoco constan como confirmados.

Sin compilar ni probar al redactar esto: los controles bajo el vídeo, la pantalla completa y la rotación, el selector de orden y la x del cuadro de búsqueda, el registro del historial al empezar a sonar, la actualización de la pestaña al vaciar el historial, la pestaña de inicio y el borrado de la lista de descargas al cerrar; el aleatorio y el repetir, el historial sin repetidos, el vídeo en el reproductor expandido, el cambio de «Mostrar vídeo» con una pista sonando, la coherencia entre la UI y la terminal, las descargas de la terminal en la pestaña Descargas, el reintento con otros clientes de YouTube y la importación de cookies; la pantalla encendida solo con vídeo en la terminal, el reproductor expandido o la pantalla completa; la opción de baja resolución (`lowres_mode`; la causa del caso que la motivó es una deducción, no está verificada); el aviso «Obteniendo el vídeo» en todas las esperas; la marca inmediata de la entrada elegida en la cola; el cancelar la búsqueda pendiente al tocar otro resultado; el resalte en verde del resultado tocado; quitar entradas sueltas del historial; y el artista de la notificación y Bluetooth (sin prueba en un coche).

También sin compilar ni probar al redactar esto: el interruptor «Activar la terminal» y su valor por defecto según sea instalación nueva o actualización (que la fecha de primera instalación y la de última actualización distingan bien los dos casos en los móviles es una suposición); la cabecera sin etiqueta ni flecha con la terminal desactivada; el selector «Reproducción» y, dentro de él, las sugerencias de YouTube (el análisis de la respuesta de InnerTube cubre dos formatos conocidos pero no se ha comprobado con una respuesta real, y que el reproductor admita añadir pistas a la cola en marcha también es una suposición); el límite de un tercio del alto para el vídeo; y la sección «Acerca de».

Limitaciones conocidas: la conversión solo cubre audio, su progreso es indeterminado y, si el preajuste no admite la extensión descargada, el cliente guarda el original sin avisar. Los avisos de añadido y quitado de las listas dependen del catálogo de traducción de yewtube y pueden salir en inglés. En Android 8 y 9 las descargas no se exportan a la carpeta pública Descargas. Obtener el flujo de un vídeo tarda unos 4 o 5 s en el móvil de prueba (QuickJS, unos 2,3 s, y yt-dlp), y cambiar a una pista cuya precarga no ha terminado cuesta otra extracción. Las sugerencias de YouTube dependen de una API interna no oficial que puede cambiar sin aviso, y sin iniciar sesión es probable que no coincidan con las recomendaciones personalizadas de la cuenta. El enlace del repositorio en «Acerca de» solo abre para quien tenga acceso, porque el repositorio es privado.

### Licencia

Las contribuciones originales de YewDroid se ofrecen bajo GNU General Public License, versión 3 o, a tu elección, cualquier versión posterior: `GPL-3.0-or-later`. Se mantienen los derechos, licencias y avisos de yewtube, mps-youtube y los demás componentes de terceros.

Texto de GPLv3: https://www.gnu.org/licenses/gpl-3.0.html

La integración se documenta bajo GPLv3 o posterior por su relación con el código GPL de yewtube/mps-youtube. Esto no cambia las licencias originales de las dependencias ni convierte todo el software incluido en código propiedad de YewDroid. No se añade una restricción de uso personal o no comercial.

Antes de redistribuir APKs, hay que incluir el texto completo de GPL y los avisos exigidos por las dependencias realmente empaquetadas, y facilitar el código fuente correspondiente y las instrucciones de compilación según las licencias aplicables. Si la APK incluye FFmpeg, LAME, libogg o libvorbis, también se aplican sus licencias (ver FFMPEG-INTEGRATION.md). El README y los enlaces no sustituyen esos materiales. Su incorporación y la revisión de una APK concreta siguen pendientes.

## Dependencies / Dependencias

Summary of the main components declared or incorporated by the reviewed configuration. This is not a complete APK inventory.

Resumen de los componentes principales declarados o incorporados por la configuración revisada. No es un inventario completo de una APK.

| Component / Componente | Version / Versión | Main license / Licencia principal | Source / Fuente |
| --- | --- | --- | --- |
| yewtube / mps-youtube | unpinned / sin fijar | GPL-3.0-or-later; retain notices / conservar avisos | https://github.com/mps-youtube/yewtube |
| yt-dlp | 2026.11.23 | Unlicense; review bundled-code notices / revisar avisos de código incorporado | https://github.com/yt-dlp/yt-dlp |
| yt-dlp-ejs | 0.8.0 | Unlicense; wheels: additional MIT and ISC / MIT e ISC adicionales | https://pypi.org/project/yt-dlp-ejs/0.8.0/ |
| Chaquopy | 17.2.0 | MIT | https://github.com/chaquo/chaquopy |
| QuickJS-NG | v0.18.0 | MIT | https://github.com/quickjs-ng/quickjs/tree/v0.18.0 |
| Python | 3.13 | PSF-2.0 and incorporated-software notices / y avisos incorporados | https://docs.python.org/3.13/license.html |
| AndroidX Media3 / ExoPlayer | 1.5.1 | Apache-2.0 | https://github.com/androidx/media |
| Kotlin Coroutines Android | 1.9.0 | Apache-2.0 | https://github.com/Kotlin/kotlinx.coroutines |
| FFmpeg (with `enableFfmpeg` / con `enableFfmpeg`) | 9.1 | LGPL-2.1-or-later (`--disable-gpl --disable-nonfree`) | https://ffmpeg.org/releases/ffmpeg-9.1.tar.xz |
| LAME (with `enableFfmpeg` / con `enableFfmpeg`) | 3.100 | LGPL | https://downloads.sourceforge.net/project/lame/lame/3.100/lame-3.100.tar.gz |
| libogg (with `enableFfmpeg` / con `enableFfmpeg`) | 1.3.6 | BSD-style (Xiph); review the bundled text / revisar el texto incluido | https://downloads.xiph.org/releases/ogg/libogg-1.3.6.tar.xz |
| libvorbis (with `enableFfmpeg` / con `enableFfmpeg`) | 1.3.7 | BSD-style (Xiph); review the bundled text / revisar el texto incluido | https://downloads.xiph.org/releases/vorbis/libvorbis-1.3.7.tar.xz |

Compose/Material3, AndroidX Core, Activity and DataStore are also declared in `app/build.gradle.kts`. A complete review must record their exact artifacts and licenses, the transitive dependencies resolved by Gradle and pip, and the APK's native libraries and resources. The `yewtube` dependency is unpinned: its effective version must be recorded for each distributed build.

Compose/Material3, AndroidX Core, Activity y DataStore también se declaran en `app/build.gradle.kts`. La revisión completa debe registrar sus artefactos y licencias exactos, las dependencias transitivas resueltas por Gradle y pip, y las bibliotecas nativas y recursos de la APK. La dependencia `yewtube` no está fijada: su versión efectiva debe registrarse en cada compilación distribuida.

License compatibility / Compatibilidad de licencias: https://www.gnu.org/licenses/license-list.html

## Credits / Créditos

- yewtube: https://github.com/mps-youtube/yewtube
- mps-youtube: https://github.com/np1/mps-youtube
- Authors and contributors of all dependencies listed above / Autores y colaboradores de todas las dependencias anteriores.

YewDroid is not affiliated with YouTube or Google. Software licenses do not grant rights to third-party videos, audio or trademarks.

YewDroid no está afiliado a YouTube ni a Google. Las licencias del software no conceden derechos sobre los vídeos, el audio ni las marcas de terceros.
