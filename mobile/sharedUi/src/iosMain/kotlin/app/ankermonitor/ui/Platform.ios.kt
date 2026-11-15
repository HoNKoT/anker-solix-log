package app.ankermonitor.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.dateWithTimeIntervalSince1970

actual fun formatClock(millis: Long, withSeconds: Boolean): String {
    val formatter = NSDateFormatter()
    formatter.dateFormat = if (withSeconds) "HH:mm:ss" else "HH:mm"
    return formatter.stringFromDate(NSDate.dateWithTimeIntervalSince1970(millis / 1000.0))
}

@Composable
actual fun platformColorScheme(dark: Boolean): ColorScheme? = null

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
}
