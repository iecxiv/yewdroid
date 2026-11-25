# Preparación de FFmpeg + LAME para YewDroid

Este kit es una primera etapa de integración, NO una integración terminada ni una APK.

## Contenido

- tools/build-ffmpeg-android.sh: compilación cruzada Linux/NDK para arm64-v8a y x86_64, API 26, FFmpeg CLI y ffprobe, con LAME, sin GPL opcional ni nonfree.
- app/src/main/python/android_ffmpeg.py: adaptador de ejecución por lista de argumentos, unión sin recodificar y conversión MP3 explícita. Todavía no está conectado a los comandos.

## Preparar y compilar

Usar copias revisadas y versionadas de las fuentes FFmpeg y LAME, con configure incluido. No apuntar a un checkout que cambie silenciosamente. Registrar versión, origen y SHA-256 de los paquetes fuente antes de compilar. No se han seleccionado ni descargado aquí esos paquetes.

```sh
export ANDROID_NDK_HOME=/ruta/al/ndk
export FFMPEG_SOURCE=/ruta/a/ffmpeg-extraido
export LAME_SOURCE=/ruta/a/lame-extraido
bash tools/build-ffmpeg-android.sh
```

La compilación usa herramientas del host Linux, make y Python 3. Por diseño FFmpeg no tiene red: yt-dlp debe descargar localmente antes de unir/convertir.

## Conexiones pendientes

1. Añadir a Gradle generated/ffmpeg/jniLibs y generated/ffmpeg/assets, depender de esta tarea en preBuild y conservar los ejecutables empaquetados; comprobar su extracción y permisos.
2. Ejecutar ambos binarios en Android y comprobar soporte libmp3lame, ABI, dependencias ELF y alineación 16 KB. El flag de enlace no es una validación de dispositivo.
3. Conectar la unión al recorrido de descarga, preferiblemente con MKV para evitar forzar MP4 incompatible. Mantener da sin conversión automática.
4. No pasar nativeLibraryDir directamente como ffmpeg_location: los nombres libffmpeg.so/libffprobe.so no son los nombres ffmpeg/ffprobe esperados por yt-dlp. Hay que adaptar explícitamente ese descubrimiento o invocar este backend desde los comandos.
5. Revisar android_features.py: encode y sus ayudas están bloqueados, y ENCODER se fuerza a cero. Crear un menú Android explícito de conversión antes de desbloquearlo. No prometer perfiles de códecs no compilados.
6. Conservar fuentes completas de FFmpeg/LAME, configuración, parches, licencias y mecanismo de sustitución o reenlace aplicable a LGPL. Las copias parciales de COPYING no certifican cumplimiento completo.

La sintaxis del script y del adaptador se ha comprobado. No se ha ejecutado la compilación nativa, integrado Gradle, habilitado encode ni publicado cambios en GitHub.
