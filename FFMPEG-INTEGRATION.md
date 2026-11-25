# Integración de FFmpeg + LAME en YewDroid

La compilación y el empaquetado están conectados opcionalmente a Gradle. La ejecución nativa no está validada; las descargas y encode todavía no usan este backend.

## Activar la compilación

Sin enableFfmpeg la app conserva su compilación actual y no incluye los binarios generados de FFmpeg, aunque existan de una compilación anterior. Para incluirlos, usar una instalación de Gradle compatible o las opciones equivalentes de Android Studio:

```sh
gradle :app:assembleDebug \
  -PenableFfmpeg=true \
  -PffmpegSource=/ruta/a/ffmpeg-extraido \
  -PlameSource=/ruta/a/lame-extraido \
  -PffmpegNdk=/ruta/al/ndk
```

También se admiten FFMPEG_SOURCE, LAME_SOURCE y ANDROID_NDK_HOME, manteniendo -PenableFfmpeg=true. Las rutas relativas se resuelven respecto a la raíz del proyecto. Las fuentes deben contener configure. Seleccionar y registrar versiones y SHA-256 concretos antes de distribuir; esta configuración no descarga ni fija las fuentes.

La tarea buildFfmpeg ejecuta tools/build-ffmpeg-android.sh antes de preBuild. Sin rutas válidas, la compilación activada falla con un mensaje explícito; no se omite FFmpeg silenciosamente. El script requiere host Linux x86_64, make, Python 3 y NDK. Genera ffmpeg/ffprobe para arm64-v8a y x86_64, API 26, con libmp3lame, sin enable-gpl ni enable-nonfree. FFmpeg tiene la red deshabilitada: yt-dlp descargará antes de convertir.

## Empaquetado

Con la opción activada, Gradle añade generated/ffmpeg/jniLibs y generated/ffmpeg/assets y conserva libffmpeg.so/libffprobe.so sin el paso de eliminación de símbolos. La configuración existente usa empaquetado legado. Deben verificarse la extracción efectiva, permisos, dependencias ELF, ejecución y alineación de 16 KB en Android. No se ha compilado ni probado esta conexión.

## Backend Python

app/src/main/python/android_ffmpeg.py busca los ejecutables en nativeLibraryDir. Permite unión sin recodificar y conversión MP3 explícita a 128, 192, 256 o 320 kbps. No sobrescribe archivos y ejecuta listas de argumentos sin shell. Los comandos todavía no lo llaman.

No pasar nativeLibraryDir directamente a ffmpeg_location: libffmpeg.so y libffprobe.so no tienen los nombres ffmpeg y ffprobe esperados por yt-dlp. Hay que adaptar el descubrimiento o usar el backend explícito.

## Pendiente

- Seleccionar y verificar las versiones de fuentes y probar la compilación cruzada.
- Verificar ambos ejecutables en un dispositivo y soporte libmp3lame.
- Conectar la unión al recorrido de descarga, sin forzar MP4 para códecs incompatibles.
- Crear y conectar el menú encode; android_features.py aún bloquea encode y fuerza ENCODER a cero.
- Mantener da como descarga del audio original, sin conversión automática.
- Conservar las fuentes completas, configuración, parches, avisos y mecanismo de sustitución o reenlace aplicable a LGPL. Las copias de COPYING no certifican cumplimiento completo.

No se actualizan las dependencias existentes. La conexión de Gradle no significa que encode o la unión mediante FFmpeg estén habilitados.
