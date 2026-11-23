# Descargas persistentes

Estado: pendiente de compilar y validar en Android. Pruebas Python con transferencias simuladas.

`da N`, `dv N` y las selecciones multiples encolan tareas. Encolado no significa guardado.

- `downloads status`: historial global y ultimas 50 entradas, destinos y errores.
- `downloads cancel`: cancela pendientes/en curso; no borra las terminadas.
- `downloads resume`: recupera canceladas y activa pendientes guardadas.
- `downloads retry`: recupera fallidas, sin repetir las registradas como terminadas.

Android 14+: JobScheduler UIDT con notificacion. Android 8-13: WorkManager foreground dataSync.
Iniciar/reanudar desde la app visible. Permitir notificaciones para ver el progreso.
SQLite vive en files/download-queue.sqlite3 y los parciales en files/download-work/ID.
Se extraen URLs nuevas al reintentar; las URLs firmadas no son la identidad persistente.
Los fallos de red identificados permiten tres intentos de cola con espera del planificador,
ademas de los reintentos internos de yt-dlp. Los fallos permanentes no abortan las siguientes pistas.
Cancelacion cooperativa: una peticion o un mux en curso puede tardar en detenerse.

## Validacion

`python3 -m unittest discover -s tests -p test_download_queue.py -v`

Compilar/instalar y probar dos pistas conocidas: notificacion, otra app, pantalla apagada,
cancelar/reanudar, perder/recuperar red y verificar que las terminadas no se repiten.
Luego comparar bateria Optimizado frente a Sin restricciones. No se ha probado en movil aun.

## Limites

No garantiza ejecucion ininterrumpida frente a parada forzada o restricciones del fabricante.
Tras volver a abrir la app puede ser necesario `downloads resume`. La recuperacion de parciales
depende del formato, titulo y servidor. No arregla DNS ni desafios JS de YouTube.
Si hay una interrupcion justo entre publicar en MediaStore y registrar el resultado en SQLite,
puede aparecer un duplicado al reintentar: no se promete semantica exactamente-una-vez.
No se elimina automaticamente el historial ni los diagnosticos fallidos.
