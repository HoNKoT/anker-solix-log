package app.ankermonitor.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnkerApiTest {
    private val connection = Connection("http://100.64.1.2:19877", "secret")

    private fun api(handler: MockRequestHandler): AnkerApi =
        AnkerApi(HttpClient(MockEngine(handler)) { expectSuccess = false })

    @Test
    fun statusをBearer付きで読む() = runTest {
        var auth: String? = null
        var path: String? = null
        val api = api { req ->
            auth = req.headers[HttpHeaders.Authorization]
            path = req.url.encodedPath
            respond("""{"ok":true,"devices":[{"id":"a","state":"connected","battery":80}]}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val result = api.fetchStatus(connection)
        assertIs<FetchResult.Success>(result)
        assertEquals(80, result.status.devices.single().batteryPercent)
        assertEquals("Bearer secret", auth)
        assertEquals("/status", path)
    }

    @Test
    fun トークンが空ならAuthorizationを付けない() = runTest {
        var auth: String? = "unset"
        val api = api { req ->
            auth = req.headers[HttpHeaders.Authorization]
            respond("""{"devices":[]}""")
        }
        assertIs<FetchResult.Success>(api.fetchStatus(connection.copy(token = "")))
        assertNull(auth)
    }

    @Test
    fun 応答コードで理由を分ける() = runTest {
        for ((code, check) in listOf<Pair<HttpStatusCode, (FetchResult) -> Unit>>(
            HttpStatusCode.Unauthorized to { r -> assertEquals(Problem.WrongToken, r.problem(connection)) },
            HttpStatusCode.Forbidden to { r ->
                val p = r.problem(connection)
                assertEquals(Problem.Refused("100.64.1.2"), p)
                assertTrue(p!!.message.contains("amon lan on"))
            },
            HttpStatusCode.NotFound to { r -> assertEquals(Problem.Unreachable(404), r.problem(connection)) },
        )) {
            val api = api { respondError(code) }
            check(api.fetchStatus(connection))
        }
    }

    @Test
    fun つながらなければUnreachable() = runTest {
        val api = api { throw IllegalStateException("connect failed") }
        val result = api.fetchStatus(connection)
        assertIs<FetchResult.Unreachable>(result)
        assertTrue(result.problem(connection)!!.message.contains("Tailscale"))
    }
}

class AnkerApiExtraTest {
    private val connection = Connection("http://100.64.1.2:19877", "secret")

    private fun api(handler: MockRequestHandler): AnkerApi =
        AnkerApi(HttpClient(MockEngine(handler)) { expectSuccess = false; install(HttpTimeout) })

    @Test
    fun 時間ごとの天気をBearer付きで読む() = runTest {
        var path: String? = null
        var auth: String? = null
        val api = api { req ->
            path = req.url.encodedPath
            auth = req.headers[HttpHeaders.Authorization]
            respond("""{"place":"x","hours":[{"t":"2026-11-17T10:00","code":0,"temp":12,"pop":0,"day":1,"now":true}]}""")
        }
        val r = api.fetchHourlyWeather(connection)
        assertIs<Loaded.Ok<HourlyWeather>>(r)
        assertEquals(1, r.value.hours.size)
        assertEquals("/api/weather-hourly", path)
        assertEquals("Bearer secret", auth)
    }

    @Test
    fun 日ごとの電力量は全期間を読む() = runTest {
        var query: String? = null
        val api = api { req ->
            query = req.url.encodedPath + "?" + req.url.encodedQuery
            respond("""{"rows":[],"settings":{"price_per_kwh":39.5},"weather":{},"tz_offset":32400}""")
        }
        val r = api.fetchDaily(connection, 1_763_200_000)
        assertIs<Loaded.Ok<DailyResponse>>(r)
        assertEquals("/api/daily?from=0&to=1763200000", query)
    }

    @Test
    fun 読めなければstatusと同じ理由を返す() = runTest {
        val r = api { respondError(HttpStatusCode.Unauthorized) }.fetchDaily(connection, 1)
        assertEquals(Loaded.Failed(Problem.WrongToken), r)
        val r2 = api { respond("not json") }.fetchHourlyWeather(connection)
        assertIs<Loaded.Failed>(r2)
        assertIs<Problem.Unreachable>(r2.problem)
    }
}
