package app.ankermonitor.widget

import android.content.Context
import android.widget.RemoteViews
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.color.ColorProvider
import androidx.glance.unit.ColorProvider
import app.ankermonitor.R
import app.ankermonitor.shared.flowInWatts
import app.ankermonitor.shared.flowOutWatts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.RowScope
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import app.ankermonitor.MainActivity
import app.ankermonitor.appContainer
import app.ankermonitor.shared.Device
import app.ankermonitor.shared.PowerFlow
import app.ankermonitor.shared.Snapshot
import app.ankermonitor.shared.batteryPercent
import app.ankermonitor.shared.displayName
import app.ankermonitor.shared.hasReading
import app.ankermonitor.shared.label
import app.ankermonitor.shared.powerFlow
import app.ankermonitor.shared.stateLabel
import app.ankermonitor.work.StatusRefreshWorker
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AnkerWidget : GlanceAppWidget() {
    // 置かれた大きさで 1 行 / 2 行を切り替えるので、実際の大きさで合成する
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val storage = context.appContainer.storage
        val initial = storage.snapshot.first()
        provideContent {
            // updateAll() はセッションが生きている間 (約 45 秒) provideGlance を呼び直さないので、
            // 置いた直後の refreshNow やアプリからの反映を拾うには、合成の中で購読しておく必要がある
            val snapshot by storage.snapshot.collectAsState(initial)
            GlanceTheme {
                WidgetContent(snapshot, LocalSize.current)
            }
        }
    }
}

class AnkerWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = AnkerWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        // 置いた直後は前回の値しか無いので、15 分を待たずに 1 回読む
        StatusRefreshWorker.refreshNow(context)
    }
}

@Composable
private fun WidgetContent(snapshot: Snapshot, size: DpSize) {
    val devices = snapshot.status?.devices.orEmpty()
    // 2 行にすると 1 台あたり約 2 倍の高さを使うので、入らない高さでは 1 行に詰めて下の機器が切れないようにする
    val twoLine = size.height >= (PADDING * 2 + FOOTER_HEIGHT + TWO_LINE_ROW_HEIGHT * devices.size.coerceAtLeast(1))
    // 1 行でも 2 台ぶんと更新時刻は最小の高さ (80dp) に入らない。時刻より機器の値を残す
    val showFooter = twoLine || size.height >= (PADDING * 2 + FOOTER_HEIGHT + ONE_LINE_ROW_HEIGHT * devices.size)
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(16.dp)
            .padding(PADDING)
            .clickable(actionStartActivity<MainActivity>()),
    ) {
        when {
            snapshot.status == null -> Text(
                text = snapshot.problemMessage ?: "アプリを開いて接続先を設定してください",
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 13.sp),
            )
            devices.isEmpty() -> Text(
                text = "機器が登録されていません",
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 13.sp),
            )
            twoLine -> devices.forEach { TwoLineDeviceRow(it) }
            else -> devices.forEach { OneLineDeviceRow(it) }
        }
        if (showFooter) {
            Spacer(GlanceModifier.defaultWeight())
            Text(text = footer(snapshot), maxLines = 1, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp))
        }
    }
}

/** 1 行目に名前と充電状態、2 行目にダッシュボードと同じ「入力 W ››› 残量 ››› 出力 W」 */
@Composable
private fun TwoLineDeviceRow(device: Device) {
    Column(modifier = GlanceModifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            DeviceName(device)
            Spacer(GlanceModifier.width(6.dp))
            Text(
                text = if (device.hasReading) device.powerFlow.label else device.stateLabel,
                maxLines = 1,
                style = TextStyle(color = stateColor(device), fontSize = 11.sp),
            )
        }
        if (device.hasReading) FlowLine(device, wattSize = 12.sp, batterySize = 15.sp, gap = 3.dp)
    }
}

/** 低いサイズ用。名前を削ってでも W と残量を出す */
@Composable
private fun OneLineDeviceRow(device: Device) {
    Row(modifier = GlanceModifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        DeviceName(device)
        Spacer(GlanceModifier.width(4.dp))
        if (device.hasReading) {
            FlowLine(device, wattSize = 11.sp, batterySize = 14.sp, gap = 2.dp)
        } else {
            Text(text = device.stateLabel, maxLines = 1, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp))
        }
    }
}

// 重みを付けないので、狭いときに切れるのは名前の方 (W と残量は削らない)
@Composable
private fun FlowLine(device: Device, wattSize: TextUnit, batterySize: TextUnit, gap: Dp) {
    val inW = device.flowInWatts
    val outW = device.flowOutWatts
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "${inW}W",
            maxLines = 1,
            style = TextStyle(color = if (inW >= 1) InColor else OffColor, fontSize = wattSize, fontWeight = FontWeight.Bold),
        )
        Spacer(GlanceModifier.width(gap))
        FlowArrow(R.layout.widget_flow_in, on = inW >= 1)
        Spacer(GlanceModifier.width(gap))
        Text(
            text = "${device.batteryPercent}%",
            maxLines = 1,
            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = batterySize, fontWeight = FontWeight.Bold),
        )
        Spacer(GlanceModifier.width(gap))
        FlowArrow(R.layout.widget_flow_out, on = outW >= 1)
        Spacer(GlanceModifier.width(gap))
        Text(
            text = "${outW}W",
            maxLines = 1,
            style = TextStyle(color = if (outW >= 1) OutColor else OffColor, fontSize = wattSize, fontWeight = FontWeight.Bold),
        )
    }
}

/** 流れているときは ViewFlipper のコマ送りで左から右へ流し、0 W のときは薄い灰色で止める (ダッシュボードの .farrow) */
@Composable
private fun FlowArrow(layoutId: Int, on: Boolean) {
    if (on) {
        AndroidRemoteViews(RemoteViews(LocalContext.current.packageName, layoutId))
    } else {
        Image(provider = ImageProvider(R.drawable.widget_flow_off), contentDescription = null)
    }
}

@Composable
private fun RowScope.DeviceName(device: Device) {
    Text(
        text = device.displayName,
        maxLines = 1,
        style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 13.sp),
        modifier = GlanceModifier.defaultWeight(),
    )
}

@Composable
private fun stateColor(device: Device): ColorProvider = when {
    !device.hasReading -> GlanceTheme.colors.onSurfaceVariant
    device.powerFlow == PowerFlow.CHARGING -> InColor
    device.powerFlow == PowerFlow.DISCHARGING -> OutColor
    else -> GlanceTheme.colors.onSurfaceVariant
}

// ダッシュボード (dashboard.html) の --in / --out / --sub。res/values(-night)/flow_colors.xml と同じ値
private val InColor = ColorProvider(day = Color(0xFF1F9D55), night = Color(0xFF34C759))
private val OutColor = ColorProvider(day = Color(0xFFE8590C), night = Color(0xFFFF9F0A))
private val OffColor = ColorProvider(day = Color(0xFF6E6E73), night = Color(0xFF9A9AA0))

private val PADDING = 12.dp
private val FOOTER_HEIGHT = 16.dp
private val TWO_LINE_ROW_HEIGHT = 44.dp
private val ONE_LINE_ROW_HEIGHT = 26.dp

private fun footer(snapshot: Snapshot): String {
    val fmt = SimpleDateFormat("HH:mm", Locale.JAPAN)
    val fetched = snapshot.fetchedAtMillis
    val failedSince = snapshot.problemAtMillis?.takeIf { snapshot.problemMessage != null && (fetched == null || it > fetched) }
    return when {
        fetched == null -> ""
        failedSince != null -> "${fmt.format(Date(fetched))} の値 (更新できません)"
        else -> "${fmt.format(Date(fetched))} 更新"
    }
}
