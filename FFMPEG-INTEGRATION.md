# Perfiles originales de yewtube en Android

encoder y encoders muestran la lista original mediante show_encs. set encoder N guarda la seleccion usando la configuracion de yewtube; set encoder default restablece None. El comando simplificado encode [N] se ha retirado. h encode o h encoder muestra ayuda.

Los perfiles se leen de g.encoders, cargados por el inicializador original desde el fichero transcode. Se conservan sus nombres, parametros, bitrates, VBR y extensiones validas. No se sobrescribe un fichero transcode existente. ENCODER ya no se fuerza a cero al cargar la configuracion.

Las descargas de una pista exclusivamente de audio (da o una seleccion de audio en d) aplican el perfil seleccionado si admite su extension. None conserva el archivo original. Un perfil no aplicable conserva el original con aviso. El mejor audio se selecciona antes de convertir, sin reducir calidad por compatibilidad.

Antes de descargar una pista que deba convertirse se comprueba que FFmpeg/ffprobe arrancan y que existe el codificador requerido. Se ejecutan los argumentos del perfil con listas, sin shell, sustituyendo ENCODER_PATH por el ejecutable Android y los marcadores IN/OUT/OUT.EXT por rutas locales. Solo se exporta el resultado terminado. Un error conserva archivos de trabajo. DELETE_ORIGINAL=False conserva el original localmente y muestra su ruta; True lo elimina solo tras exportar correctamente. No se reactiva la configuracion general de escritorio ni Last.fm.

## Compilacion

```sh
export OGG_SOURCE=/ruta/a/libogg-extraido
export VORBIS_SOURCE=/ruta/a/libvorbis-extraido
gradle :app:assembleDebug \
  -PenableFfmpeg=true \
  -PffmpegSource=/ruta/a/ffmpeg-extraido \
  -PlameSource=/ruta/a/lame-extraido \
  -PffmpegNdk=/ruta/al/ndk
```

Se necesitan fuentes con configure, Linux x86_64, NDK, make, Python 3 y pkg-config. El script exige libmp3lame, libvorbis, aac y wmav2 y sus contenedores; sin enable-gpl ni enable-nonfree. Las fuentes todavia deben fijarse y verificarse. Gradle solo incluye los binarios con enableFfmpeg=true. d/dv mantienen la union sin recodificar, con FFmpeg a MKV o con Mp4Muxer a MP4 compatible.

## Pendiente

La sintaxis y la sustitucion de argumentos de los siete perfiles y sus extensiones se han probado con datos simulados. No se han validado la compilacion nativa, las conversiones de los siete perfiles, la carga/persistencia real ni la exportacion en Android. Verificar decodificadores, contenedores, ELF, permisos y alineacion 16 KB. Conservar fuentes completas, configuracion, parches, avisos y mecanismo de sustitucion o reenlace aplicable a LGPL. No se certifica aun equivalencia funcional completa ni cumplimiento completo de una APK.
