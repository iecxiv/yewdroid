package iecxiv.yewdroid

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private val BG = Color(0xFF0C0C0C)
private val FG = Color(0xFFDCDFE4)
private val ACCENT = Color(0xFF98C379)
private val ERROR = Color(0xFFE06C75)

/** One yewtube option: [kind] is "bool", "choice" or "columns". */
data class ConfigOption(
    val key: String,
    val kind: String,
    val flag: Boolean,
    val value: String,
    val options: List<String>
)

data class EncoderPreset(val index: Int, val name: String, val ext: String)

data class ConfigState(
    val options: List<ConfigOption>,
    val encoders: List<EncoderPreset>,
    val encoder: Int,
    val ffmpeg: Boolean
)

/** Parses the JSON produced by android_settings.snapshot(). */
fun parseConfigState(raw: String): ConfigState {
    val json = JSONObject(raw)
    val options = json.optJSONArray("options") ?: JSONArray()
    val encoders = json.optJSONArray("encoders") ?: JSONArray()
    return ConfigState(
        options = List(options.length()) { index ->
            val item = options.getJSONObject(index)
            val values = item.optJSONArray("options") ?: JSONArray()
            ConfigOption(
                key = item.optString("key"),
                kind = item.optString("kind"),
                flag = item.optBoolean("flag"),
                value = item.optString("value"),
                options = List(values.length()) { values.getString(it) }
            )
        },
        encoders = List(encoders.length()) { index ->
            val item = encoders.getJSONObject(index)
            EncoderPreset(item.optInt("index"), item.optString("name"), item.optString("ext"))
        },
        encoder = json.optInt("encoder"),
        ffmpeg = json.optBoolean("ffmpeg")
    )
}

private fun settingsModule() = Python.getInstance().getModule("android_settings")

private fun loadConfig(): ConfigState = parseConfigState(settingsModule().callAttr("snapshot").toString())

private fun saveConfig(key: String, value: String): Boolean =
    JSONObject(settingsModule().callAttr("set_value", key, value).toString()).optBoolean("ok")

private fun resetConfig() {
    settingsModule().callAttr("reset")
}

/** The "keep the original audio" switch is stored by Python (android_dl_encode), next to the download code. */
private fun loadKeepOriginal(): Boolean =
    Python.getInstance().getModule("android_dl_encode").callAttr("keep_original").toBoolean()

private fun saveKeepOriginal(value: Boolean) {
    Python.getInstance().getModule("android_dl_encode").callAttr("set_keep_original", value)
}

/** What to do when a video has no stream within the maximum resolution: "up" or "audio" (stored by yt_term.py). */
private fun loadLowRes(): String =
    Python.getInstance().getModule("yt_term").callAttr("lowres_mode").toString()

private fun saveLowRes(value: String) {
    Python.getInstance().getModule("yt_term").callAttr("set_lowres_mode", value)
}

/** Option key to its name and description. */
private val LABELS = mapOf(
    "AUDIO_FORMAT" to (R.string.cfgui_audio_format to R.string.cfgui_audio_format_desc),
    "VIDEO_FORMAT" to (R.string.cfgui_video_format to R.string.cfgui_video_format_desc),
    "MAX_RES" to (R.string.cfgui_max_res to R.string.cfgui_max_res_desc),
    "ORDER" to (R.string.cfgui_order to R.string.cfgui_order_desc),
    "COLUMNS" to (R.string.cfgui_columns to R.string.cfgui_columns_desc),
    "SHOW_VIDEO" to (R.string.cfgui_show_video to R.string.cfgui_show_video_desc),
    "ALWAYS_REPEAT" to (R.string.cfgui_always_repeat to R.string.cfgui_always_repeat_desc),
    "HISTORY" to (R.string.cfgui_history to R.string.cfgui_history_desc)
)

@Composable
private fun optionLabel(key: String, value: String): String = when {
    value == "auto" -> stringResource(R.string.cfgui_auto)
    key == "MAX_RES" -> "${value}p"
    else -> value
}

/** The yewtube options and the encoding presets, as sections of the Settings screen. */
@Composable
internal fun ConfigSettings(resumes: Int) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val client by TerminalHub.client.collectAsState()
    var state by remember { mutableStateOf<ConfigState?>(null) }
    var failed by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ConfigOption?>(null) }
    var resetting by remember { mutableStateOf(false) }
    var reloads by remember { mutableIntStateOf(0) }
    var keepOriginal by remember { mutableStateOf(false) }
    var lowRes by remember { mutableStateOf("up") }
    var editingLowRes by remember { mutableStateOf(false) }

    // The session must have finished starting (config loaded, presets read) before reading or writing.
    LaunchedEffect(client.ready, resumes, reloads) {
        if (client.ready) {
            val result = withContext(Dispatchers.Default) { runCatching { loadConfig() } }
            state = result.getOrNull()
            failed = result.isFailure
            keepOriginal = withContext(Dispatchers.Default) { runCatching { loadKeepOriginal() }.getOrDefault(false) }
            lowRes = withContext(Dispatchers.Default) { runCatching { loadLowRes() }.getOrDefault("up") }
        }
    }

    fun change(key: String, value: String) {
        scope.launch {
            withContext(Dispatchers.Default) { runCatching { saveConfig(key, value) } }
            reloads++
        }
    }

    fun changeKeepOriginal(value: Boolean) {
        keepOriginal = value
        scope.launch { withContext(Dispatchers.Default) { runCatching { saveKeepOriginal(value) } } }
    }

    fun changeLowRes(value: String) {
        lowRes = value
        scope.launch { withContext(Dispatchers.Default) { runCatching { saveLowRes(value) } } }
    }

    val body = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 15.sp, color = FG)
    val note = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color.Gray)
    val current = state

    SettingsSection(stringResource(R.string.cfgui_section)) {
        if (current == null) {
            Text(stringResource(if (failed) R.string.cfgui_failed else R.string.cfgui_wait), style = note)
        } else {
            current.options.forEach { option ->
                val labels = LABELS[option.key] ?: return@forEach
                val isFlag = option.kind == "bool"
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { if (isFlag) change(option.key, if (option.flag) "0" else "1") else editing = option }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(labels.first), style = body)
                        Text(stringResource(labels.second), style = note)
                        if (!isFlag) {
                            val shown = if (option.kind == "columns") option.value else optionLabel(option.key, option.value)
                            Text(shown, style = body.copy(color = ACCENT), modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                    if (isFlag) {
                        Switch(
                            checked = option.flag,
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
                // What to do when a video has nothing within the maximum resolution, right under that option.
                if (option.key == "MAX_RES") {
                    LowResRow(lowRes) { editingLowRes = true }
                }
            }
        }
    }

    if (current != null) {
        SettingsSection(stringResource(R.string.cfgui_encoding_section)) {
            Text(stringResource(R.string.cfgui_encoding_desc), style = note)
            Text(
                stringResource(if (current.ffmpeg) R.string.cfgui_ffmpeg_on else R.string.cfgui_ffmpeg_off),
                style = note.copy(color = if (current.ffmpeg) ACCENT else ERROR),
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
            )
            current.encoders.forEach { preset ->
                Row(
                    Modifier.fillMaxWidth()
                        .selectable(
                            selected = current.encoder == preset.index,
                            role = Role.RadioButton,
                            onClick = { change("ENCODER", preset.index.toString()) }
                        )
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = current.encoder == preset.index,
                        onClick = null,
                        colors = RadioButtonDefaults.colors(selectedColor = ACCENT, unselectedColor = FG)
                    )
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(preset.name, style = body)
                        if (preset.ext.isNotEmpty() && preset.ext != "COPY") Text(".${preset.ext}", style = note)
                    }
                }
            }
            // Keeping the original only makes sense when a preset (not "None") converts the download.
            val converting = current.encoders.firstOrNull { it.index == current.encoder }
                ?.ext?.let { it.isNotEmpty() && it != "COPY" } == true
            Row(
                Modifier.fillMaxWidth()
                    .clickable(enabled = converting) { changeKeepOriginal(!keepOriginal) }
                    .padding(top = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.cfgui_keep_original),
                        style = if (converting) body else body.copy(color = Color.Gray)
                    )
                    Text(stringResource(R.string.cfgui_keep_original_desc), style = note)
                }
                Switch(
                    checked = keepOriginal && converting,
                    onCheckedChange = null,
                    enabled = converting,
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
            Row {
                TextButton(
                    onClick = { ctx.startActivity(Intent(ctx, EncoderEditorActivity::class.java)) },
                    colors = ButtonDefaults.textButtonColors(contentColor = ACCENT)
                ) { Text(stringResource(R.string.cfgui_edit_presets)) }
                TextButton(
                    onClick = { resetting = true },
                    colors = ButtonDefaults.textButtonColors(contentColor = ACCENT)
                ) { Text(stringResource(R.string.cfgui_reset)) }
            }
        }
    }

    editing?.let { option ->
        if (option.kind == "columns") {
            ColumnsDialog(
                option = option,
                onPick = { value ->
                    editing = null
                    change(option.key, value)
                },
                onDismiss = { editing = null }
            )
        } else {
            ChoiceDialog(
                option = option,
                onPick = { value ->
                    editing = null
                    change(option.key, value)
                },
                onDismiss = { editing = null }
            )
        }
    }
    if (editingLowRes) {
        LowResDialog(
            mode = lowRes,
            onPick = { value ->
                editingLowRes = false
                changeLowRes(value)
            },
            onDismiss = { editingLowRes = false }
        )
    }
    if (resetting) {
        ConfirmDialog(
            title = stringResource(R.string.cfgui_reset_confirm),
            confirmLabel = stringResource(R.string.cfgui_reset),
            onConfirm = {
                resetting = false
                scope.launch {
                    withContext(Dispatchers.Default) { runCatching { resetConfig() } }
                    reloads++
                }
            },
            onDismiss = { resetting = false }
        )
    }
}

/** The row that shows the chosen way to handle a video with nothing within the maximum resolution. */
@Composable
private fun LowResRow(mode: String, onClick: () -> Unit) {
    val body = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 15.sp, color = FG)
    val note = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color.Gray)
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.lowres_title), style = body)
            Text(stringResource(R.string.lowres_desc), style = note)
            Text(
                stringResource(if (mode == "audio") R.string.lowres_audio else R.string.lowres_up),
                style = body.copy(color = ACCENT),
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

/** The two ways to handle it, each with its explanation. */
@Composable
private fun LowResDialog(mode: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val body = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 15.sp, color = FG)
    val note = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color.Gray)
    val choices = listOf(
        Triple("up", R.string.lowres_up, R.string.lowres_up_desc),
        Triple("audio", R.string.lowres_audio, R.string.lowres_audio_desc)
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1B1B1B),
        title = {
            Text(
                stringResource(R.string.lowres_title),
                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 16.sp, color = FG)
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                choices.forEach { (value, label, description) ->
                    Row(
                        Modifier.fillMaxWidth()
                            .selectable(
                                selected = value == mode,
                                role = Role.RadioButton,
                                onClick = { onPick(value) }
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        RadioButton(
                            selected = value == mode,
                            onClick = null,
                            colors = RadioButtonDefaults.colors(selectedColor = ACCENT, unselectedColor = FG)
                        )
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(stringResource(label), style = body)
                            Text(stringResource(description), style = note)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = Color.Gray)) {
                Text(stringResource(R.string.dialog_cancel))
            }
        }
    )
}

@Composable
private fun ChoiceDialog(option: ConfigOption, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val body = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 15.sp, color = FG)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1B1B1B),
        title = {
            Text(
                stringResource(LABELS[option.key]?.first ?: R.string.cfgui_section),
                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 16.sp, color = FG)
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                option.options.forEach { value ->
                    Row(
                        Modifier.fillMaxWidth()
                            .selectable(
                                selected = value == option.value,
                                role = Role.RadioButton,
                                onClick = { onPick(value) }
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = value == option.value,
                            onClick = null,
                            colors = RadioButtonDefaults.colors(selectedColor = ACCENT, unselectedColor = FG)
                        )
                        Text(optionLabel(option.key, value), style = body, modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = Color.Gray)) {
                Text(stringResource(R.string.dialog_cancel))
            }
        }
    )
}

@Composable
private fun ColumnsDialog(option: ConfigOption, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val body = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 15.sp, color = FG)
    var chosen by remember { mutableStateOf(option.value.split(' ').filter { it.isNotEmpty() }.toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1B1B1B),
        title = {
            Text(
                stringResource(R.string.cfgui_columns),
                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 16.sp, color = FG)
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                option.options.forEach { name ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { chosen = if (name in chosen) chosen - name else chosen + name }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = name in chosen,
                            onCheckedChange = null,
                            colors = CheckboxDefaults.colors(checkedColor = ACCENT, uncheckedColor = FG, checkmarkColor = BG)
                        )
                        Text(name, style = body, modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onPick(option.options.filter { it in chosen }.joinToString(" ")) },
                colors = ButtonDefaults.textButtonColors(contentColor = ACCENT)
            ) { Text(stringResource(R.string.cfgui_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = Color.Gray)) {
                Text(stringResource(R.string.dialog_cancel))
            }
        }
    )
}
