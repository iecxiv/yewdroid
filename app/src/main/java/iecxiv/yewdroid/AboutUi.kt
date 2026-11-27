package iecxiv.yewdroid

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val FG = Color(0xFFDCDFE4)
private val ACCENT = Color(0xFF98C379)

/** Repository of the app. */
private const val REPOSITORY_URL = "https://github.com/iecxiv/yewdroid"

/** The version name the installed app declares (versionName in build.gradle.kts); empty if it cannot be read. */
private fun appVersionName(context: Context): String = try {
    val manager = context.packageManager
    val info = if (Build.VERSION.SDK_INT >= 33) {
        manager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        manager.getPackageInfo(context.packageName, 0)
    }
    info.versionName.orEmpty()
} catch (_: Exception) {
    ""
}

/** The last section of Settings: the app version and a link to the repository. */
@Composable
internal fun AboutSettings() {
    val ctx = LocalContext.current
    val version = remember { appVersionName(ctx) }
    val body = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 15.sp, color = FG)
    val link = TextStyle(
        fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = ACCENT, textDecoration = TextDecoration.Underline
    )
    SettingsSection(stringResource(R.string.about_section)) {
        if (version.isNotEmpty()) {
            Text(stringResource(R.string.about_version, version), style = body)
        }
        Text(
            stringResource(R.string.about_repository),
            style = link,
            modifier = Modifier.fillMaxWidth()
                .clickable {
                    try {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPOSITORY_URL)))
                    } catch (_: ActivityNotFoundException) {
                    }
                }
                .padding(top = 8.dp, bottom = 8.dp)
        )
        Text(
            REPOSITORY_URL,
            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color.Gray)
        )
    }
}
