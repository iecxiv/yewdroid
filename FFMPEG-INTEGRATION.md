# Perfiles originales de yewtube en Android

encoder/encoders muestran la lista original. set encoder N guarda la seleccion y set encoder default restablece None. encode [N] se ha retirado. h encoder o h encode muestra ayuda. Los perfiles y parametros se cargan del fichero transcode original sin sobrescribirlo.

Las descargas de una pista de audio aplican el perfil si admite su extension. None conserva el original; un perfil no aplicable lo conserva con aviso. DELETE_ORIGINAL=False conserva la entrada localmente, mientras que True permite eliminarla tras exportar correctamente. Los comandos de escritorio no relacionados permanecen desactivados.

## Fuentes multimedia fijadas

- FFmpeg 9.1: https://ffmpeg.org/releases/ffmpeg-9.1.tar.xz
- LAME 3.100: https://downloads.sourceforge.net/project/lame/lame/3.100/lame-3.100.tar.gz
- libogg 1.3.6 y libvorbis 1.3.7: paquetes oficiales de downloads.xiph.org.

Los SHA-256 estan fijados en tools/media-sources.lock.json. Para Xiph proceden de su pagina de descargas; el hash FFmpeg coincide con los indices de Fossies y Temno, y el de LAME con el inventario de Suprema y la copia de SourceForge. Ademas, el preparador verifica la firma de FFmpeg con la huella oficial FCF986EA15E6E293A5644F10B4322F04D67658D8. Es una seleccion inicial de fuentes, no una actualizacion automatica ni una compilacion nativa ya verificada.

## Preparar y compilar

Con Python 3.13 y GnuPG en el equipo Linux:

```sh
python3 tools/prepare-media-sources.py
source build/media-sources/verified/sources.env
export ANDROID_NDK_HOME=/ruta/al/ndk
gradle :app:assembleDebug -PenableFfmpeg=true
```

El preparador descarga solo las versiones fijadas, exige hashes correctos, comprueba la firma FFmpeg, extrae con controles de rutas y genera FFMPEG_SOURCE, LAME_SOURCE, OGG_SOURCE y VORBIS_SOURCE. Conserva los paquetes originales, la firma, la clave publica y el archivo de bloqueo en build/media-sources/verified. No sobrescribe un directorio verified existente: conservarlo antes de repetir. No compila ni publica por si mismo.

Gradle admite tambien ffmpegSource, lameSource y ffmpegNdk. enableFfmpeg=true es obligatorio para incluir los ejecutables. Se necesitan NDK Linux x86_64, make y pkg-config. El script exige libmp3lame, libvorbis, aac y wmav2 y los contenedores requeridos, sin enable-gpl ni enable-nonfree. d/dv unen sin recodificar; las descargas de audio convierten segun el perfil seleccionado.

## Distribucion y validacion pendiente

Los archivos bajo build/ no se publican automaticamente en GitHub. Incluir los paquetes fuente exactos, los scripts, configuracion, parches y avisos con cada distribucion que los utilice, ademas del mecanismo de sustitucion o reenlace aplicable a LGPL. Las copias de COPYING no certifican cumplimiento completo.

Se han comprobado sintaxis y casos simulados de extraccion segura. No se han ejecutado las descargas reales, la verificacion GPG, la compilacion cruzada ni los siete perfiles en Android. Verificar persistencia, cancelacion, exportacion, codificadores/decodificadores, ELF y alineacion 16 KB. No se certifica aun una APK completamente funcional equivalente al original.
