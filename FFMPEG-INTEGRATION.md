# Integración de FFmpeg en YewDroid

## Estado

Gradle permite compilar y empaquetar FFmpeg opcionalmente; d/dv usan el backend para unir pistas si está incluido. El script exige ahora los componentes de los siete perfiles originales de yewtube. La compilación y ejecución Android no están validadas.

El menú conectado todavía es encode [N], limitado a MP3 explícito. La restauración de encoder/encoders y set encoder N, la selección persistente y su aplicación a da siguen pendientes. Hasta completar esa restauración, da conserva el audio original sin conversión.

## Requisitos y compilación

Se necesitan Linux x86_64, NDK, make, Python 3, pkg-config y paquetes fuente extraídos de FFmpeg, LAME, libogg y libvorbis con configure ejecutable. Las versiones y SHA-256 aún deben seleccionarse y verificarse; no se descargan automáticamente.

```sh
export OGG_SOURCE=/ruta/a/libogg-extraido
export VORBIS_SOURCE=/ruta/a/libvorbis-extraido
gradle :app:assembleDebug \
  -PenableFfmpeg=true \
  -PffmpegSource=/ruta/a/ffmpeg-extraido \
  -PlameSource=/ruta/a/lame-extraido \
  -PffmpegNdk=/ruta/al/ndk
```

FFMPEG_SOURCE, LAME_SOURCE y ANDROID_NDK_HOME son alternativas a las propiedades Gradle. OGG_SOURCE y VORBIS_SOURCE se heredan del entorno del proceso Gradle. Usar rutas absolutas para esas dos variables. enableFfmpeg es obligatorio para empaquetar los binarios; sin él se conserva la compilación base.

## Componentes exigidos

- libmp3lame: MP3 256k, MP3 192k, MP3 VBR best y MP3 VBR good.
- libvorbis y libogg: OGG 256k.
- aac nativo: M4A 256k.
- wmav2 nativo: Windows Media Audio v2.
- Contenedores MP3, OGG, ipod/M4A, ASF/WMA y Matroska.

El script compila bibliotecas estáticas para arm64-v8a y x86_64, API 26. pkg-config solo busca las bibliotecas del prefijo de cada ABI, evitando bibliotecas del host. Comprueba las macros de configuración y falla si faltan componentes, sin enable-gpl ni enable-nonfree. Estas comprobaciones no equivalen a ejecutar los siete perfiles.

Copia los avisos de FFmpeg, LAME, libogg y libvorbis a los assets generados. Conserva configuración y hashes de los binarios. FFmpeg tiene la red deshabilitada; yt-dlp descarga antes de procesar.

## Validación pendiente

Fijar las fuentes, validar compilación cruzada y probar en Android los cuatro codificadores, todos los perfiles, contenedores, decodificadores necesarios, extracción/permisos de ejecutables, ELF y alineación 16 KB. Probar selección, cancelación, persistencia, descarga y exportación. La sintaxis del script se ha comprobado; no se ha ejecutado la compilación nativa.

Conservar fuentes completas, configuración, parches, avisos y mecanismo de sustitución o reenlace aplicable a LGPL. Las copias de COPYING no certifican cumplimiento completo. No se actualizan dependencias existentes ni se certifica todavía equivalencia funcional con la app original.
