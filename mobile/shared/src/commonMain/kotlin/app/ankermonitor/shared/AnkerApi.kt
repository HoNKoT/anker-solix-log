package app.ankermonitor.shared

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
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

class AnkerApi(private val client: HttpClient) {
    suspend fun fetchStatus(connection: Connection): FetchResult {
        val response = try {
            client.get("${connection.url}/status") {
                if (connection.token.isNotEmpty()) header(HttpHeaders.Authorization, "Bearer ${connection.token}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return FetchResult.Unreachable(null, e.message ?: e::class.simpleName)
        }
        return when (val code = response.status.value) {
            200 -> try {
                FetchResult.Success(parseStatus(response.bodyAsText()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FetchResult.Unreachable(code, "応答を読めません: ${e.message}")
            }
            401 -> FetchResult.Unauthorized
            403 -> FetchResult.Forbidden
            else -> FetchResult.Unreachable(code, null)
        }
    }
}
