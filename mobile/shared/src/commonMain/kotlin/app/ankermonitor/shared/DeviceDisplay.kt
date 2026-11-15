package app.ankermonitor.shared

import kotlin.math.roundToInt

enum class PowerFlow { CHARGING, DISCHARGING, IDLE, UNKNOWN }

/** 値が取れている (接続済みで残量がある)。メニューバーアプリの `ok` と同じ条件 */
val Device.hasReading: Boolean get() = state == "connected" && battery != null

val Device.displayName: String get() = name?.takeIf { it.isNotBlank() } ?: id

val Device.batteryPercent: Int? get() = battery?.roundToInt()
val Device.inputWatts: Int get() = (powerIn ?: 0.0).roundToInt()
val Device.outputWatts: Int get() = (powerOut ?: 0.0).roundToInt()

/**
 * 充電 / 放電の向き。バックエンドの `charging` を優先し、UNKNOWN のときだけ
 * メニューバーアプリのアイコンと同じ「1W 以上で、多い方」の判定にする。
 */
val Device.powerFlow: PowerFlow
    get() {
        if (!hasReading) return PowerFlow.UNKNOWN
        when (charging) {
            "CHARGING" -> return PowerFlow.CHARGING
            "DISCHARGING" -> return PowerFlow.DISCHARGING
            "IDLE" -> return PowerFlow.IDLE
        }
        val pin = powerIn ?: 0.0
        val pout = powerOut ?: 0.0
        return when {
            pin >= 1 && pin > pout -> PowerFlow.CHARGING
            pout >= 1 && pout > pin -> PowerFlow.DISCHARGING
            else -> PowerFlow.IDLE
        }
    }

/** 値が無いときの状態の言い方 (メニューバーアプリのツールチップと同じ) */
val Device.stateLabel: String
    get() = when (state) {
        "connected" -> if (battery == null) "値を待っています…" else "接続済み"
        "connecting" -> "接続中…"
        "searching" -> "探しています…"
        else -> "オフライン"
    }

val PowerFlow.label: String
    get() = when (this) {
        PowerFlow.CHARGING -> "充電中"
        PowerFlow.DISCHARGING -> "放電中"
        PowerFlow.IDLE -> "待機"
        PowerFlow.UNKNOWN -> "--"
    }
