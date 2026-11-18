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
 * 矢印に出す入力 / 出力の W。ダッシュボード (updateCard) と同じく、機器どうしの給電も普通の入力 / 出力として足す
 * (集計用の [inputWatts] / [outputWatts] からは引かれているので、そのままだと相手から充電中なのに 0 W になる)
 */
val Device.flowInWatts: Int get() = ((powerIn ?: 0.0) + shared.orEmpty().filter { it.dir == "in" }.sumOf { it.w }).roundToInt()
val Device.flowOutWatts: Int get() = ((powerOut ?: 0.0) + shared.orEmpty().filter { it.dir == "out" }.sumOf { it.w }).roundToInt()

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

/** 機器どうしの USB 給電の言い方 (ダッシュボードと同じ)。例: 「SOLIX C2000 Gen 2 から給電中 92 W」 */
val Device.feedLabels: List<String>
    get() = shared.orEmpty().filter { it.w >= 1 }.map {
        val peer = it.peerName?.takeIf { n -> n.isNotBlank() } ?: it.peer
        val w = it.w.roundToInt()
        if (it.dir == "in") "$peer から給電中 $w W" else "$peer へ給電中 $w W"
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

/**
 * 機器どうしの給電 (Transfer) の W。履歴と同じく「相手が受け取った W」(recv) で数える
 * (98W 送って 92W 届いたなら、送り側も受け側も Transfer 92W)。入力・出力の W には含まれていない。
 */
val Device.transferWatts: Int
    get() = shared.orEmpty().sumOf { it.recv ?: it.w }.roundToInt()

/** 送り側のロス: 送った W − 相手が受け取った W (受け側では 0)。測定誤差で受け取りが多いときは 0 */
val Device.transferLossWatts: Int
    get() = shared.orEmpty().filter { it.dir == "out" }
        .sumOf { maxOf(0.0, (it.sent ?: it.w) - (it.recv ?: it.w)) }.roundToInt()

/** カードに出す Transfer の行。給電していなければ null。例: 「Transfer 送り出し 92 W (6 W loss)」 */
val Device.transferLabel: String?
    get() {
        val shares = shared.orEmpty()
        if (shares.isEmpty()) return null
        val dir = when {
            shares.all { it.dir == "out" } -> "送り出し "
            shares.all { it.dir == "in" } -> "受け取り "
            else -> ""
        }
        val loss = transferLossWatts
        return "Transfer $dir$transferWatts W" + if (loss >= 1) " ($loss W loss)" else ""
    }
