package app.ankermonitor.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
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
