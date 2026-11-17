package app.ankermonitor.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WeatherTest {
    @Test
    fun statusのweatherを読む() {
        val s = parseStatus(
            """{"devices":[],"weather":{"place":"東京",
              "today":{"code":3,"tmax":18.4,"tmin":9.6,"precip":0.2,"sun_h":5.0,"rad_mj":10.1},
              "now":{"code":1,"temp":15.6,"at":1763200000.5}}}""",
        )
        val b = s.weather.badge()!!
        assertEquals("🌤️", b.emoji)
        assertEquals(16, b.temp)
        assertEquals("18/10℃ · 日照 5h", b.todayLine)
    }

    @Test
    fun 現在の値が無ければ今日の天気だけ() {
        val b = parseStatus("""{"weather":{"place":"x","today":{"code":61,"tmax":10,"tmin":5,"sun_h":null},"now":null}}""")
            .weather.badge()!!
        assertEquals("🌧️", b.emoji)
        assertNull(b.temp)
        assertEquals("10/5℃ · 日照 -h", b.todayLine)
    }

    @Test
    fun weatherがnullや中身が空なら出さない() {
        assertNull(parseStatus("""{"devices":[],"weather":null}""").weather.badge())
        assertNull(parseStatus("""{"devices":[]}""").weather.badge())
        assertNull(parseStatus("""{"weather":{"place":"x","today":null,"now":null}}""").weather.badge())
    }

    @Test
    fun 天気コードの分け方はダッシュボードと同じ() {
        val cases = mapOf(
            0 to "☀️", 1 to "🌤️", 2 to "⛅", 3 to "☁️", 45 to "🌫️", 48 to "🌫️", 51 to "🌦️", 57 to "🌦️",
            61 to "🌧️", 67 to "🌧️", 71 to "❄️", 77 to "❄️", 80 to "🌦️", 82 to "🌦️", 85 to "🌨️", 86 to "🌨️",
            95 to "⛈️", 99 to "⛈️", 4 to "·",
        )
        cases.forEach { (code, emoji) -> assertEquals(emoji, weatherIcon(code).emoji, "code $code") }
        assertEquals("·", weatherIcon(null).emoji)
    }

    private val hourly = parseHourlyWeather(
        """{"place":"東京","hours":[
          {"t":"2026-11-17T22:00","code":0,"temp":10.4,"pop":0,"day":0,"now":false},
          {"t":"2026-11-17T23:00","code":61,"temp":9.6,"pop":40,"day":0,"now":true},
          {"t":"2026-11-18T00:00","code":1,"temp":null,"pop":null,"day":0,"now":false},
          {"t":"2026-11-18T07:00","code":1,"temp":8,"pop":10,"day":1,"now":false}]}""",
    )

    @Test
    fun 時間ごとのカード() {
        val cards = hourly.cards()
        assertEquals(listOf("22時", "いま", "0時", "7時"), cards.map { it.label })
        assertEquals(listOf(true, false, false, false), cards.map { it.isPast })
        assertEquals(listOf(false, true, false, false), cards.map { it.isNow })
        // 夜の快晴・晴れは月、昼の晴れはそのまま
        assertEquals(listOf("🌙", "🌧️", "🌙", "🌤️"), cards.map { it.emoji })
        assertEquals(listOf("10°", "10°", "-", "8°"), cards.map { it.temp })
        // 降水確率は 0 や不明なら出さない
        assertEquals(listOf("", "40%", "", "10%"), cards.map { it.pop })
        assertEquals(listOf(null, null, "11/18", null), cards.map { it.dateMark })
        assertEquals(1, hourly.nowIndex)
    }

    @Test
    fun いまのカードが無ければ過ぎた時間も無い() {
        val h = hourly.copy(hours = hourly.hours.map { it.copy(now = false) })
        assertFalse(h.cards().any { it.isPast })
        assertEquals(0, h.nowIndex)
    }

    @Test
    fun 場所が未設定ならhoursは空() {
        val h = parseHourlyWeather("""{"hours":[]}""")
        assertNull(h.place)
        assertTrue(h.cards().isEmpty())
    }
}
