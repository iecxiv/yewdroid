# Integración de FFmpeg + LAME en YewDroid

Gradle admite compilar y empaquetar FFmpeg opcionalmente. d/dv lo usan para unir pistas cuando está disponible; encode [N] ofrece conversión explícita a MP3. La compilación y ejecución Android siguen sin validar.

## Compilar con FFmpeg

```sh
gradle :app:assembleDebug \
  -PenableFfmpeg=true \
  -PffmpegSource=/ruta/a/ffmpeg-extraido \
  -PlameSource=/ruta/a/lame-extraido \
  -PffmpegNdk=/ruta/al/ndk
```

FFMPEG_SOURCE, LAME_SOURCE y ANDROID_NDK_HOME son alternativas a las propiedades de ruta. enableFfmpeg sigue siendo obligatorio. Se requieren fuentes con configure, Linux x86_64, make, Python 3 y NDK. Las fuentes todavía no están fijadas ni verificadas y no se descargan automáticamente.

El script genera arm64-v8a y x86_64, API 26, con libmp3lame, sin enable-gpl ni enable-nonfree y sin red. Gradle conserva libffmpeg.so/libffprobe.so y añade los assets legales. Verificar extracción, permisos, ELF y alineación 16 KB.

## Descargas y conversión

- d muestra formatos y permite seleccionar audio para unir al vídeo.
- dv usa bv*+ba/b sin degradar silenciosamente la calidad. Con FFmpeg une a MKV sin recodificar; sin él mantiene Mp4Muxer para H.264/AAC compatibles.
- da usa ba y conserva el audio original. No convierte a MP3 automáticamente.
- encode o encode N abre el menú Android: selecciona vídeo de la lista actual y bitrate MP3 de 128, 192, 256 o 320 kbps. Enter cancela antes de descargar.
- encode comprueba que ffmpeg/ffprobe arrancan y que libmp3lame está disponible antes de descargar el mejor audio. Convierte y exporta únicamente el MP3 terminado. Un error conserva los archivos de trabajo.
- encode no convierte archivos ya descargados, no admite playlists ni rangos y no configura conversiones automáticas. h encode muestra su ayuda.

El encode de escritorio y ENCODER automático siguen desactivados; se registra un comando Android independiente. Se usa android_ffmpeg.py explícitamente, no ffmpeg_location. No se promete soporte universal de formatos.

## Validación pendiente

Seleccionar fuentes versionadas, compilar, comprobar binarios y códecs en dispositivo, probar d/dv/da/encode, cancelación y exportación. La sintaxis y pruebas simuladas del menú se han comprobado, pero no la compilación Android. Conservar fuentes completas, configuración, parches, avisos y mecanismo de sustitución o reenlace aplicable a LGPL. Las copias de COPYING no certifican cumplimiento completo.

No se actualizan dependencias existentes ni se convierte da automáticamente.
