package app.ankermonitor.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class StatusPollerTest {
    private val connection = Connection("", "http://100.64.1.2:19877", "t")

    /** 応答を順に返す (最後の応答はくり返す) */
    private fun poller(vararg responses: HttpStatusCode?): StatusPoller {
        var i = 0
        val engine = MockEngine {
            val code = responses[minOf(i++, responses.lastIndex)]
            when (code) {
                null -> throw IllegalStateException("connect failed")
                HttpStatusCode.OK -> respond("""{"devices":[{"id":"a","state":"connected","battery":50}]}""")
                else -> respondError(code)
            }
        }
        return StatusPoller(AnkerApi(HttpClient(engine) { expectSuccess = false }), intervalMillis = 10)
    }

    @Test
    fun トークン違いはすぐに出す() = runTest {
        val first = poller(HttpStatusCode.Unauthorized).poll(connection).drop(1).take(1).toList().single()
        assertEquals(Problem.WrongToken, first.problem)
    }

    @Test
    fun つながらないのは3回続いてから出す() = runTest {
        val states = poller(HttpStatusCode.OK, null).poll(connection).drop(1).take(4).toList()
        assertNotNull(states[0].status)
        assertNull(states[1].problem)
        assertNull(states[2].problem)
        assertIs<Problem.Unreachable>(states[3].problem)
        // 最後に読めた値は残す
        assertNotNull(states[3].status)
    }

    @Test
    fun 読めたら理由を消す() = runTest {
        val states = poller(HttpStatusCode.Forbidden, HttpStatusCode.OK).poll(connection).drop(1).take(2).toList()
        assertEquals(Problem.Refused("100.64.1.2"), states[0].problem)
        assertNull(states[1].problem)
    }
}
