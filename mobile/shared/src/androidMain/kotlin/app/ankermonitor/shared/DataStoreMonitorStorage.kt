package app.ankermonitor.shared

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

// DataStore は同じファイルに 1 プロセス 1 インスタンスでないと壊れるので、Context の拡張として 1 つだけ持つ
private val Context.monitorDataStore: DataStore<Preferences> by preferencesDataStore(name = "anker_monitor")

class DataStoreMonitorStorage(context: Context) : MonitorStorage {
    private val store = context.applicationContext.monitorDataStore

    override val connection: Flow<Connection?> = store.data.map { p ->
        val url = p[KEY_URL]
        if (url.isNullOrEmpty()) null else Connection(url, p[KEY_TOKEN].orEmpty())
    }.distinctUntilChanged()

    override suspend fun saveConnection(connection: Connection) {
        store.edit {
            it[KEY_URL] = connection.url
            it[KEY_TOKEN] = connection.token
            // 接続先が変わったら、前の Mac の値をウィジェットに残さない
            it.remove(KEY_SNAPSHOT)
        }
    }

    override val snapshot: Flow<Snapshot> = store.data.map { p ->
        p[KEY_SNAPSHOT]?.let { runCatching { StatusJson.decodeFromString(Snapshot.serializer(), it) }.getOrNull() }
            ?: Snapshot()
    }.distinctUntilChanged()

    override suspend fun saveSnapshot(snapshot: Snapshot) {
        val json = StatusJson.encodeToString(Snapshot.serializer(), snapshot)
        store.edit { it[KEY_SNAPSHOT] = json }
    }

    private companion object {
        val KEY_URL = stringPreferencesKey("url")
        val KEY_TOKEN = stringPreferencesKey("token")
        val KEY_SNAPSHOT = stringPreferencesKey("snapshot")
    }
}
