package app.ankermonitor.shared

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.min

/** `GET /api/daily` の応答 (anker_monitor.py の savings_rows / weather_rows / load_settings) */
@Serializable
data class DailyResponse(
    val rows: List<DailyRow> = emptyList(),
    val settings: DailySettings = DailySettings(),
    /** 日付 "YYYY-MM-DD" → その日の天気。場所が未設定なら空 */
    val weather: Map<String, WeatherDay> = emptyMap(),
    /** 記録している Mac の UTC からのずれ (秒)。日付はこの時刻で区切られている */
    @SerialName("tz_offset") val tzOffset: Int = 0,
)

@Serializable
data class DailySettings(
    @SerialName("price_per_kwh") val pricePerKwh: Double = 39.5,
)

/** 機器ごと・日ごとの電力量 (Wh)。null は 0 として受ける (ダッシュボードの `x[k]||0` と同じ) */
@Serializable
data class DailyRow(
    val dev: String = "",
    val day: String,
    @SerialName("wh_in") val whIn: Double = 0.0,
    @SerialName("wh_out") val whOut: Double = 0.0,
    @SerialName("wh_solar") val whSolar: Double = 0.0,
    @SerialName("wh_ac") val whAc: Double = 0.0,
    @SerialName("wh_saved") val whSaved: Double = 0.0,
    @SerialName("wh_loss") val whLoss: Double = 0.0,
    @SerialName("wh_xfer_in") val whXferIn: Double = 0.0,
    @SerialName("wh_xfer_out") val whXferOut: Double = 0.0,
    @SerialName("wh_xfer_loss_in") val whXferLossIn: Double = 0.0,
    @SerialName("wh_xfer_loss_out") val whXferLossOut: Double = 0.0,
)

/** 1 日ぶん (対象の機器を足したもの)。値は Wh */
data class DayEnergy(
    val day: String,
    val whIn: Double,
    val whOut: Double,
    val whSolar: Double,
    /** ソーラーで賄えた出力 (全期間の貯めから計算し直した値。バックエンドの wh_saved は使わない) */
    val whSaved: Double,
    val whLoss: Double,
    val whTransfer: Double,
    val whTransferLoss: Double,
    val weather: WeatherDay?,
)

/** 1 か月ぶんの合計と、その月の日 (新しい日から) */
data class MonthEnergy(
    val whIn: Double,
    val whOut: Double,
    val whSolar: Double,
    val whSaved: Double,
    val whLoss: Double,
    val whTransfer: Double,
    val whTransferLoss: Double,
    /** 天気の記録がある日の日照の合計。1 日も無ければ null */
    val sunHours: Double?,
    val days: List<DayEnergy>,
) {
    /** 月の Transfer が 0.05 Wh 以上なら、日の表に Transfer の列を出す */
    val hasTransfer: Boolean get() = whTransfer >= 0.05
}

/** 一覧の 1 行。記録の無い月は [energy] が null (「記録はありません」) */
data class MonthEntry(val key: String, val year: Int, val month: Int, val energy: MonthEnergy?)

data class EnergyReport(
    /** 今月から、記録のある一番古い月まで (新しい順) */
    val months: List<MonthEntry>,
    val pricePerKwh: Double,
) {
    fun yenOf(wh: Double): Double = wh * pricePerKwh / 1000
}

/**
 * dashboard.html の renderDaily() と同じまとめ方。
 * [rows] は全期間 (from=0) を渡す。ソーラーの貯めを記録の最初から積み上げるため。
 * [currentMonth] は "YYYY-MM" (記録している Mac の時刻での今月)。
 * [allDevices] = true なら全機器の合計 (Transfer は送り側の値だけ数える)、false なら 1 台ぶん。
 */
fun summarizeEnergy(
    response: DailyResponse,
    currentMonth: String,
    allDevices: Boolean = true,
): EnergyReport {
    class Acc {
        var whIn = 0.0; var whOut = 0.0; var whSolar = 0.0; var whLoss = 0.0
        var xIn = 0.0; var xOut = 0.0; var xlIn = 0.0; var xlOut = 0.0
    }
    val byDay = mutableMapOf<String, Acc>()
    for (r in response.rows) {
        val m = byDay.getOrPut(r.day) { Acc() }
        m.whIn += r.whIn; m.whOut += r.whOut; m.whSolar += r.whSolar; m.whLoss += r.whLoss
        m.xIn += r.whXferIn; m.xOut += r.whXferOut; m.xlIn += r.whXferLossIn; m.xlOut += r.whXferLossOut
    }
    // 節約: ソーラー入力を対象の機器全体で貯め続け、出力はその貯めから使えた分だけ数える。
    // 機器ごとに貯めると、ソーラーが入る機器と出力する機器が違うとき節約がほぼ 0 になるため
    var credit = 0.0
    val days = byDay.keys.sorted().map { day ->
        val m = byDay.getValue(day)
        credit += m.whSolar
        val saved = min(m.whOut, credit)
        credit -= saved
        // 全機器では送り側と受け側に同じ量が入っているので片側だけ数える (送り側の記録が無ければ受け側)
        val (xt, xl) = when {
            !allDevices -> (m.xIn + m.xOut) to (m.xlIn + m.xlOut)
            m.xOut > 0 -> m.xOut to m.xlOut
            else -> m.xIn to m.xlIn
        }
        DayEnergy(day, m.whIn, m.whOut, m.whSolar, saved, m.whLoss, xt, xl, response.weather[day])
    }
    // 月の値は日の値を足す (日によって送り側の記録だけ欠けていても、日の行の合計と合うように)
    val byMonth = days.groupBy { it.day.take(7) }.mapValues { (_, ds) ->
        val sun = ds.mapNotNull { it.weather?.sunHours }
        MonthEnergy(
            whIn = ds.sumOf { it.whIn },
            whOut = ds.sumOf { it.whOut },
            whSolar = ds.sumOf { it.whSolar },
            whSaved = ds.sumOf { it.whSaved },
            whLoss = ds.sumOf { it.whLoss },
            whTransfer = ds.sumOf { it.whTransfer },
            whTransferLoss = ds.sumOf { it.whTransferLoss },
            sunHours = if (sun.isEmpty()) null else sun.sum(),
            days = ds.sortedByDescending { it.day },
        )
    }
    val oldest = byMonth.keys.minOrNull()
    val months = mutableListOf<MonthEntry>()
    var y = currentMonth.take(4).toInt()
    var mo = currentMonth.substring(5, 7).toInt()
    while (true) {
        val key = monthKey(y, mo)
        months += MonthEntry(key, y, mo, byMonth[key])
        // 途中の記録のない月も 1 行出す。記録が無ければ今月だけ
        if (oldest == null || key <= oldest) break
        if (--mo == 0) { mo = 12; y-- }
    }
    return EnergyReport(months, response.settings.pricePerKwh)
}

private fun monthKey(y: Int, m: Int) = "$y-${m.toString().padStart(2, '0')}"

/** 記録している Mac の時刻での今月 "YYYY-MM" */
fun currentMonthOf(nowMillis: Long, tzOffsetSec: Int): String {
    val (y, m, _) = civilFromEpochDay((nowMillis.floorDiv(1000L) + tzOffsetSec).floorDiv(86_400L))
    return monthKey(y, m)
}

/** "YYYY-MM-DD" の曜日 (日月火水木金土) */
fun weekdayOf(day: String): Char {
    val epochDay = epochDayOf(day.take(4).toInt(), day.substring(5, 7).toInt(), day.substring(8, 10).toInt())
    // 1970-01-01 は木曜
    return "日月火水木金土"[(((epochDay + 4) % 7 + 7) % 7).toInt()]
}

/** 「11/15 (土)」 */
fun dayLabel(day: String): String =
    "${day.substring(5, 7).toInt()}/${day.substring(8, 10).toInt()} (${weekdayOf(day)})"

fun isWeekend(day: String): Boolean = weekdayOf(day).let { it == '土' || it == '日' }

// グレゴリオ暦の日付 ⇔ 1970-01-01 からの日数 (Howard Hinnant の days_from_civil / civil_from_days)
private fun epochDayOf(y0: Int, m: Int, d: Int): Long {
    val y = if (m <= 2) y0 - 1 else y0
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val doy = (153 * (if (m > 2) m - 3 else m + 9) + 2) / 5 + d - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146_097L + doe - 719_468
}

private fun civilFromEpochDay(z0: Long): Triple<Int, Int, Int> {
    val z = z0 + 719_468
    val era = (if (z >= 0) z else z - 146_096) / 146_097
    val doe = z - era * 146_097
    val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
    val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
    val y = (yoe + era * 400 + if (m <= 2) 1 else 0).toInt()
    return Triple(y, m, d)
}
