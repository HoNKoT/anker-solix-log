package app.ankermonitor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ankermonitor.shared.DayEnergy
import app.ankermonitor.shared.EnergyReport
import app.ankermonitor.shared.MonthEnergy
import app.ankermonitor.shared.MonthEntry
import app.ankermonitor.shared.dayLabel
import app.ankermonitor.shared.fixed
import app.ankermonitor.shared.isWeekend
import app.ankermonitor.shared.kwh
import app.ankermonitor.shared.plain
import app.ankermonitor.shared.weatherIcon
import app.ankermonitor.shared.yen
import kotlin.math.roundToInt

/** はじめに出す月の数と、「さらに前の月」で増やす数 (ダッシュボードと同じ 3 か月ずつ) */
private const val MONTH_STEP = 3

@Composable
fun EnergyContent(
    state: EnergyState,
    modifier: Modifier = Modifier,
    initialOpen: Set<String> = emptySet(),
) {
    when (state) {
        EnergyState.Loading -> Box(modifier.padding(32.dp), contentAlignment = Alignment.TopCenter) { CircularProgressIndicator() }
        is EnergyState.Failed -> Box(modifier.padding(16.dp)) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(state.message, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(16.dp))
            }
        }
        is EnergyState.Loaded -> EnergyList(state.report, modifier, initialOpen)
    }
}

@Composable
private fun EnergyList(report: EnergyReport, modifier: Modifier, initialOpen: Set<String>) {
    var shown by rememberSaveable { mutableStateOf(MONTH_STEP) }
    // 開いている月は画面の中だけで覚える (保存はしない)
    var open by remember { mutableStateOf(initialOpen) }
    val rest = report.months.size - shown
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(report.months.take(shown), key = { it.key }) { m ->
            MonthCard(m, report, isOpen = m.key in open) {
                open = if (m.key in open) open - m.key else open + m.key
            }
        }
        if (rest > 0) {
            item(key = "more") {
                OutlinedButton(onClick = { shown += MONTH_STEP }, modifier = Modifier.fillMaxWidth()) {
                    Text("さらに前の月を表示 (あと $rest か月)")
                }
            }
        }
        item(key = "note") {
            Text(
                "全機器の合計。節約額 = ソーラーで賄えた出力 kWh × 単価 ${plain(report.pricePerKwh)} 円/kWh。" +
                    "ソーラー入力は貯め続け、出力 (Transfer は含まない) はその貯めから使います。" +
                    "Transfer は機器どうしの給電で、入力・出力には含めません。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MonthCard(entry: MonthEntry, report: EnergyReport, isOpen: Boolean, onToggle: () -> Unit) {
    val e = entry.energy
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.then(if (e != null) Modifier.clickable(onClick = onToggle) else Modifier).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (isOpen && e != null) "▾" else "▸", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "${entry.year}年${entry.month}月",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 6.dp),
                )
                if (e == null) {
                    Text(
                        "記録はありません",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }
            if (e != null) {
                MonthSummary(e, report)
                if (isOpen) DayTable(e, report)
            }
        }
    }
}

@Composable
private fun MonthSummary(e: MonthEnergy, report: EnergyReport) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Stat(yen(report.yenOf(e.whSaved)), "円", "節約 (ソーラーで賄えた ${kwh(e.whSaved)} kWh)", big = true)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Stat(kwh(e.whIn), "kWh", "入力 (うちソーラー ${kwh(e.whSolar)} kWh)", Modifier.weight(1f))
            Stat(kwh(e.whOut), "kWh", "出力 (≒ ${yen(report.yenOf(e.whOut))} 円)", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (e.hasTransfer) {
                val loss = if (e.whTransferLoss >= 0.05) " (${kwh(e.whTransferLoss)} kWh loss)" else ""
                Stat(kwh(e.whTransfer), "kWh", "Transfer$loss", Modifier.weight(1f))
            }
            Stat(kwh(e.whLoss), "kWh", "待機・変換ロス (推定)", Modifier.weight(1f))
            if (!e.hasTransfer) {
                e.sunHours?.let { Stat(it.roundToInt().toString(), "時間", "日照 (天気の記録から)", Modifier.weight(1f)) }
                    ?: Box(Modifier.weight(1f))
            }
        }
        if (e.hasTransfer) {
            e.sunHours?.let { Stat(it.roundToInt().toString(), "時間", "日照 (天気の記録から)") }
        }
    }
}

/** 数値の横に小さく単位を付け、その下に説明 */
@Composable
private fun Stat(value: String, unit: String, caption: String, modifier: Modifier = Modifier, big: Boolean = false) {
    Column(modifier) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(value) }
                withStyle(SpanStyle(fontSize = MaterialTheme.typography.labelSmall.fontSize)) { append(" $unit") }
            },
            style = if (big) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
            color = if (big) MaterialTheme.colorScheme.primary else Color.Unspecified,
        )
        Text(caption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// 日の表の列の幅 (日付の列だけ広い)。Transfer の列は、その月に給電があったときだけ出す
private const val DAY_COL = 1.5f
private const val NUM_COL = 1f
// ロスの「(0.010 loss)」が 1 行に収まる幅
private const val XFER_COL = 1.4f

@Composable
private fun DayTable(e: MonthEnergy, report: EnergyReport) {
    val hasX = e.hasTransfer
    Column {
        HorizontalDivider()
        Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.Bottom) {
            HeaderCell("日", DAY_COL, TextAlign.Start)
            HeaderCell("入力\nkWh", NUM_COL)
            HeaderCell("出力\nkWh", NUM_COL)
            if (hasX) HeaderCell("Transfer\nkWh", XFER_COL)
            HeaderCell("ソーラー\nkWh", NUM_COL)
            HeaderCell("節約", NUM_COL)
        }
        HorizontalDivider()
        e.days.forEach { d -> DayRow(d, report, hasX) }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.HeaderCell(text: String, weight: Float, align: TextAlign = TextAlign.End) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = align,
        modifier = Modifier.weight(weight),
    )
}

@Composable
private fun DayRow(d: DayEnergy, report: EnergyReport, hasX: Boolean) {
    Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(DAY_COL)) {
            Text(
                dayLabel(d.day),
                style = MaterialTheme.typography.bodySmall,
                color = if (isWeekend(d.day)) MaterialTheme.colorScheme.error else Color.Unspecified,
            )
            d.weather?.let { w ->
                val t = "${w.tmax?.roundToInt() ?: "-"}/${w.tmin?.roundToInt() ?: "-"}℃"
                Text(
                    "${weatherIcon(w.code).emoji} $t",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        NumCell(kwh(d.whIn), MaterialTheme.colorScheme.primary)
        NumCell(kwh(d.whOut), MaterialTheme.colorScheme.tertiary)
        if (hasX) {
            Column(Modifier.weight(XFER_COL), horizontalAlignment = Alignment.End) {
                Text(kwh(d.whTransfer), style = MaterialTheme.typography.bodySmall)
                // ロスは数 Wh のことが多く、小数 2 桁の kWh だと 0.00 になるので 3 桁で出す
                if (d.whTransferLoss >= 0.5) {
                    Text(
                        "(${fixed(d.whTransferLoss / 1000, 3)} loss)",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        NumCell(kwh(d.whSolar))
        Text(
            buildAnnotatedString {
                append(yen(report.yenOf(d.whSaved)))
                withStyle(SpanStyle(fontSize = MaterialTheme.typography.labelSmall.fontSize)) { append("円") }
            },
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(NUM_COL),
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.NumCell(text: String, color: Color = Color.Unspecified) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color, textAlign = TextAlign.End, modifier = Modifier.weight(NUM_COL))
}
