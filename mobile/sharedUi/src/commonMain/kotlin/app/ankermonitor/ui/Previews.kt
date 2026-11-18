package app.ankermonitor.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.tooling.preview.Preview
import app.ankermonitor.shared.Connection
import app.ankermonitor.shared.Device
import app.ankermonitor.shared.MonitorState
import app.ankermonitor.shared.Port
import app.ankermonitor.shared.Problem
import app.ankermonitor.shared.DailyResponse
import app.ankermonitor.shared.DailyRow
import app.ankermonitor.shared.DailySettings
import app.ankermonitor.shared.HourlyWeather
import app.ankermonitor.shared.Share
import app.ankermonitor.shared.StatusResponse
import app.ankermonitor.shared.WeatherCurrent
import app.ankermonitor.shared.WeatherDay
import app.ankermonitor.shared.WeatherHour
import app.ankermonitor.shared.WeatherNow
import app.ankermonitor.shared.summarizeEnergy

/** Preview とスクリーンショットテスト用の見本 (値は anker_monitor.py の /status の形に合わせている) */
object PreviewSamples {
    val connection = Connection("http://my-mac.local:19877", "http://my-mac.tailnet.ts.net:19877", "token")

    // 2026-11-15 12:34:56 (UTC)。表示は端末のタイムゾーンで変わる
    const val FETCHED_AT = 1_763_210_096_000L

    val charging = Device(
        id = "c1000-1", name = "リビングの C1000", model = "c1000", modelLabel = "C1000(X)", icon = "box",
        state = "connected", battery = 36.0, powerIn = 212.0, powerOut = 48.0, charging = "CHARGING", via = "ble",
        ports = listOf(Port("solar", "ソーラー", "in", 200.0), Port("usb_c1", "USB-C1", "out", 30.0, shared = true)),
        remainingHours = 3.4,
        // デスクの C300 へ USB-C で 30W 送り、27W 届いている (find_sharing の形)
        shared = listOf(Share("usb_c1", "out", 30.0, "c300-1", "デスクの C300", sent = 30.0, recv = 27.0)),
    )
    val discharging = Device(
        id = "c300-1", name = "デスクの C300", modelLabel = "C300(X)", icon = "compact",
        state = "connected", battery = 82.0, powerIn = 0.0, powerOut = 65.0, charging = "DISCHARGING", via = "cloud",
        remainingHours = 3.5,
        shared = listOf(Share("in_usb", "in", 27.0, "c1000-1", "リビングの C1000", sent = 30.0, recv = 27.0)),
    )
    val searching = Device(
        id = "f3800-1", name = "F3800", modelLabel = "F3800", icon = "large",
        state = "searching", charging = "UNKNOWN", detail = "",
    )
    val offline = Device(
        id = "c800-1", name = "車の C800", modelLabel = "C800(X)", icon = "box",
        state = "offline", charging = "UNKNOWN", detail = "BleakError: Device not found",
    )

    val weather = WeatherNow(
        place = "見本の町",
        today = WeatherDay(code = 1, tmax = 18.4, tmin = 9.2, precip = 0.0, sunHours = 6.3),
        now = WeatherCurrent(code = 1, temp = 15.6),
    )

    val status = StatusResponse(devices = listOf(charging, discharging, searching, offline), weather = weather)

    // 3 時間前から 1 時間ごと。4 枚目が「いま」(13 時)
    val hourly = HourlyWeather(
        place = "見本の町",
        hours = listOf(
            Triple(10, 1, 12.1), Triple(11, 1, 13.8), Triple(12, 2, 15.0), Triple(13, 1, 15.6), Triple(14, 2, 16.2),
            Triple(15, 3, 15.9), Triple(16, 3, 14.7), Triple(17, 61, 13.2), Triple(18, 61, 12.4), Triple(19, 3, 11.8),
            Triple(20, 0, 11.0), Triple(21, 0, 10.3),
        ).map { (h, code, temp) ->
            WeatherHour(
                t = "2026-11-17T${h.toString().padStart(2, '0')}:00", code = code, temp = temp,
                pop = if (code == 61) 60 else if (code == 3) 20 else 0, day = if (h in 6..16) 1 else 0, now = h == 13,
            )
        },
    )

    /** 2026-08 から記録があり、2026-09 は記録なし。11 月は C1000 → C300 の給電あり */
    val daily: DailyResponse = run {
        val rows = mutableListOf<DailyRow>()
        val weather = mutableMapOf<String, WeatherDay>()
        fun day(d: String, solar: Double, out300: Double, xfer: Double, code: Int, tmax: Double, tmin: Double, sun: Double) {
            rows += DailyRow(dev = "c1000-1", day = d, whIn = solar + 120, whOut = 180.0, whSolar = solar, whLoss = 40.0,
                whXferOut = xfer, whXferLossOut = xfer * 0.08)
            rows += DailyRow(dev = "c300-1", day = d, whIn = 0.0, whOut = out300, whLoss = 12.0,
                whXferIn = xfer, whXferLossIn = xfer * 0.08)
            weather[d] = WeatherDay(code = code, tmax = tmax, tmin = tmin, sunHours = sun)
        }
        day("2026-08-30", 1800.0, 300.0, 0.0, 0, 33.0, 25.0, 11.2)
        day("2026-08-31", 1500.0, 320.0, 0.0, 1, 32.0, 24.0, 9.8)
        day("2026-10-30", 900.0, 260.0, 0.0, 3, 19.0, 12.0, 2.4)
        day("2026-10-31", 1100.0, 280.0, 0.0, 1, 20.0, 11.0, 7.1)
        day("2026-11-13", 1250.0, 310.0, 210.0, 0, 18.0, 8.0, 8.6)
        day("2026-11-14", 300.0, 290.0, 150.0, 61, 14.0, 10.0, 0.4)
        day("2026-11-15", 0.0, 240.0, 0.0, 63, 12.0, 9.0, 0.0)
        day("2026-11-16", 1320.0, 330.0, 270.0, 1, 17.0, 7.0, 8.9)
        day("2026-11-17", 640.0, 150.0, 120.0, 2, 18.0, 9.0, 4.1)
        DailyResponse(rows = rows, settings = DailySettings(pricePerKwh = 39.5), weather = weather, tzOffset = 9 * 3600)
    }

    val energyReport = summarizeEnergy(daily, currentMonth = "2026-11")
}

@Preview
@Composable
fun MonitorScreenPreview() = AnkerTheme(dark = false, dynamicColor = false) {
    MonitorScreen(
        connection = PreviewSamples.connection,
        state = MonitorState(PreviewSamples.status, PreviewSamples.FETCHED_AT, problem = null, loading = false),
        onOpenSettings = {},
    )
}

@Preview
@Composable
fun MonitorScreenDarkPreview() = AnkerTheme(dark = true, dynamicColor = false) {
    MonitorScreen(
        connection = PreviewSamples.connection,
        state = MonitorState(PreviewSamples.status, PreviewSamples.FETCHED_AT, problem = null, loading = false),
        onOpenSettings = {},
    )
}

@Preview
@Composable
fun MonitorScreenUnreachablePreview() = AnkerTheme(dark = false, dynamicColor = false) {
    MonitorScreen(
        connection = PreviewSamples.connection,
        state = MonitorState(
            StatusResponse(devices = listOf(PreviewSamples.charging)),
            PreviewSamples.FETCHED_AT,
            problem = Problem.Unreachable(null),
            loading = false,
        ),
        onOpenSettings = {},
    )
}

@Preview
@Composable
fun MonitorScreenEmptyPreview() = AnkerTheme(dark = false, dynamicColor = false) {
    MonitorScreen(
        connection = PreviewSamples.connection,
        state = MonitorState(StatusResponse(devices = emptyList()), PreviewSamples.FETCHED_AT, problem = null, loading = false),
        onOpenSettings = {},
    )
}

@Preview
@Composable
fun SetupScreenPreview() = AnkerTheme(dark = false, dynamicColor = false) {
    SetupScreen(initial = null, onSave = { _, _, _ -> SaveResult.Saved }, onDone = {}, onCancel = null)
}

@Preview
@Composable
fun SetupScreenFilledPreview() = AnkerTheme(dark = false, dynamicColor = false) {
    SetupScreen(initial = PreviewSamples.connection, onSave = { _, _, _ -> SaveResult.Saved }, onDone = {}, onCancel = {})
}

@Preview
@Composable
fun MonitorScreenHourlyPreview() = AnkerTheme(dark = false, dynamicColor = false) {
    MonitorScreen(
        connection = PreviewSamples.connection,
        state = MonitorState(PreviewSamples.status, PreviewSamples.FETCHED_AT, problem = null, loading = false),
        onOpenSettings = {},
        hourly = HourlyState.Loaded(PreviewSamples.hourly),
    )
}

@Preview
@Composable
fun EnergyScreenPreview() = AnkerTheme(dark = false, dynamicColor = false) {
    MonitorScreen(
        connection = PreviewSamples.connection,
        state = MonitorState(PreviewSamples.status, PreviewSamples.FETCHED_AT, problem = null, loading = false),
        onOpenSettings = {},
        energy = EnergyState.Loaded(PreviewSamples.energyReport),
        initialTab = HomeTab.ENERGY,
    )
}

@Preview
@Composable
fun EnergyScreenOpenPreview() = AnkerTheme(dark = false, dynamicColor = false) {
    androidx.compose.material3.Surface {
        EnergyContent(EnergyState.Loaded(PreviewSamples.energyReport), initialOpen = setOf("2026-11"))
    }
}

@Preview
@Composable
fun EnergyScreenOpenDarkPreview() = AnkerTheme(dark = true, dynamicColor = false) {
    androidx.compose.material3.Surface {
        EnergyContent(EnergyState.Loaded(PreviewSamples.energyReport), initialOpen = setOf("2026-11"))
    }
}

/** スクリーンショットテストで回す一覧 (名前はファイル名になる) */
// 画像にする側 (Roborazzi) は描画が落ち着くのを待つので、流れ続ける矢印 (FlowArrow) は Preview と同じく止めて描く
val screenshotPreviews: List<Pair<String, @Composable () -> Unit>> = listOf<Pair<String, @Composable () -> Unit>>(
    "monitor" to { MonitorScreenPreview() },
    "monitor_dark" to { MonitorScreenDarkPreview() },
    "monitor_unreachable" to { MonitorScreenUnreachablePreview() },
    "monitor_empty" to { MonitorScreenEmptyPreview() },
    "monitor_hourly" to { MonitorScreenHourlyPreview() },
    "energy" to { EnergyScreenPreview() },
    "energy_open" to { EnergyScreenOpenPreview() },
    "energy_open_dark" to { EnergyScreenOpenDarkPreview() },
    "setup" to { SetupScreenPreview() },
    "setup_filled" to { SetupScreenFilledPreview() },
).map { (name, content) ->
    name to @Composable { CompositionLocalProvider(LocalInspectionMode provides true) { content() } }
}
