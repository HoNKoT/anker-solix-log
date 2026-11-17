package app.ankermonitor.shared

import kotlin.math.abs
import kotlin.math.roundToLong

// commonMain には String.format が無いので、ダッシュボードの toFixed / toLocaleString と同じ見た目を自前で作る

/** 小数 [digits] 桁で四捨五入した文字列 (JS の toFixed 相当)。例: 1234.5 → "1234.50" */
fun fixed(value: Double, digits: Int): String {
    var scale = 1L
    repeat(digits) { scale *= 10 }
    val scaled = (abs(value) * scale).roundToLong()
    val sign = if (value < 0 && scaled != 0L) "-" else ""
    val whole = scaled / scale
    if (digits == 0) return "$sign$whole"
    val frac = (scaled % scale).toString().padStart(digits, '0')
    return "$sign$whole.$frac"
}

/** Wh → kWh の小数 2 桁 (単位なし)。例: 1234.0 → "1.23" */
fun kwh(wh: Double): String = fixed(wh / 1000, 2)

/** 円の整数を 3 桁区切りで (単位なし)。例: 12345.6 → "12,346" */
fun yen(value: Double): String {
    val n = value.roundToLong()
    val digits = abs(n).toString()
    val grouped = digits.reversed().chunked(3).joinToString(",").reversed()
    return if (n < 0) "-$grouped" else grouped
}

/** 整数なら小数点を付けない (JS の数値の文字列化と同じ)。例: 5.0 → "5"、5.2 → "5.2" */
fun plain(value: Double): String {
    val r = value.roundToLong()
    return if (r.toDouble() == value) r.toString() else value.toString()
}
