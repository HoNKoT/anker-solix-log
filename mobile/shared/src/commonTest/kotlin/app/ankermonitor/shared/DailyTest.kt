package app.ankermonitor.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DailyTest {
    private fun row(dev: String, day: String, whIn: Double = 0.0, whOut: Double = 0.0, solar: Double = 0.0,
                    xIn: Double = 0.0, xOut: Double = 0.0, xlIn: Double = 0.0, xlOut: Double = 0.0) =
        DailyRow(dev = dev, day = day, whIn = whIn, whOut = whOut, whSolar = solar,
            whXferIn = xIn, whXferOut = xOut, whXferLossIn = xlIn, whXferLossOut = xlOut)

    private fun report(vararg rows: DailyRow, month: String = "2026-11", all: Boolean = true) =
        summarizeEnergy(DailyResponse(rows = rows.toList(), settings = DailySettings(40.0)), month, all)

    private fun day(r: EnergyReport, day: String) =
        r.months.flatMap { it.energy?.days.orEmpty() }.single { it.day == day }

    @Test
    fun api_dailyの応答を読む() {
        val r = parseDaily(
            """{"rows":[{"dev":"c2000","day":"2026-11-15","wh_in":1200.5,"wh_out":300,"wh_solar":1000,"wh_ac":200.5,
              "wh_saved":250,"wh_loss":50,"hours":24,"wh_xfer_in":0,"wh_xfer_out":92,"wh_xfer_loss_in":0,"wh_xfer_loss_out":6}],
              "settings":{"price_per_kwh":31,"sample_interval":10,"location":{"name":"x","lat":1,"lon":2}},
              "weather":{"2026-11-15":{"code":0,"tmax":20,"tmin":8,"precip":0,"sun_h":9.1,"rad_mj":12}},
              "tz_offset":32400}""",
        )
        assertEquals(31.0, r.settings.pricePerKwh)
        assertEquals(32400, r.tzOffset)
        assertEquals(92.0, r.rows.single().whXferOut)
        assertEquals(0, r.weather.getValue("2026-11-15").code)
    }

    @Test
    fun nullの値は0として読む() {
        val r = parseDaily("""{"rows":[{"dev":"a","day":"2026-11-15","wh_in":null,"wh_out":5}],"settings":{}}""")
        assertEquals(0.0, r.rows.single().whIn)
        assertEquals(39.5, r.settings.pricePerKwh)
    }

    @Test
    fun ソーラーの機器と出力の機器が違っても全機器で節約を数える() {
        val r = report(
            row("c2000", "2026-11-15", whIn = 1000.0, solar = 1000.0),
            row("c300", "2026-11-15", whOut = 400.0),
        )
        val d = day(r, "2026-11-15")
        assertEquals(400.0, d.whSaved)
        assertEquals(16.0, r.yenOf(d.whSaved))
    }

    @Test
    fun 使い切れなかったソーラーは翌日以降に持ち越す() {
        val r = report(
            row("a", "2026-11-14", solar = 500.0, whOut = 100.0),
            row("a", "2026-11-15", whOut = 300.0), // 雨の日
            row("a", "2026-11-16", whOut = 300.0), // 貯めが尽きた後は数えない
        )
        assertEquals(100.0, day(r, "2026-11-14").whSaved)
        assertEquals(300.0, day(r, "2026-11-15").whSaved)
        assertEquals(100.0, day(r, "2026-11-16").whSaved)
        assertEquals(500.0, r.months.first().energy!!.whSaved)
    }

    @Test
    fun バックエンドのwh_savedは使わない() {
        val r = report(DailyRow(dev = "a", day = "2026-11-15", whOut = 100.0, whSaved = 999.0))
        assertEquals(0.0, day(r, "2026-11-15").whSaved)
    }

    @Test
    fun 全機器のTransferは送り側だけ数える() {
        val r = report(
            row("c2000", "2026-11-15", xOut = 92.0, xlOut = 6.0),
            row("c300", "2026-11-15", xIn = 92.0, xlIn = 6.0),
        )
        val d = day(r, "2026-11-15")
        assertEquals(92.0, d.whTransfer)
        assertEquals(6.0, d.whTransferLoss)
    }

    @Test
    fun 送り側の記録が無い日は受け側で数え月は日の合計() {
        val r = report(
            row("c2000", "2026-11-14", xOut = 50.0, xlOut = 2.0),
            row("c300", "2026-11-14", xIn = 50.0, xlIn = 2.0),
            row("c300", "2026-11-15", xIn = 30.0, xlIn = 1.0),
        )
        assertEquals(30.0, day(r, "2026-11-15").whTransfer)
        val m = r.months.first().energy!!
        assertEquals(80.0, m.whTransfer)
        assertEquals(3.0, m.whTransferLoss)
        assertTrue(m.hasTransfer)
    }

    @Test
    fun 一台ぶんなら受け取りと送り出しを足す() {
        val r = report(row("c300", "2026-11-15", xIn = 30.0, xOut = 10.0, xlIn = 1.0, xlOut = 2.0), all = false)
        assertEquals(40.0, day(r, "2026-11-15").whTransfer)
        assertEquals(3.0, day(r, "2026-11-15").whTransferLoss)
    }

    @Test
    fun 今月から一番古い月まで並べ記録のない月も出す() {
        val r = report(
            row("a", "2026-08-31", whOut = 1.0),
            row("a", "2026-11-01", whOut = 1.0),
            row("a", "2026-11-03", whOut = 1.0),
        )
        assertEquals(listOf("2026-11", "2026-10", "2026-09", "2026-08"), r.months.map { it.key })
        assertNull(r.months[1].energy)
        assertNull(r.months[2].energy)
        assertEquals(listOf("2026-11-03", "2026-11-01"), r.months[0].energy!!.days.map { it.day })
        assertFalse(r.months[0].energy!!.hasTransfer)
    }

    @Test
    fun 年をまたいでさかのぼる() {
        val r = report(row("a", "2025-12-10", whOut = 1.0), month = "2026-02")
        assertEquals(listOf("2026-02", "2026-01", "2025-12"), r.months.map { it.key })
        assertEquals(12, r.months.last().month)
    }

    @Test
    fun 記録が無ければ今月だけ() {
        val r = report(month = "2026-11")
        assertEquals(listOf("2026-11"), r.months.map { it.key })
        assertNull(r.months.single().energy)
    }

    @Test
    fun 日照は天気の記録がある日だけ足す() {
        val res = DailyResponse(
            rows = listOf(row("a", "2026-11-14"), row("a", "2026-11-15"), row("a", "2026-11-16")),
            weather = mapOf("2026-11-14" to WeatherDay(sunHours = 5.5), "2026-11-15" to WeatherDay(sunHours = 2.0)),
        )
        val m = summarizeEnergy(res, "2026-11").months.single().energy!!
        assertEquals(7.5, m.sunHours)
        assertEquals(5.5, m.days.single { it.day == "2026-11-14" }.weather?.sunHours)
    }

    @Test
    fun 今月はMacの時刻で決める() {
        // 2026-10-31 15:30 UTC = 2026-11-01 00:30 JST
        val millis = 1_793_460_600_000L
        assertEquals("2026-11", currentMonthOf(millis, 9 * 3600))
        assertEquals("2026-10", currentMonthOf(millis, 0))
    }

    @Test
    fun 曜日と日付の言い方() {
        assertEquals('火', weekdayOf("2026-11-17"))
        assertEquals('木', weekdayOf("1970-01-01"))
        assertEquals('日', weekdayOf("2024-03-03"))
        assertEquals("11/15 (日)", dayLabel("2026-11-15"))
        assertTrue(isWeekend("2026-11-14"))
        assertFalse(isWeekend("2026-11-16"))
    }

    @Test
    fun 数の書き方() {
        assertEquals("1.23", kwh(1234.0))
        assertEquals("0.01", kwh(5.0))
        assertEquals("0.00", kwh(4.9))
        assertEquals("12.35", kwh(12346.0))
        assertEquals("0.006", fixed(6.0 / 1000, 3))
        assertEquals("12,346", yen(12345.6))
        assertEquals("0", yen(0.4))
        assertEquals("1,000,000", yen(1_000_000.0))
        assertEquals("5", plain(5.0))
        assertEquals("5.2", plain(5.2))
    }
}
