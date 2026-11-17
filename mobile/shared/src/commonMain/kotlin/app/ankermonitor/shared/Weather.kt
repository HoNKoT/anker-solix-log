package app.ankermonitor.shared

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/** `/status` の `weather` (anker_monitor.py の weather_now)。取れていない値は null で来る */
@Serializable
data class WeatherNow(
    val place: String? = null,
    val today: WeatherDay? = null,
    /** 1 時間以内に取れた現在の値だけ。無ければ null */
    val now: WeatherCurrent? = null,
)

/** 日ごとの天気 (weather 表の 1 行)。code は WMO 天気コード */
@Serializable
data class WeatherDay(
    val code: Int? = null,
    val tmax: Double? = null,
    val tmin: Double? = null,
    val precip: Double? = null,
    @SerialName("sun_h") val sunHours: Double? = null,
    @SerialName("rad_mj") val radiationMj: Double? = null,
)

@Serializable
data class WeatherCurrent(
    val code: Int? = null,
    val temp: Double? = null,
    /** 取った時刻 (UNIX 秒) */
    val at: Double? = null,
)

/** `GET /api/weather-hourly`。場所が未設定なら hours は空で place も無い */
@Serializable
data class HourlyWeather(
    val place: String? = null,
    val hours: List<WeatherHour> = emptyList(),
)

@Serializable
data class WeatherHour(
    /** 場所の現地時刻 "YYYY-MM-DDTHH:00" */
    val t: String,
    val code: Int? = null,
    val temp: Double? = null,
    /** 降水確率 % */
    val pop: Int? = null,
    /** 1 = 昼 / 0 = 夜 (Open-Meteo の is_day) */
    val day: Int? = null,
    val now: Boolean = false,
)

data class WeatherIcon(val emoji: String, val label: String)

/** WMO の天気コード → 絵文字と名前 (dashboard.html の wxIcon と同じ分け方) */
fun weatherIcon(code: Int?): WeatherIcon = when {
    code == null -> WeatherIcon("·", "")
    code == 0 -> WeatherIcon("☀️", "快晴")
    code == 1 -> WeatherIcon("🌤️", "晴れ")
    code == 2 -> WeatherIcon("⛅", "一部曇り")
    code == 3 -> WeatherIcon("☁️", "曇り")
    code == 45 || code == 48 -> WeatherIcon("🌫️", "霧")
    code in 51..57 -> WeatherIcon("🌦️", "霧雨")
    code in 61..67 -> WeatherIcon("🌧️", "雨")
    code in 71..77 -> WeatherIcon("❄️", "雪")
    code in 80..82 -> WeatherIcon("🌦️", "にわか雨")
    code == 85 || code == 86 -> WeatherIcon("🌨️", "にわか雪")
    code >= 95 -> WeatherIcon("⛈️", "雷雨")
    else -> WeatherIcon("·", "")
}

/** 右上に出す天気。今日の値も現在の値も無ければ null (出さない) */
data class WeatherBadge(
    val emoji: String,
    val label: String,
    /** 現在の気温 (℃、四捨五入)。現在の値が無ければ null */
    val temp: Int?,
    /** 「25/18℃ · 日照 5.2h」。今日の値が無ければ null */
    val todayLine: String?,
)

fun WeatherNow?.badge(): WeatherBadge? {
    val w = this ?: return null
    val t = w.today
    val n = w.now
    if (t == null && n == null) return null
    val icon = weatherIcon(n?.code ?: t?.code)
    val todayLine = t?.let { "${roundOrDash(it.tmax)}/${roundOrDash(it.tmin)}℃ · 日照 ${it.sunHours?.let(::plain) ?: "-"}h" }
    return WeatherBadge(icon.emoji, icon.label, n?.temp?.roundToInt(), todayLine)
}

/** 1 時間ごとのカード 1 枚ぶんの表示 */
data class HourCard(
    /** 「いま」または「14時」 */
    val label: String,
    val emoji: String,
    /** 「18°」。無ければ「-」 */
    val temp: String,
    /** 「30%」。0 % や不明なら空 */
    val pop: String,
    val isNow: Boolean,
    /** いまより前の時間 (薄くする) */
    val isPast: Boolean,
    /** 日付が変わった最初のカードなら「11/16」 */
    val dateMark: String?,
)

/** dashboard.html の toggleHourly() と同じ並べ方。夜の快晴・晴れは月にする */
fun HourlyWeather.cards(): List<HourCard> {
    val nowIndex = hours.indexOfFirst { it.now }
    var prevDay: String? = null
    return hours.mapIndexed { i, h ->
        val date = h.t.substringBefore('T')
        val hour = h.t.substringAfter('T').take(2).toIntOrNull()
        val mark = if (prevDay != null && date != prevDay) {
            "${date.substring(5, 7).toInt()}/${date.substring(8, 10).toInt()}"
        } else null
        prevDay = date
        var emoji = weatherIcon(h.code).emoji
        if (h.day == 0 && (h.code == 0 || h.code == 1)) emoji = "🌙"
        HourCard(
            label = if (h.now) "いま" else "${hour ?: "-"}時",
            emoji = emoji,
            temp = h.temp?.let { "${it.roundToInt()}°" } ?: "-",
            pop = h.pop?.takeIf { it > 0 }?.let { "$it%" } ?: "",
            isNow = h.now,
            isPast = i < nowIndex,
            dateMark = mark,
        )
    }
}

/** 開いたときに左端に置くカードの位置 (「いま」が無ければ先頭) */
val HourlyWeather.nowIndex: Int get() = hours.indexOfFirst { it.now }.coerceAtLeast(0)

private fun roundOrDash(v: Double?): String = v?.roundToInt()?.toString() ?: "-"
