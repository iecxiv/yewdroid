package iecxiv.yewdroid

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val FG = Color(0xFFDCDFE4)
private val ACCENT = Color(0xFF98C379)
private val MUTED = Color(0xFF7F848E)
private val DIALOG = Color(0xFF1B1B1B)
private val ERROR = Color(0xFFE06C75)

/**
 * yewtube playlist names start with a letter or underscore and use letters, digits, - and _;
 * spaces become hyphens. Returns null when the text cannot be a valid name.
 */
internal fun playlistName(raw: String): String? {
    val name = raw.trim().replace(' ', '-')
    if (name.isEmpty()) return null
    if (!(name[0].isLetter() || name[0] == '_')) return null
    return if (name.all { it.isLetterOrDigit() || it == '-' || it == '_' }) name else null
}

@Composable
private fun DarkDialog(
    title: String,
    onDismiss: () -> Unit,
    confirm: @Composable () -> Unit,
    content: @Composable () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DIALOG,
        titleContentColor = ACCENT,
        textContentColor = FG,
        title = { Text(title, fontFamily = FontFamily.Monospace, fontSize = 16.sp) },
        text = content,
        confirmButton = confirm,
        dismissButton = {
            TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = MUTED)) {
                Text(stringResource(R.string.dialog_cancel))
            }
        }
    )
}

/** Asks for a playlist name and validates it before confirming. */
@Composable
internal fun NameDialog(
    title: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    initial: String = ""
) {
    var text by remember { mutableStateOf(initial) }
    val valid = playlistName(text)
    val invalid = text.isNotBlank() && valid == null
    DarkDialog(
        title = title,
        onDismiss = onDismiss,
        confirm = {
            TextButton(
                onClick = { valid?.let(onConfirm) },
                enabled = valid != null,
                colors = ButtonDefaults.textButtonColors(contentColor = ACCENT, disabledContentColor = MUTED)
            ) { Text(confirmLabel) }
        }
    ) {
        Column {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth()
                    .border(1.dp, if (invalid) ERROR else ACCENT, RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                singleLine = true,
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = Color.White),
                cursorBrush = SolidColor(ACCENT),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { valid?.let(onConfirm) }),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth()) {
                        if (text.isEmpty()) {
                            Text(
                                stringResource(R.string.playlist_name_hint),
                                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = MUTED)
                            )
                        }
                        inner()
                    }
                }
            )
            if (invalid) {
                Text(
                    stringResource(R.string.playlist_name_invalid),
                    style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = ERROR),
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}

@Composable
internal fun ConfirmDialog(
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    DarkDialog(
        title = title,
        onDismiss = onDismiss,
        confirm = {
            TextButton(onClick = onConfirm, colors = ButtonDefaults.textButtonColors(contentColor = ERROR)) {
                Text(confirmLabel)
            }
        },
        content = {}
    )
}

/** Lists the saved playlists and "New playlist"; [onPick] receives a valid playlist name. */
@Composable
internal fun AddToPlaylistDialog(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val playlists by TerminalHub.playlists.collectAsState()
    var creating by remember { mutableStateOf(false) }
    if (creating) {
        NameDialog(
            title = stringResource(R.string.playlist_new),
            confirmLabel = stringResource(R.string.playlist_add_confirm),
            onConfirm = onPick,
            onDismiss = { creating = false }
        )
        return
    }
    DarkDialog(
        title = stringResource(R.string.playlist_add_title),
        onDismiss = onDismiss,
        confirm = {}
    ) {
        LazyColumn(Modifier.heightIn(max = 300.dp)) {
            item {
                Row(
                    Modifier.fillMaxWidth().clickable { creating = true }.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, tint = ACCENT, modifier = Modifier.size(18.dp))
                    Text(
                        stringResource(R.string.playlist_new),
                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = ACCENT),
                        modifier = Modifier.padding(start = 10.dp)
                    )
                }
            }
            items(playlists.items, key = { it.name }) { playlist ->
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(playlist.name) }.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = FG),
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        stringResource(R.string.playlist_count, playlist.count),
                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MUTED)
                    )
                }
            }
        }
    }
}

/** The "Playlists" tab: saved local playlists with open, play, rename and delete. */
@Composable
fun PlaylistsScreen(onOpened: (PlaylistItem) -> Unit, modifier: Modifier = Modifier) {
    val state by TerminalHub.playlists.collectAsState()
    var renaming by remember { mutableStateOf<PlaylistItem?>(null) }
    var deleting by remember { mutableStateOf<PlaylistItem?>(null) }
    if (state.items.isEmpty()) {
        Box(modifier.padding(16.dp), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.playlists_empty),
                style = TextStyle(
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MUTED, textAlign = TextAlign.Center
                )
            )
        }
    } else {
        LazyColumn(modifier) {
            items(state.items, key = { it.name }) { playlist ->
                PlaylistRow(
                    playlist,
                    onOpen = {
                        TerminalHub.runCommand("open ${playlist.n}")
                        onOpened(playlist)
                    },
                    onPlay = { TerminalHub.runCommand("play ${playlist.n}") },
                    onRename = { renaming = playlist },
                    onDelete = { deleting = playlist }
                )
            }
        }
    }
    renaming?.let { playlist ->
        NameDialog(
            title = stringResource(R.string.playlist_rename_title),
            confirmLabel = stringResource(R.string.playlist_rename),
            initial = playlist.name,
            onConfirm = { name ->
                TerminalHub.runCommand("mv ${playlist.n} $name")
                renaming = null
            },
            onDismiss = { renaming = null }
        )
    }
    deleting?.let { playlist ->
        ConfirmDialog(
            title = stringResource(R.string.playlist_delete_confirm, playlist.name),
            confirmLabel = stringResource(R.string.playlist_delete),
            onConfirm = {
                TerminalHub.runCommand("rmp ${playlist.n}")
                deleting = null
            },
            onDismiss = { deleting = null }
        )
    }
}

@Composable
private fun PlaylistRow(
    playlist: PlaylistItem,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = FG)
            )
            val detail = listOf(
                stringResource(R.string.playlist_count, playlist.count),
                playlist.duration
            ).filter { it.isNotEmpty() }.joinToString(" \u00b7 ")
            Text(detail, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MUTED))
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.client_more_options), tint = FG)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.playlist_play)) },
                    onClick = { menu = false; onPlay() }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.playlist_open)) },
                    onClick = { menu = false; onOpen() }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.playlist_rename)) },
                    onClick = { menu = false; onRename() }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.playlist_delete)) },
                    onClick = { menu = false; onDelete() }
                )
            }
        }
    }
}
