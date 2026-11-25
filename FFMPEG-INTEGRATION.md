# Integración de FFmpeg + LAME en YewDroid

Gradle admite compilar y empaquetar FFmpeg opcionalmente. El recorrido de d/dv detecta los ejecutables y los usa para unir pistas. La compilación y ejecución Android siguen sin validar. encode continúa deshabilitado.

## Compilar con FFmpeg

```sh
gradle :app:assembleDebug \
  -PenableFfmpeg=true \
  -PffmpegSource=/ruta/a/ffmpeg-extraido \
  -PlameSource=/ruta/a/lame-extraido \
  -PffmpegNdk=/ruta/al/ndk
```

Se admiten FFMPEG_SOURCE, LAME_SOURCE y ANDROID_NDK_HOME como alternativas a las propiedades de ruta. enableFfmpeg sigue siendo obligatorio para incluir los binarios. Se necesitan fuentes con configure, host Linux x86_64, make, Python 3 y NDK. Las rutas relativas se resuelven desde la raíz del proyecto. Las versiones y hashes de las fuentes siguen pendientes de fijar y verificar; no se descargan automáticamente.

El script genera arm64-v8a y x86_64, API 26, con libmp3lame, sin enable-gpl ni enable-nonfree y sin red. Gradle conserva libffmpeg.so y libffprobe.so y añade los assets legales. Verificar extracción, permisos, ELF y alineación 16 KB en un dispositivo.

## Descargas

- d muestra los formatos y permite elegir audio para unir al vídeo.
- dv mantiene bv*+ba/b sin escoger una calidad inferior por compatibilidad.
- da mantiene ba y no convierte automáticamente a MP3; no necesita arrancar FFmpeg.
- Con FFmpeg, d/dv comprueban -version antes de descargar y unen las dos pistas a MKV con copia de flujos, sin recodificar.
- Sin ejecutables disponibles, mantienen Mp4Muxer para MP4/H.264 + M4A/AAC. Una combinación mejor incompatible falla antes de descargar; no se sustituye silenciosamente.
- Si un binario presente no arranca, la descarga se detiene. El soporte real de formatos depende de la compilación; una unión fallida conserva las pistas y no publica un archivo terminado.

Se invoca android_ffmpeg.py directamente, no ffmpeg_location: los nombres empaquetados no coinciden con ffmpeg/ffprobe. Su función encode_mp3 existe, pero ningún menú o comando la llama todavía.

## Pendiente

Seleccionar fuentes versionadas, validar compilación y ejecución nativa, probar selección/unión/cancelación/exportación en Android y crear el menú encode. Conservar fuentes completas, configuración, parches, avisos y mecanismo de sustitución o reenlace aplicable a LGPL. Esta integración no certifica cumplimiento completo ni soporte universal de formatos.

No se actualizan dependencias existentes ni se convierte da automáticamente. encode sigue bloqueado en android_features.py.
