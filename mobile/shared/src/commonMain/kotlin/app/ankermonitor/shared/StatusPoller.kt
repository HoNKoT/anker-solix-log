package app.ankermonitor.shared

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

data class MonitorState(
    val status: StatusResponse? = null,
    /** 最後に読めた時刻 (ミリ秒) */
    val fetchedAtMillis: Long? = null,
    val problem: Problem? = null,
    val loading: Boolean = true,
    /** 最後に読めた接続先 (ローカル / グローバル) */
    val baseUrl: String? = null,
)

/**
 * 表示中の画面のための定期取得。前の応答を待ってから次を投げるので、遅い回線でも要求が重ならない。
 * 401 / 403 はすぐ出すが、つながらないのは Wi-Fi の切り替えなどで 1〜2 回はよくあるので、
 * [failuresBeforeOffline] 回続いてから出す (メニューバーアプリのリモート表示と同じ)。
 */
class StatusPoller(
    private val api: AnkerApi,
    private val intervalMillis: Long = 3_000,
    private val failuresBeforeOffline: Int = 3,
    private val onSuccess: suspend (StatusResponse) -> Unit = {},
) {
    fun poll(connection: Connection): Flow<MonitorState> = flow {
        var state = MonitorState()
        var failures = 0
        emit(state)
        while (true) {
            val result = api.fetchStatus(connection)
            state = when (result) {
                is FetchResult.Success -> {
                    failures = 0
                    onSuccess(result.status)
                    MonitorState(result.status, currentTimeMillis(), null, loading = false, baseUrl = result.baseUrl)
                }
                is FetchResult.Unreachable -> {
                    failures++
                    val problem = if (failures >= failuresBeforeOffline) result.problem(connection) else state.problem
                    state.copy(problem = problem, loading = state.status == null && problem == null)
                }
                else -> {
                    failures++
                    state.copy(problem = result.problem(connection), loading = false)
                }
            }
            emit(state)
            delay(intervalMillis)
        }
    }
}
