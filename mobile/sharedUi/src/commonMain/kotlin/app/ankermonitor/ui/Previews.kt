package app.ankermonitor.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import app.ankermonitor.shared.Connection
import app.ankermonitor.shared.Device
import app.ankermonitor.shared.MonitorState
import app.ankermonitor.shared.Port
import app.ankermonitor.shared.Problem
import app.ankermonitor.shared.StatusResponse

/** Preview とスクリーンショットテスト用の見本 (値は anker_monitor.py の /status の形に合わせている) */
object PreviewSamples {
    val connection = Connection("http://100.64.1.2:19877", "token")

    // 2026-11-15 12:34:56 (UTC)。表示は端末のタイムゾーンで変わる
    const val FETCHED_AT = 1_763_210_096_000L

    val charging = Device(
        id = "c1000-1", name = "リビングの C1000", model = "c1000", modelLabel = "C1000(X)", icon = "box",
        state = "connected", battery = 36.0, powerIn = 212.0, powerOut = 48.0, charging = "CHARGING", via = "ble",
        ports = listOf(Port("solar", "ソーラー", "in", 200.0), Port("usb_c1", "USB-C1", "out", 30.0)),
        remainingHours = 3.4,
    )
    val discharging = Device(
        id = "c300-1", name = "デスクの C300", modelLabel = "C300(X)", icon = "compact",
        state = "connected", battery = 82.0, powerIn = 0.0, powerOut = 65.0, charging = "DISCHARGING", via = "cloud",
        remainingHours = 3.5,
    )
    val searching = Device(
        id = "f3800-1", name = "F3800", modelLabel = "F3800", icon = "large",
        state = "searching", charging = "UNKNOWN", detail = "",
    )
    val offline = Device(
        id = "c800-1", name = "車の C800", modelLabel = "C800(X)", icon = "box",
        state = "offline", charging = "UNKNOWN", detail = "BleakError: Device not found",
    )

    val status = StatusResponse(devices = listOf(charging, discharging, searching, offline))
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
    SetupScreen(initial = null, onSave = { _, _ -> SaveResult.Saved }, onDone = {}, onCancel = null)
}

@Preview
@Composable
fun SetupScreenFilledPreview() = AnkerTheme(dark = false, dynamicColor = false) {
    SetupScreen(initial = PreviewSamples.connection, onSave = { _, _ -> SaveResult.Saved }, onDone = {}, onCancel = {})
}

/** スクリーンショットテストで回す一覧 (名前はファイル名になる) */
val screenshotPreviews: List<Pair<String, @Composable () -> Unit>> = listOf(
    "monitor" to { MonitorScreenPreview() },
    "monitor_dark" to { MonitorScreenDarkPreview() },
    "monitor_unreachable" to { MonitorScreenUnreachablePreview() },
    "monitor_empty" to { MonitorScreenEmptyPreview() },
    "setup" to { SetupScreenPreview() },
    "setup_filled" to { SetupScreenFilledPreview() },
)
