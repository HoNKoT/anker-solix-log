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
        val token = p[KEY_TOKEN].orEmpty()
        val local = p[KEY_LOCAL_URL].orEmpty()
        val global = p[KEY_GLOBAL_URL].orEmpty()
        val legacy = p[KEY_URL].orEmpty()
        when {
            local.isNotEmpty() || global.isNotEmpty() -> Connection(local, global, token)
            legacy.isNotEmpty() -> legacyConnection(legacy, token)
            else -> null
        }
    }.distinctUntilChanged()

    override suspend fun saveConnection(connection: Connection) {
        store.edit {
            it[KEY_LOCAL_URL] = connection.localUrl
            it[KEY_GLOBAL_URL] = connection.globalUrl
            it[KEY_TOKEN] = connection.token
            it.remove(KEY_URL)
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
        /** ローカル / グローバルに分ける前の 1 つだけの接続先 (読むだけ) */
        val KEY_URL = stringPreferencesKey("url")
        val KEY_LOCAL_URL = stringPreferencesKey("local_url")
        val KEY_GLOBAL_URL = stringPreferencesKey("global_url")
        val KEY_TOKEN = stringPreferencesKey("token")
        val KEY_SNAPSHOT = stringPreferencesKey("snapshot")
    }
}
