package app.ankermonitor.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

actual fun formatClock(millis: Long, withSeconds: Boolean): String =
    SimpleDateFormat(if (withSeconds) "HH:mm:ss" else "HH:mm", Locale.JAPAN).format(Date(millis))

@Composable
actual fun platformColorScheme(dark: Boolean): ColorScheme? {
    if (Build.VERSION.SDK_INT < 31) return null
    val context = LocalContext.current
    return if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
}

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = BackHandler(enabled, onBack)
