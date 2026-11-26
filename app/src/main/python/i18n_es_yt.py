"""Spanish catalogue for the yewtube screens and messages (main view, lists, history, info panels, settings)."""


def _labels(width, pairs, tail="{}"):
    return {english.ljust(width) + ": " + tail: spanish.ljust(width) + ": " + tail
            for english, spanish in pairs}


# Column headings keep their width: a longer word takes space from the gap next to it.
HEADINGS = {
    "Num": "Núm", "Title": "Título", "Length": "Tiempo", "View": "Vis.", "Rtng": "Val.", "Comm": "Com.",
    "Date": "Fecha", "Time": "Tiempo", "User": "Usuario", "Like": "Gus.", "Dslk": "NoG.",
    "Category": "Categoría", "Video ID": "ID vídeo", "Username": "Usuario", "Description": "Descripción",
    "Item": "Nº", "Playlist": "Lista", "Author": "Autor", "Updated": "Cambio", "Count": "Núm.",
    "Name": "Nombre", "Duration": "Duración", "Format": "Formato", "Quality": "Calidad",
    "Media": "Medio", "Size": "Tamaño", "Notes": "Notas", "Key": "Clave", "Value": "Valor",
}

CATALOG = {
    # main view
    "Enter /search-term to search or [h]elp": "Escribe /término para buscar o [h]elp para ver la ayuda",
    "Press ctrl-c again to exit": "Pulsa Ctrl-C otra vez para salir",
    "No playlist loaded": "No hay ninguna lista cargada",
    "No playlists found!": "¡No se encontraron listas!",
    "Local Playlists": "Listas locales",
    # playlists and results
    "Showing YouTube playlist {}": "Mostrando la lista de YouTube {}",
    "Retrieving YouTube playlist": "Obteniendo la lista de YouTube",
    "No next items to display": "No hay elementos siguientes que mostrar",
    "No previous items to display": "No hay elementos anteriores que mostrar",
    "{} may only be used on an open YouTube playlist": "{} solo puede usarse en una lista de YouTube abierta",
    "Items shuffled": "Elementos mezclados",
    "Shuffled entire playlist": "Lista completa mezclada",
    "Reversed displayed songs": "Elementos mostrados invertidos",
    "Reversed range: {}": "Rango invertido: {}",
    "Reversed entire playlist": "Lista completa invertida",
    "Deleted playlist {}": "Lista {} borrada",
    "Cleared all songs": "Todos los elementos quitados",
    "Nothing to save. Search for items and then use {} to add them": "Nada que guardar. Busca elementos y luego usa {} para añadirlos",
    "Playlist is empty! Use {} to add a track": "¡La lista está vacía! Usa {} para añadir una pista",
    "Playlist is empty! Search for items and then use {} to add them": "¡La lista está vacía! Busca elementos y luego usa {} para añadirlos",
    "{#} of {#}": "{#} de {#}",
    # playback
    "Invalid playlist selection: {}": "Selección de lista no válida: {}",
    "There are no tracks to select": "No hay pistas que seleccionar",
    "Problem playing last item: Conflicting override options specified": "Problema al reproducir el último elemento: opciones de modo en conflicto",
    "Player not configured! Enter {} to set a player": "Reproductor no configurado. Escribe {} para configurar uno",
    "No previous search.": "No hay ninguna búsqueda anterior.",
    "Out of range.": "Fuera de rango.",
    "Stopping...": "Deteniendo...",
    "Playback halted": "Reproducción detenida",
    "fetching item..": "obteniendo elemento..",
    # search
    "Results for channel search: '{}'": "Resultados de la búsqueda de canales: '{}'",
    "That is not a valid category. Valid categories are: {}": "Categoría no válida. Las categorías válidas son: {}",
    "Livestreams in category: '{}'": "Directos de la categoría: '{}'",
    "User {} not found or has no videos in the Music category.": "Usuario {} no encontrado o sin vídeos en la categoría Música.",
    "Use 'set search_music False' to show results not in the Music category.": "Usa 'set search_music False' para mostrar resultados que no sean de la categoría Música.",
    "Related items must refer to a specific video item": "Los elementos relacionados deben referirse a un vídeo concreto",
    "Videos related to {}": "Vídeos relacionados con {}",
    "Related to {} not found": "No se encontraron elementos relacionados con {}",
    "Error while opening the file, check the validity of the path": "Error al abrir el archivo; comprueba que la ruta sea válida",
    "Press any key to continue...": "Pulsa cualquier tecla para continuar...",
    # comments, clipboard, information
    "cache cleared": "caché borrada",
    "Fetching comments for {}": "Obteniendo comentarios de {}",
    "No comments for {}": "No hay comentarios de {}",
    "Comments only available for video items": "Los comentarios solo están disponibles para vídeos",
    "clipboard copy not valid in this mode": "copiar al portapapeles no es válido en este modo",
    "{} copied": "{} copiado",
    "Error - couldn't copy to clipboard.": "Error: no se pudo copiar al portapapeles.",
    "Fetching playlist info..": "Obteniendo información de la lista..",
    "Fetching video metadata..": "Obteniendo metadatos del vídeo..",
    "Fetching stream metadata..": "Obteniendo metadatos del flujo..",
    "Fetched": "Obtenido",
    "Playlist Info": "Información de la lista",
    "Video Info": "Información del vídeo",
    "Stream Info": "Información del flujo",
    "Description:": "Descripción:",
    "Keywords: {}": "Palabras clave: {}",
    "[Press enter to go back]": "[Pulsa Enter para volver]",
    "Size       : {} videos": "Tamaño     : {} vídeos",
    # history
    "Viewing play history": "Viendo el historial de reproducción",
    "Viewing recent played songs": "Viendo las canciones reproducidas recientemente",
    "Viewing play history\tHistory recording is currently off": "Viendo el historial de reproducción\tLa grabación del historial está desactivada",
    "Viewing recent played songs\tHistory recording is currently off": "Viendo las canciones reproducidas recientemente\tLa grabación del historial está desactivada",
    "History empty": "Historial vacío",
    "History empty\tHistory recording is currently off": "Historial vacío\tLa grabación del historial está desactivada",
    "History cleared": "Historial borrado",
    # settings (messages returned by set)
    "{} must be one of {} - not {}": "{} debe ser uno de {} - no {}",
    "{} requires True/False, got {}": "{} requiere True/False, se recibió {}",
    "{} requires a number, got {}": "{} requiere un número, se recibió {}",
    "{} must be between {#} and {#}, got {}": "{} debe estar entre {#} y {#}, se recibió {}",
    "{} set to False": "{} establecido en False",
    "{} set to True": "{} establecido en True",
    "{} set to {}": "{} establecido en {}",
    "{} is too high, type {} to see valid values": "{} es demasiado alto; escribe {} para ver los valores válidos",
    "Encoder set to {}": "Codificador establecido en {}",
    # exit
    "Released under the GPLv3 license": "Publicado bajo la licencia GPLv3",
}
CATALOG.update(_labels(11, [("Author", "Autor"), ("Created", "Creada"), ("Updated", "Modificada")]))
CATALOG.update(_labels(15, [
    ("Is Live Now", "En directo"), ("Duration", "Duración"), ("View count", "Reproducciones"),
    ("Author", "Autor"), ("Published Date", "Publicado"), ("Uploaded Date", "Subido"),
    ("Rating", "Valoración"), ("Likes", "Me gusta"), ("Dislikes", "No me gusta"),
    ("Category", "Categoría"), ("Family Safe", "Apto familias"), ("Link", "Enlace")]))
CATALOG.update(_labels(12, [
    ("Extension", "Extensión"), ("Size", "Tamaño"), ("Quality", "Calidad"),
    ("Raw bitrate", "Tasa bruta"), ("Media type", "Tipo"), ("Link", "Enlace")]))
