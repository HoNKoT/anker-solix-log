package app.ankermonitor.shared

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** ウィジェット用に残す最後の取得結果 */
@Serializable
data class Snapshot(
    val status: StatusResponse? = null,
    val fetchedAtMillis: Long? = null,
    /** 直近の取得が失敗していればその理由 (成功したら消す) */
    val problemMessage: String? = null,
    val problemAtMillis: Long? = null,
)

/** 接続先とスナップショットの保存先。Android は DataStore、iOS は後で NSUserDefaults 等で実装する */
interface MonitorStorage {
    val connection: Flow<Connection?>
    suspend fun saveConnection(connection: Connection)

    val snapshot: Flow<Snapshot>
    suspend fun saveSnapshot(snapshot: Snapshot)
}

/** 1 回取りに行き、結果をスナップショットに書く (ウィジェットの定期更新と、アプリを開いたときの更新で使う) */
suspend fun refreshSnapshot(api: AnkerApi, storage: MonitorStorage, connection: Connection, previous: Snapshot): Snapshot {
    val now = currentTimeMillis()
    val next = when (val result = api.fetchStatus(connection)) {
        is FetchResult.Success -> Snapshot(result.status, now, null, null)
        // 失敗しても最後に読めた値は残し、古いことが分かるように理由と時刻を付ける
        else -> previous.copy(problemMessage = result.problem(connection)?.message, problemAtMillis = now)
    }
    storage.saveSnapshot(next)
    return next
}
