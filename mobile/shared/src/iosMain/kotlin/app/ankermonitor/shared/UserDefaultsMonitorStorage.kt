package app.ankermonitor.shared

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import platform.Foundation.NSUserDefaults

/** iOS の保存先。ウィジェット (WidgetKit) と共有するときは App Group の suiteName を渡す */
class UserDefaultsMonitorStorage(suiteName: String? = null) : MonitorStorage {
    private val defaults = suiteName?.let { NSUserDefaults(suiteName = it) } ?: NSUserDefaults.standardUserDefaults

    private val connectionFlow = MutableStateFlow(readConnection())
    private val snapshotFlow = MutableStateFlow(readSnapshot())

    override val connection: Flow<Connection?> = connectionFlow
    override val snapshot: Flow<Snapshot> = snapshotFlow

    override suspend fun saveConnection(connection: Connection) {
        defaults.setObject(connection.localUrl, KEY_LOCAL_URL)
        defaults.setObject(connection.globalUrl, KEY_GLOBAL_URL)
        defaults.setObject(connection.token, KEY_TOKEN)
        defaults.removeObjectForKey(KEY_URL)
        defaults.removeObjectForKey(KEY_SNAPSHOT)
        connectionFlow.value = connection
        snapshotFlow.value = Snapshot()
    }

    override suspend fun saveSnapshot(snapshot: Snapshot) {
        defaults.setObject(StatusJson.encodeToString(Snapshot.serializer(), snapshot), KEY_SNAPSHOT)
        snapshotFlow.value = snapshot
    }

    private fun readConnection(): Connection? {
        val token = defaults.stringForKey(KEY_TOKEN).orEmpty()
        val local = defaults.stringForKey(KEY_LOCAL_URL).orEmpty()
        val global = defaults.stringForKey(KEY_GLOBAL_URL).orEmpty()
        val legacy = defaults.stringForKey(KEY_URL).orEmpty()
        return when {
            local.isNotEmpty() || global.isNotEmpty() -> Connection(local, global, token)
            legacy.isNotEmpty() -> legacyConnection(legacy, token)
            else -> null
        }
    }

    private fun readSnapshot(): Snapshot = defaults.stringForKey(KEY_SNAPSHOT)
        ?.let { runCatching { StatusJson.decodeFromString(Snapshot.serializer(), it) }.getOrNull() }
        ?: Snapshot()

    private companion object {
        /** ローカル / グローバルに分ける前の 1 つだけの接続先 (読むだけ) */
        const val KEY_URL = "url"
        const val KEY_LOCAL_URL = "local_url"
        const val KEY_GLOBAL_URL = "global_url"
        const val KEY_TOKEN = "token"
        const val KEY_SNAPSHOT = "snapshot"
    }
}
