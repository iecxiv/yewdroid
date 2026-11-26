package iecxiv.yewdroid

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
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
import androidx.core.app.NotificationManagerCompat
import java.io.File

private val BG = Color(0xFF0C0C0C)
private val FG = Color(0xFFDCDFE4)
private val ACCENT = Color(0xFF98C379)
private val ERROR = Color(0xFFE06C75)

/** Terminal translation languages shown in settings: stored value to label. */
private val LANGUAGES = listOf(
    "system" to R.string.language_system,
    "es" to R.string.language_spanish
)

/** Guidance points shown before the user picks a cookies file. */
private val COOKIES_GUIDE = listOf(
    R.string.cookies_info_1,
    R.string.cookies_info_2,
    R.string.cookies_info_3,
    R.string.cookies_info_4,
    R.string.cookies_info_5
)

/**
 * One block of the Settings screen: a divider, the heading and the content indented under it, so the
 * sections can be told apart at a glance.
 */
@Composable
internal fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxWidth().padding(top = 20.dp).height(1.dp).background(Color(0xFF2A2A2A)))
    Text(
        title,
        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = ACCENT),
        modifier = Modifier.padding(top = 12.dp, bottom = 8.dp)
    )
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, bottom = 4.dp), content = content)
}

class SettingsActivity : ComponentActivity() {
    private var resumes by mutableIntStateOf(0)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SettingsScreen(resumes) { finish() } }
    }

    override fun onResume() {
        super.onResume()
        resumes++
    }
}

private fun Context.findActivity(): Activity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

private fun openFirst(ctx: Context, vararg intents: Intent) {
    for (intent in intents) {
        try { ctx.startActivity(intent); return } catch (_: ActivityNotFoundException) {}
    }
}

private fun notificationSettings(ctx: Context) =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)

private fun appDetails(ctx: Context) =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))

/** The YouTube cookies file the Python session hands to yt-dlp (yt_term.py looks for it by name). */
private fun cookiesFile(ctx: Context) = File(ctx.filesDir, "cookies.txt")

/**
 * Copies a cookies.txt (Netscape format) chosen by the user into the app's private storage.
 * Returns false when the file is empty, too large or has no YouTube/Google cookie line.
 */
private fun importCookies(ctx: Context, uri: Uri): Boolean = try {
    val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    val text = bytes?.takeIf { it.isNotEmpty() && it.size <= 5_000_000 }?.toString(Charsets.UTF_8)
    val valid = text != null && text.lineSequence().any { raw ->
        val line = raw.removePrefix("#HttpOnly_")
        !line.startsWith("#") && line.split('\t').size >= 7 &&
            (line.contains("youtube.com") || line.contains("google.com"))
    }
    if (valid && text != null) {
        val header = "# Netscape HTTP Cookie File"
        val content = if (text.contains("Netscape HTTP Cookie File")) text else "$header\n$text"
        val temp = File(ctx.filesDir, "cookies.txt.tmp")
        temp.writeText(content)
        temp.copyTo(cookiesFile(ctx), overwrite = true)
        temp.delete()
        cookiesFile(ctx).isFile
    } else {
        false
    }
} catch (_: Exception) {
    false
}

/** Explains the risks and the right way to export cookies before the file picker opens. */
@Composable
private fun CookiesGuideDialog(onContinue: () -> Unit, onDismiss: () -> Unit) {
    val body = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = FG)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1B1B1B),
        title = {
            Text(
                stringResource(R.string.cookies_info_title),
                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 16.sp, color = FG)
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                COOKIES_GUIDE.forEachIndexed { index, id ->
                    Text(
                        "${index + 1}. " + stringResource(id),
                        style = body,
                        modifier = Modifier.padding(bottom = 10.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onContinue, colors = ButtonDefaults.textButtonColors(contentColor = ACCENT)) {
                Text(stringResource(R.string.cookies_info_continue))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = Color.Gray)) {
                Text(stringResource(R.string.dialog_cancel))
            }
        }
    )
}

@Composable
private fun SettingsScreen(resumes: Int, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var changes by remember { mutableIntStateOf(0) }
    val notificationsAllowed = remember(resumes, changes) {
        NotificationManagerCompat.from(ctx).areNotificationsEnabled()
    }
    val batteryAllowed = remember(resumes, changes) {
        ctx.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(ctx.packageName) == true
    }
    val languageFlow = remember(ctx.applicationContext) { TerminalPreferences.terminalLanguage(ctx.applicationContext) }
    val savedLanguage by languageFlow.collectAsState(initial = null)
    var selectedLanguage by remember { mutableStateOf<String?>(null) }
    val language = selectedLanguage ?: savedLanguage
    var appLanguage by remember { mutableStateOf(AppLanguage.saved(ctx)) }
    val sessionStarted by TerminalHub.sessionStarted.collectAsState()
    val translationAvailable by TerminalHub.translationAvailable.collectAsState()
    var cookiesSaved by remember { mutableStateOf(cookiesFile(ctx).isFile) }
    var cookiesInvalid by remember { mutableStateOf(false) }
    var cookiesGuide by remember { mutableStateOf(false) }
    val cookiesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            cookiesInvalid = !importCookies(ctx, uri)
            cookiesSaved = cookiesFile(ctx).isFile
        }
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        changes++
        val activity = ctx.findActivity()
        if (!granted && activity != null &&
            !activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            openFirst(ctx, notificationSettings(ctx), appDetails(ctx))
        }
    }
    val batteryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { changes++ }

    fun allowNotifications() {
        val granted = Build.VERSION.SDK_INT < 33 ||
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (granted) openFirst(ctx, notificationSettings(ctx), appDetails(ctx))
        else notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    fun allowBattery() {
        val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName))
        try {
            batteryLauncher.launch(request)
        } catch (_: ActivityNotFoundException) {
            openFirst(ctx, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), appDetails(ctx))
        }
    }

    val title = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 20.sp, color = ACCENT)
    val body = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 15.sp, color = FG)
    val note = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color.Gray)
    Column(Modifier.fillMaxSize().background(BG).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = FG) }
            Text(stringResource(R.string.settings_title), style = title)
        }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            SettingsSection(stringResource(R.string.applang_section)) {
                Text(stringResource(R.string.applang_desc), style = note)
                val appLanguages = listOf(AppLanguage.SYSTEM to stringResource(R.string.applang_system)) + AppLanguage.SUPPORTED
                appLanguages.forEach { (value, label) ->
                    Row(
                        Modifier.fillMaxWidth()
                            .selectable(
                                selected = appLanguage == value,
                                role = Role.RadioButton,
                                onClick = {
                                    if (appLanguage != value) {
                                        appLanguage = value
                                        AppLanguage.save(ctx, value)
                                        // The terminal output follows the device language when set to "system".
                                        if (language == "system") TerminalHub.setLanguage(ctx, "system")
                                        ctx.findActivity()?.recreate()
                                    }
                                }
                            )
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = appLanguage == value,
                            onClick = null,
                            colors = RadioButtonDefaults.colors(selectedColor = ACCENT, unselectedColor = FG)
                        )
                        Text(label, style = body, modifier = Modifier.padding(start = 12.dp))
                    }
                }
                if (appLanguage == AppLanguage.SYSTEM && !AppLanguage.systemSupported()) {
                    Text(stringResource(R.string.applang_unsupported), style = note)
                }
            }

            SettingsSection(stringResource(R.string.settings_permissions_section)) {
                PermissionRow(
                    stringResource(R.string.permission_notifications),
                    stringResource(R.string.permission_notifications_desc),
                    notificationsAllowed
                ) { allowNotifications() }
                PermissionRow(
                    stringResource(R.string.permission_battery),
                    stringResource(R.string.permission_battery_desc),
                    batteryAllowed
                ) { allowBattery() }
                TextButton(
                    onClick = { openFirst(ctx, appDetails(ctx)) },
                    colors = ButtonDefaults.textButtonColors(contentColor = ACCENT)
                ) { Text(stringResource(R.string.settings_open_system)) }
            }

            if (language != null) {
                SettingsSection(stringResource(R.string.language_section)) {
                    Text(stringResource(R.string.language_section_desc), style = note)
                    LANGUAGES.forEach { (value, label) ->
                        Row(
                            Modifier.fillMaxWidth()
                                .selectable(
                                    selected = language == value,
                                    role = Role.RadioButton,
                                    onClick = {
                                        selectedLanguage = value
                                        TerminalHub.setLanguage(ctx, value)
                                    }
                                )
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = language == value,
                                onClick = null,
                                colors = RadioButtonDefaults.colors(selectedColor = ACCENT, unselectedColor = FG)
                            )
                            Text(stringResource(label), style = body, modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                    if (sessionStarted && !translationAvailable) {
                        Text(stringResource(R.string.language_unavailable), style = note)
                    }
                }
            }

            ConfigSettings(resumes)

            SettingsSection(stringResource(R.string.cookies_section)) {
                Text(stringResource(R.string.cookies_desc), style = note)
                Text(
                    stringResource(if (cookiesSaved) R.string.cookies_on else R.string.cookies_off),
                    style = body, modifier = Modifier.padding(top = 8.dp)
                )
                if (cookiesInvalid) {
                    Text(stringResource(R.string.cookies_invalid), style = note.copy(color = ERROR))
                }
                Row {
                    TextButton(
                        onClick = { cookiesGuide = true },
                        colors = ButtonDefaults.textButtonColors(contentColor = ACCENT)
                    ) { Text(stringResource(R.string.cookies_import)) }
                    if (cookiesSaved) {
                        TextButton(
                            onClick = {
                                cookiesFile(ctx).delete()
                                cookiesSaved = cookiesFile(ctx).isFile
                                cookiesInvalid = false
                            },
                            colors = ButtonDefaults.textButtonColors(contentColor = ACCENT)
                        ) { Text(stringResource(R.string.cookies_remove)) }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (cookiesGuide) {
        CookiesGuideDialog(
            onContinue = {
                cookiesGuide = false
                cookiesLauncher.launch(arrayOf("*/*"))
            },
            onDismiss = { cookiesGuide = false }
        )
    }
}

@Composable
private fun PermissionRow(name: String, description: String, allowed: Boolean, onAllow: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(name, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 15.sp, color = FG))
            Text(description, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color.Gray))
        }
        if (allowed) {
            Text(stringResource(R.string.permission_allowed), style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = ACCENT))
        } else {
            TextButton(onClick = onAllow, colors = ButtonDefaults.textButtonColors(contentColor = ACCENT)) {
                Text(stringResource(R.string.permission_allow))
            }
        }
    }
}
