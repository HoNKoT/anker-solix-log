package app.ankermonitor.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
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
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val storage = context.appContainer.storage
        val initial = storage.snapshot.first()
        provideContent {
            // updateAll() はセッションが生きている間 (約 45 秒) provideGlance を呼び直さないので、
            // 置いた直後の refreshNow やアプリからの反映を拾うには、合成の中で購読しておく必要がある
            val snapshot by storage.snapshot.collectAsState(initial)
            GlanceTheme {
                WidgetContent(snapshot)
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
private fun WidgetContent(snapshot: Snapshot) {
    val devices = snapshot.status?.devices.orEmpty()
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(16.dp)
            .padding(12.dp)
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
            else -> devices.forEach { DeviceRow(it) }
        }
        Spacer(GlanceModifier.defaultWeight())
        Text(text = footer(snapshot), style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp))
    }
}

@Composable
private fun DeviceRow(device: Device) {
    Row(modifier = GlanceModifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = device.displayName,
            maxLines = 1,
            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 13.sp),
            modifier = GlanceModifier.defaultWeight(),
        )
        Spacer(GlanceModifier.width(8.dp))
        if (device.hasReading) {
            Text(
                text = "${device.batteryPercent}%",
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold),
            )
            Spacer(GlanceModifier.width(6.dp))
            Text(
                text = flowMark(device.powerFlow),
                style = TextStyle(
                    color = if (device.powerFlow == PowerFlow.CHARGING) GlanceTheme.colors.primary else GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 12.sp,
                ),
            )
        } else {
            Text(text = device.stateLabel, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp))
        }
    }
}

private fun flowMark(flow: PowerFlow): String = when (flow) {
    PowerFlow.CHARGING -> "↑${flow.label}"
    PowerFlow.DISCHARGING -> "↓${flow.label}"
    else -> flow.label
}

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
