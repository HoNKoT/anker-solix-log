package app.ankermonitor.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

@Composable
fun AnkerTheme(
    dark: Boolean = isSystemInDarkTheme(),
    /** Preview では false にして、端末の壁紙に左右されず Android / iOS で同じ配色にする */
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val scheme = (if (dynamicColor) platformColorScheme(dark) else null) ?: if (dark) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = scheme, content = content)
}
