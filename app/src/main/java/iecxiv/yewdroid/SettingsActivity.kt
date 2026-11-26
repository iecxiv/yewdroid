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

private val BG = Color(0xFF0C0C0C)
private val FG = Color(0xFFDCDFE4)
private val ACCENT = Color(0xFF98C379)

/** Terminal translation languages shown in settings: stored value to label. */
private val LANGUAGES = listOf(
    "system" to R.string.language_system,
    "es" to R.string.language_spanish
)

class SettingsActivity : ComponentActivity() {
    private var resumes by mutableIntStateOf(0)

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
    val sessionStarted by TerminalHub.sessionStarted.collectAsState()
    val translationAvailable by TerminalHub.translationAvailable.collectAsState()
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
    val section = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = ACCENT)
    val body = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 15.sp, color = FG)
    val note = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color.Gray)
    Column(Modifier.fillMaxSize().background(BG).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = FG) }
            Text(stringResource(R.string.settings_title), style = title)
        }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.settings_permissions_section), style = section, modifier = Modifier.padding(vertical = 8.dp))
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

            if (language != null) {
                Text(
                    stringResource(R.string.language_section), style = section,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                )
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
