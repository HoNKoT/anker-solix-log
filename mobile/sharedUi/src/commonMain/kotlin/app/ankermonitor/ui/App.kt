package app.ankermonitor.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Android / iOS 共通の画面の入口 */
@Composable
fun App(viewModel: MainViewModel) {
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf(false) }

    when (val c = connection) {
        ConnectionState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        ConnectionState.NotSet -> SetupScreen(initial = null, onSave = viewModel::testAndSave, onDone = {}, onCancel = null)
        is ConnectionState.Set -> if (editing) {
            PlatformBackHandler(enabled = true) { editing = false }
            SetupScreen(
                initial = c.connection,
                onSave = viewModel::testAndSave,
                onDone = { editing = false },
                onCancel = { editing = false },
            )
        } else {
            val state by viewModel.monitor.collectAsStateWithLifecycle()
            MonitorScreen(connection = c.connection, state = state, onOpenSettings = { editing = true })
        }
    }
}
