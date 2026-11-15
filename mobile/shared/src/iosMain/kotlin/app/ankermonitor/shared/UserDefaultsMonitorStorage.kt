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
        defaults.setObject(connection.url, KEY_URL)
        defaults.setObject(connection.token, KEY_TOKEN)
        defaults.removeObjectForKey(KEY_SNAPSHOT)
        connectionFlow.value = connection
        snapshotFlow.value = Snapshot()
    }

    override suspend fun saveSnapshot(snapshot: Snapshot) {
        defaults.setObject(StatusJson.encodeToString(Snapshot.serializer(), snapshot), KEY_SNAPSHOT)
        snapshotFlow.value = snapshot
    }

    private fun readConnection(): Connection? {
        val url = defaults.stringForKey(KEY_URL)
        return if (url.isNullOrEmpty()) null else Connection(url, defaults.stringForKey(KEY_TOKEN).orEmpty())
    }

    private fun readSnapshot(): Snapshot = defaults.stringForKey(KEY_SNAPSHOT)
        ?.let { runCatching { StatusJson.decodeFromString(Snapshot.serializer(), it) }.getOrNull() }
        ?: Snapshot()

    private companion object {
        const val KEY_URL = "url"
        const val KEY_TOKEN = "token"
        const val KEY_SNAPSHOT = "snapshot"
    }
}
