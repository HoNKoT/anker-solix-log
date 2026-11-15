package app.ankermonitor.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ankermonitor.shared.AnkerApi
import app.ankermonitor.shared.Connection
import app.ankermonitor.shared.FetchResult
import app.ankermonitor.shared.MonitorState
import app.ankermonitor.shared.MonitorStorage
import app.ankermonitor.shared.Snapshot
import app.ankermonitor.shared.StatusPoller
import app.ankermonitor.shared.currentTimeMillis
import app.ankermonitor.shared.normalizeServerUrl
import app.ankermonitor.shared.problem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

sealed interface ConnectionState {
    data object Loading : ConnectionState
    data object NotSet : ConnectionState
    data class Set(val connection: Connection) : ConnectionState
}

sealed interface SaveResult {
    data object Saved : SaveResult
    /** 保存はしたが、いまは読めない (amon remote と同じく、Tailscale が切れているだけのこともあるので保存する) */
    data class SavedButUnreachable(val message: String) : SaveResult
    /** 401 / 403 / 空欄: 保存しない */
    data class Rejected(val message: String) : SaveResult
}

class MainViewModel(
    private val storage: MonitorStorage,
    private val api: AnkerApi,
    poller: StatusPoller,
    /** 接続先を保存できたとき (Android はウィジェットの即時更新を積む) */
    private val onSaved: () -> Unit,
) : ViewModel() {

    val connection: StateFlow<ConnectionState> = storage.connection
        .map { it?.let(ConnectionState::Set) ?: ConnectionState.NotSet }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ConnectionState.Loading)

    // 画面が見えている間だけ読み続ける (WhileSubscribed: 裏に回って 5 秒で止まる)
    @OptIn(ExperimentalCoroutinesApi::class)
    val monitor: StateFlow<MonitorState> = connection
        .flatMapLatest { state ->
            if (state is ConnectionState.Set) poller.poll(state.connection) else flowOf(MonitorState(loading = false))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MonitorState())

    // 初回は保存した瞬間に設定画面が消えて呼び出し元のスコープが止まるので、続きの保存は viewModelScope で最後までやる
    suspend fun testAndSave(urlInput: String, tokenInput: String): SaveResult =
        viewModelScope.async { checkAndSave(urlInput, tokenInput) }.await()

    private suspend fun checkAndSave(urlInput: String, tokenInput: String): SaveResult {
        val url = normalizeServerUrl(urlInput)
        if (url.isEmpty()) return SaveResult.Rejected("接続先を入れてください")
        val candidate = Connection(url, tokenInput.trim())
        return when (val result = api.fetchStatus(candidate)) {
            is FetchResult.Success -> {
                storage.saveConnection(candidate)
                storage.saveSnapshot(Snapshot(result.status, currentTimeMillis()))
                onSaved()
                SaveResult.Saved
            }
            is FetchResult.Unreachable -> {
                storage.saveConnection(candidate)
                SaveResult.SavedButUnreachable(result.problem(candidate)!!.message)
            }
            else -> SaveResult.Rejected(result.problem(candidate)!!.message)
        }
    }
}
