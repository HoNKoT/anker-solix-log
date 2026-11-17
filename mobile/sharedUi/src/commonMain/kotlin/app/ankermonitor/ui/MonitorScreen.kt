package app.ankermonitor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.ankermonitor.shared.Connection
import app.ankermonitor.shared.Device
import app.ankermonitor.shared.MonitorState
import app.ankermonitor.shared.PowerFlow
import app.ankermonitor.shared.badge
import app.ankermonitor.shared.batteryPercent
import app.ankermonitor.shared.displayName
import app.ankermonitor.shared.feedLabels
import app.ankermonitor.shared.hasReading
import app.ankermonitor.shared.inputWatts
import app.ankermonitor.shared.label
import app.ankermonitor.shared.outputWatts
import app.ankermonitor.shared.powerFlow
import app.ankermonitor.shared.stateLabel
import app.ankermonitor.shared.transferLabel
import kotlin.math.roundToInt

/** 画面下のタブ。「いま」はダッシュボードの「いま」タブ、「月ごと」は履歴タブの日ごとの電力量 */
enum class HomeTab(val label: String) { NOW("いま"), ENERGY("月ごと") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonitorScreen(
    connection: Connection,
    state: MonitorState,
    onOpenSettings: () -> Unit,
    hourly: HourlyState? = null,
    onToggleWeather: () -> Unit = {},
    energy: EnergyState = EnergyState.Loading,
    onLoadEnergy: () -> Unit = {},
    initialTab: HomeTab = HomeTab.NOW,
) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    LaunchedEffect(tab) { if (tab == HomeTab.ENERGY) onLoadEnergy() }
    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Anker Monitor") },
                    actions = {
                        if (tab == HomeTab.NOW) {
                            state.status?.weather.badge()?.let { WeatherBadgeButton(it, open = hourly != null, onClick = onToggleWeather) }
                        }
                        TextButton(onClick = onOpenSettings) { Text("接続先") }
                    },
                )
                PrimaryTabRow(selectedTabIndex = tab.ordinal) {
                    HomeTab.entries.forEach { t ->
                        Tab(selected = tab == t, onClick = { tab = t }, text = { Text(t.label) })
                    }
                }
            }
        },
    ) { padding ->
        when (tab) {
            HomeTab.NOW -> NowContent(connection, state, hourly, Modifier.fillMaxSize().padding(padding))
            HomeTab.ENERGY -> EnergyContent(energy, Modifier.fillMaxSize().padding(padding))
        }
    }
}

@Composable
private fun NowContent(connection: Connection, state: MonitorState, hourly: HourlyState?, modifier: Modifier) {
    val devices = state.status?.devices.orEmpty()
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        hourly?.let { item(key = "hourly") { HourlyStrip(it) } }
        state.problem?.let { problem ->
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(
                        problem.message,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
        when {
            state.loading -> item {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            state.status != null && devices.isEmpty() -> item {
                Text("機器が登録されていません。記録している Mac のダッシュボードで追加してください。")
            }
            else -> items(devices, key = { it.id }) { DeviceCard(it) }
        }
        item {
            val fetched = state.fetchedAtMillis?.let { formatClock(it, withSeconds = true) }
            Text(
                text = connection.url + (fetched?.let { "  ·  $it 更新" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DeviceCard(device: Device) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(device.displayName, style = MaterialTheme.typography.titleMedium)
                    device.modelLabel?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                ConnectionChip(device)
            }
            if (device.hasReading) {
                val pct = device.batteryPercent ?: 0
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("$pct%", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(12.dp))
                    val flow = device.powerFlow
                    Text(
                        flow.label,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (flow == PowerFlow.CHARGING) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                LinearProgressIndicator(progress = { pct.coerceIn(0, 100) / 100f }, modifier = Modifier.fillMaxWidth())
                Text("入力 ${device.inputWatts} W   出力 ${device.outputWatts} W", style = MaterialTheme.typography.bodyLarge)
                device.transferLabel?.let {
                    Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.tertiary)
                }
                device.feedLabels.forEach {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
                device.remainingHours?.let {
                    Text(
                        "残り時間の目安 ${(it * 10).roundToInt() / 10.0} 時間",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                device.detail?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ConnectionChip(device: Device) {
    val via = when (device.via) {
        "ble" -> " (Bluetooth)"
        "cloud" -> " (クラウド)"
        else -> ""
    }
    AssistChip(onClick = {}, label = { Text(device.stateLabel + if (device.hasReading) via else "") })
}
