package app.ankermonitor.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ダッシュボード (dashboard.html) の --in / --out / --sub
@Composable
fun flowInColor(): Color = if (isSystemInDarkTheme()) Color(0xFF34C759) else Color(0xFF1F9D55)

@Composable
fun flowOutColor(): Color = if (isSystemInDarkTheme()) Color(0xFFFF9F0A) else Color(0xFFE8590C)

@Composable
fun flowOffColor(): Color = if (isSystemInDarkTheme()) Color(0xFF9A9AA0) else Color(0xFF6E6E73)

private const val CYCLE_MILLIS = 1_200
private const val DELAY_FRACTION = 0.2f / 1.2f

/**
 * ダッシュボードの ARROW (3 本の「›」) と @keyframes flow と同じ動き: 1.2 秒で 0.22 → 1 (40%) → 0.22、
 * 1 本ごとに 0.2 秒遅らせて左から右へ流れて見せる。[on] でないときは薄く止める
 */
@Composable
fun FlowArrow(on: Boolean, color: Color, modifier: Modifier = Modifier, width: Dp = 34.dp) {
    // Preview と画像化では止める (流れ続けると描画が落ち着かず、Roborazzi が待ち続ける)
    val animate = on && !LocalInspectionMode.current
    val t by if (animate) {
        rememberInfiniteTransition(label = "flow").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(CYCLE_MILLIS, easing = LinearEasing), RepeatMode.Restart),
            label = "flowPhase",
        )
    } else {
        remember { mutableFloatStateOf(0.45f) }
    }
    val off = flowOffColor()
    Canvas(modifier.size(width, width * (30f / 44f))) {
        val k = size.width / 44f
        val stroke = Stroke(width = 5.5f * k, cap = StrokeCap.Round, join = StrokeJoin.Round)
        for (i in 0 until 3) {
            val x = 5f + 12f * i
            val path = Path().apply {
                moveTo(x * k, 4f * k)
                lineTo((x + 11f) * k, 15f * k)
                lineTo(x * k, 26f * k)
            }
            val alpha = if (on) pulse(((t - DELAY_FRACTION * i) % 1f + 1f) % 1f) else 0.25f
            drawPath(path, color = if (on) color else off, alpha = alpha, style = stroke)
        }
    }
}

// ease-in-out は省いて直線で上げ下げする (1 本が短いので見た目は変わらない)
private fun pulse(phase: Float): Float =
    if (phase <= 0.4f) 0.22f + 0.78f * (phase / 0.4f) else 1f - 0.78f * ((phase - 0.4f) / 0.6f)

