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

/** 接続先とトークン。トークンはバックエンド側で未設定なら空でよい (amon remote と同じ) */
data class Connection(val url: String, val token: String)

sealed interface FetchResult {
    data class Success(val status: StatusResponse) : FetchResult
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

class AnkerApi(private val client: HttpClient) {
    suspend fun fetchStatus(connection: Connection): FetchResult =
        when (val r = get(connection, "/status", null, ::parseStatus)) {
            is Fetched.Ok -> FetchResult.Success(r.value)
            is Fetched.Failed -> r.result
        }

    /** 1 時間ごとの天気。バックエンドが Open-Meteo に取りに行くことがある (1 回 20 秒まで、別ホストで取り直すと 40 秒) ので長めに待つ */
    suspend fun fetchHourlyWeather(connection: Connection): Loaded<HourlyWeather> =
        get(connection, "/api/weather-hourly", 45_000, ::parseHourlyWeather).toLoaded(connection)

    /** 日ごとの電力量。ソーラーの貯めを最初から積み上げるので全期間 (from=0) を読む */
    suspend fun fetchDaily(connection: Connection, nowSec: Long): Loaded<DailyResponse> =
        get(connection, "/api/daily?from=0&to=$nowSec", 30_000, ::parseDaily).toLoaded(connection)

    private sealed interface Fetched<out T> {
        data class Ok<T>(val value: T) : Fetched<T>
        data class Failed(val result: FetchResult) : Fetched<Nothing>
    }

    private fun <T> Fetched<T>.toLoaded(connection: Connection): Loaded<T> = when (this) {
        is Fetched.Ok -> Loaded.Ok(value)
        is Fetched.Failed -> Loaded.Failed(result.problem(connection)!!)
    }

    private suspend fun <T> get(connection: Connection, path: String, timeoutMillis: Long?, parse: (String) -> T): Fetched<T> {
        val response = try {
            client.get("${connection.url}$path") {
                if (connection.token.isNotEmpty()) header(HttpHeaders.Authorization, "Bearer ${connection.token}")
                if (timeoutMillis != null) timeout { requestTimeoutMillis = timeoutMillis }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Fetched.Failed(FetchResult.Unreachable(null, e.message ?: e::class.simpleName))
        }
        return when (val code = response.status.value) {
            200 -> try {
                Fetched.Ok(parse(response.bodyAsText()))
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
}
