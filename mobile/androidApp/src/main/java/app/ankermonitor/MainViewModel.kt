package app.ankermonitor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.ankermonitor.shared.Connection
import app.ankermonitor.shared.FetchResult
import app.ankermonitor.shared.MonitorState
import app.ankermonitor.shared.Snapshot
import app.ankermonitor.shared.normalizeServerUrl
import app.ankermonitor.shared.problem
import app.ankermonitor.work.StatusRefreshWorker
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

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val container = application.appContainer

    val connection: StateFlow<ConnectionState> = container.storage.connection
        .map { it?.let(ConnectionState::Set) ?: ConnectionState.NotSet }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ConnectionState.Loading)

    // 画面が見えている間だけ読み続ける (WhileSubscribed: 裏に回って 5 秒で止まる)
    @OptIn(ExperimentalCoroutinesApi::class)
    val monitor: StateFlow<MonitorState> = connection
        .flatMapLatest { state ->
            if (state is ConnectionState.Set) container.poller.poll(state.connection) else flowOf(MonitorState(loading = false))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MonitorState())

    fun onAppForeground() = container.pushToWidgetOnNextSuccess()

    // 初回は保存した瞬間に設定画面が消えて呼び出し元のスコープが止まるので、続きの保存は viewModelScope で最後までやる
    suspend fun testAndSave(urlInput: String, tokenInput: String): SaveResult =
        viewModelScope.async { checkAndSave(urlInput, tokenInput) }.await()

    private suspend fun checkAndSave(urlInput: String, tokenInput: String): SaveResult {
        val url = normalizeServerUrl(urlInput)
        if (url.isEmpty()) return SaveResult.Rejected("接続先を入れてください")
        val candidate = Connection(url, tokenInput.trim())
        val result = container.api.fetchStatus(candidate)
        return when (result) {
            is FetchResult.Success -> {
                container.storage.saveConnection(candidate)
                container.storage.saveSnapshot(Snapshot(result.status, System.currentTimeMillis()))
                StatusRefreshWorker.refreshNow(getApplication())
                SaveResult.Saved
            }
            is FetchResult.Unreachable -> {
                container.storage.saveConnection(candidate)
                SaveResult.SavedButUnreachable(result.problem(candidate)!!.message)
            }
            else -> SaveResult.Rejected(result.problem(candidate)!!.message)
        }
    }
}
