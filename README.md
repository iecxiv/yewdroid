# YewTube Android

yewtube (mps_youtube, instalado desde PyPI) ejecutandose dentro de una app nativa Android con Chaquopy.

- Interfaz: terminal propia en Compose (colores ANSI, \r, borrado de pantalla). El usuario escribe los comandos de yewtube (/busqueda, 1, d 1, playlists...).
- Reproduccion: el reproductor externo (mpv/vlc) se sustituye por ExoPlayer en un MediaSessionService (segundo plano + notificacion).
- Codigo de yewtube SIN modificar: yt_term.py parchea en runtime stdin/stdout, tamano de terminal, clear y player.

Requisitos: Android Studio (JBR 21), Python 3.13 con pip y venv en el PC de compilacion.
Licencia: yewtube es GPL-3.0; uso personal.
