package app.ankermonitor.shared

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * バックエンド (anker_monitor.py) の `GET /status` の応答。
 * 形は `Manager.snapshot()` と、メニューバーアプリ (AnkerMonitor.swift) の `Status` / `Device` に合わせている。
 */
@Serializable
data class StatusResponse(
    val ok: Boolean = true,
    /** バックエンドの時刻 (UNIX 秒、小数あり) */
    val now: Double? = null,
    val devices: List<Device> = emptyList(),
)

@Serializable
data class Device(
    val id: String,
    val name: String? = null,
    val model: String? = null,
    @SerialName("model_label") val modelLabel: String? = null,
    val icon: String? = null,
    /** searching / connecting / connected / offline */
    val state: String? = null,
    // BLE 経由の値は四捨五入されずに来ることがある (クラウド経由だけ whole() で整数化される) ので Double で受ける
    val battery: Double? = null,
    @SerialName("power_in") val powerIn: Double? = null,
    @SerialName("power_out") val powerOut: Double? = null,
    /** CHARGING / DISCHARGING / IDLE / UNKNOWN */
    val charging: String? = null,
    val detail: String? = null,
    /** 値の出どころ: ble / cloud (未接続なら null) */
    val via: String? = null,
    val ports: List<Port>? = null,
    @SerialName("remaining_h") val remainingHours: Double? = null,
    /** 機器どうしの USB 給電 (バックエンドの find_sharing)。このぶんは power_in / power_out から引かれている */
    val shared: List<Share>? = null,
)

@Serializable
data class Share(
    val key: String,
    /** in = 相手から給電されている / out = 相手へ給電している */
    val dir: String,
    val w: Double = 0.0,
    val peer: String,
    @SerialName("peer_name") val peerName: String? = null,
)

@Serializable
data class Port(
    val key: String,
    val label: String? = null,
    /** in / out */
    val dir: String,
    val w: Double = 0.0,
)
