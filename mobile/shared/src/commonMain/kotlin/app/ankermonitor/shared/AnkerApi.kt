package app.ankermonitor.shared

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

/**
 * 接続先とトークン。トークンはバックエンド側で未設定なら空でよい (amon remote と同じ)。
 * メニューバーアプリと同じく、ローカル (同じネットワークの 名前.local など) とグローバル (Tailscale の …ts.net など) を持てる。
 * どちらも [normalizeServerUrl] 済みの URL で、使わない方は空。
 */
data class Connection(val localUrl: String, val globalUrl: String, val token: String) {
    /** 試す順。ローカルが先 (届くならそちらが速い) */
    val candidates: List<String> get() = listOf(localUrl, globalUrl).filter { it.isNotEmpty() }.distinct()
}

sealed interface FetchResult {
    /** [baseUrl] は読めた接続先 (ローカル / グローバルのどちらか) */
    data class Success(val status: StatusResponse, val baseUrl: String = "") : FetchResult
    /** 401: トークンが違う / 無い */
    data object Unauthorized : FetchResult
    /** 403: 記録している Mac が LAN 公開していない、または読めないパス */
    data object Forbidden : FetchResult
    /** 接続できない・時間切れ・想定外の応答 (code は HTTP 応答があったときだけ) */
    data class Unreachable(val code: Int?, val reason: String?) : FetchResult
}

val StatusJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
}

fun parseStatus(body: String): StatusResponse = StatusJson.decodeFromString(StatusResponse.serializer(), body)

fun createAnkerHttpClient(): HttpClient = platformHttpClient {
    // 非 2xx でも例外にせず、コードで分岐する (401 / 403 を言い分けるため)
    expectSuccess = false
    install(HttpTimeout) {
        // Tailscale 越しは 2 秒を超えることがあるので、メニューバーアプリのリモート時と同じ 5 秒
        requestTimeoutMillis = 5_000
        connectTimeoutMillis = 5_000
    }
}

fun parseHourlyWeather(body: String): HourlyWeather = StatusJson.decodeFromString(HourlyWeather.serializer(), body)

fun parseDaily(body: String): DailyResponse = StatusJson.decodeFromString(DailyResponse.serializer(), body)

/** /status 以外の読み取り。失敗の理由は /status と同じ言い分けにする */
sealed interface Loaded<out T> {
    data class Ok<T>(val value: T) : Loaded<T>
    data class Failed(val problem: Problem) : Loaded<Nothing>
}

class AnkerApi(
    private val client: HttpClient,
    private val nowMillis: () -> Long = ::currentTimeMillis,
) {
    // 画面の定期取得とウィジェットの更新が同じインスタンスを使うので、最後に読めた接続先を共有する
    private var lastWorking: Pair<Connection, String>? = null
    private var lastLocalProbeMillis = 0L

    suspend fun fetchStatus(connection: Connection): FetchResult =
        when (val r = get(connection, "/status", null, ::parseStatus)) {
            is Fetched.Ok -> FetchResult.Success(r.value, r.baseUrl)
            is Fetched.Failed -> r.result
        }

    /** 1 時間ごとの天気。バックエンドが Open-Meteo に取りに行くことがある (1 回 20 秒まで、別ホストで取り直すと 40 秒) ので長めに待つ */
    suspend fun fetchHourlyWeather(connection: Connection): Loaded<HourlyWeather> =
        get(connection, "/api/weather-hourly", 45_000, ::parseHourlyWeather).toLoaded(connection)

    /** 日ごとの電力量。ソーラーの貯めを最初から積み上げるので全期間 (from=0) を読む */
    suspend fun fetchDaily(connection: Connection, nowSec: Long): Loaded<DailyResponse> =
        get(connection, "/api/daily?from=0&to=$nowSec", 30_000, ::parseDaily).toLoaded(connection)

    private sealed interface Fetched<out T> {
        data class Ok<T>(val value: T, val baseUrl: String) : Fetched<T>
        data class Failed(val result: FetchResult) : Fetched<Nothing>
    }

    private fun <T> Fetched<T>.toLoaded(connection: Connection): Loaded<T> = when (this) {
        is Fetched.Ok -> Loaded.Ok(value)
        is Fetched.Failed -> Loaded.Failed(result.problem(connection)!!)
    }

    /** 前に読めた接続先から試す。グローバルを使っている間も [LOCAL_PROBE_INTERVAL_MILLIS] おきにローカルから試し、届けば戻す */
    private fun order(connection: Connection): List<String> {
        val all = connection.candidates
        val last = lastWorking?.takeIf { it.first == connection }?.second
        if (last == null || last == all.firstOrNull() || last !in all) return all
        val now = nowMillis()
        if (now - lastLocalProbeMillis >= LOCAL_PROBE_INTERVAL_MILLIS) {
            lastLocalProbeMillis = now
            return all
        }
        return listOf(last) + all.filter { it != last }
    }

    /**
     * 候補を順に試す。読めた / 401 / 403 ならそこで止める (トークン違いや LAN 非公開は、別の経路でも同じなので)。
     * それ以外 (つながらない・時間切れ・想定外の応答) なら次の候補へ。メニューバーアプリの fetchRemote と同じ
     */
    private suspend fun <T> get(connection: Connection, path: String, timeoutMillis: Long?, parse: (String) -> T): Fetched<T> {
        val bases = order(connection)
        if (bases.isEmpty()) return Fetched.Failed(FetchResult.Unreachable(null, "接続先がありません"))
        var last: Fetched<T>? = null
        for ((i, base) in bases.withIndex()) {
            // 後ろに候補があるローカルは、届かないときにすぐ諦めてグローバルへ回せるよう短く待つ
            val quick = i < bases.size - 1 && base == connection.localUrl
            val r = getOnce(base, connection.token, path, timeoutMillis, quick, parse)
            last = r
            val stop = r is Fetched.Ok ||
                (r is Fetched.Failed && (r.result == FetchResult.Unauthorized || r.result == FetchResult.Forbidden))
            if (r is Fetched.Ok) lastWorking = connection to base
            if (stop) break
        }
        return last!!
    }

    private suspend fun <T> getOnce(
        base: String,
        token: String,
        path: String,
        timeoutMillis: Long?,
        quick: Boolean,
        parse: (String) -> T,
    ): Fetched<T> {
        val response = try {
            client.get("$base$path") {
                if (token.isNotEmpty()) header(HttpHeaders.Authorization, "Bearer $token")
                timeout {
                    if (quick) connectTimeoutMillis = LOCAL_CONNECT_TIMEOUT_MILLIS
                    if (timeoutMillis != null) requestTimeoutMillis = timeoutMillis
                    else if (quick) requestTimeoutMillis = LOCAL_CONNECT_TIMEOUT_MILLIS
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Fetched.Failed(FetchResult.Unreachable(null, e.message ?: e::class.simpleName))
        }
        return when (val code = response.status.value) {
            200 -> try {
                Fetched.Ok(parse(response.bodyAsText()), base)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Fetched.Failed(FetchResult.Unreachable(code, "応答を読めません: ${e.message}"))
            }
            401 -> Fetched.Failed(FetchResult.Unauthorized)
            403 -> Fetched.Failed(FetchResult.Forbidden)
            else -> Fetched.Failed(FetchResult.Unreachable(code, null))
        }
    }

    private companion object {
        const val LOCAL_PROBE_INTERVAL_MILLIS = 30_000L
        // メニューバーアプリのローカル候補と同じ 2 秒
        const val LOCAL_CONNECT_TIMEOUT_MILLIS = 2_000L
    }
}
