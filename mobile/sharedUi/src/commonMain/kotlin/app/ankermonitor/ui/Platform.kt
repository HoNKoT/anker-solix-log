package app.ankermonitor.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

/** 時刻の表示 (HH:mm または HH:mm:ss) */
expect fun formatClock(millis: Long, withSeconds: Boolean): String

/** Android 12 以上のダイナミックカラー。無ければ null (既定の配色を使う) */
@Composable
expect fun platformColorScheme(dark: Boolean): ColorScheme?

/** 戻る操作 (Android の戻るボタン / ジェスチャ)。iOS には無いので何もしない */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)
