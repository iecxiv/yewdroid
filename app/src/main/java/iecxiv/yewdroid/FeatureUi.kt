package iecxiv.yewdroid

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val BG = Color(0xFF0C0C0C)
private val FG = Color(0xFFDCDFE4)
private val ACCENT = Color(0xFF98C379)
private val MUTED = Color(0xFF7F848E)

@Composable
private fun queueModeName(mode: QueueMode): String = stringResource(
    when (mode) {
        QueueMode.NONE -> R.string.feat_queue_none
        QueueMode.PAGE -> R.string.feat_queue_page
        QueueMode.RELATED -> R.string.feat_queue_related
    }
)

/** The queue-mode picker of the client, with the "save as playlist" button when there are results to save. */
@Composable
internal fun QueueModeRow(mode: QueueMode, showSave: Boolean, onPick: (QueueMode) -> Unit, onSave: () -> Unit) {
    val small = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MUTED)
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(R.string.feat_queue_label), style = small)
        Box(Modifier.weight(1f)) {
            Text(
                queueModeName(mode) + " \u25BE",
                style = small.copy(color = ACCENT),
                modifier = Modifier.clickable { menu = true }.padding(horizontal = 8.dp, vertical = 8.dp)
            )
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                QueueMode.values().forEach { value ->
                    DropdownMenuItem(
                        text = { Text(queueModeName(value)) },
                        onClick = {
                            menu = false
                            onPick(value)
                        }
                    )
                }
            }
        }
        if (showSave) {
            TextButton(
                onClick = onSave,
                colors = ButtonDefaults.textButtonColors(contentColor = ACCENT)
            ) { Text(stringResource(R.string.client_save_playlist), fontSize = 12.sp) }
        }
    }
}

/** The Settings section with the switch that enables the terminal. */
@Composable
internal fun FeatureSettings() {
    val ctx = LocalContext.current
    val flow = remember(ctx.applicationContext) { FeaturePrefs.terminalEnabled(ctx.applicationContext) }
    val saved by flow.collectAsState(initial = null)
    var selected by remember { mutableStateOf<Boolean?>(null) }
    val enabled = selected ?: saved ?: return
    val body = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 15.sp, color = FG)
    val note = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color.Gray)
    SettingsSection(stringResource(R.string.feat_terminal_section)) {
        Row(
            Modifier.fillMaxWidth()
                .clickable {
                    val value = !enabled
                    selected = value
                    FeaturePrefs.saveTerminalEnabled(ctx, value)
                }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.feat_terminal_title), style = body)
                Text(stringResource(R.string.feat_terminal_desc), style = note)
            }
            Switch(
                checked = enabled,
                onCheckedChange = null,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = BG,
                    checkedTrackColor = ACCENT,
                    checkedBorderColor = ACCENT,
                    uncheckedThumbColor = FG,
                    uncheckedTrackColor = Color(0xFF2A2A2A),
                    uncheckedBorderColor = Color(0xFF555555)
                )
            )
        }
    }
}
