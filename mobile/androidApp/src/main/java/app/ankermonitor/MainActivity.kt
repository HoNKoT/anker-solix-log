package app.ankermonitor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ankermonitor.ui.MonitorScreen
import app.ankermonitor.ui.SetupScreen

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                App(viewModel)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.onAppForeground()
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    // minSdk 26 なので、ダイナミックカラー (12 以上) が無い端末は既定の配色にする
    val scheme = when {
        android.os.Build.VERSION.SDK_INT >= 31 && isSystemInDarkTheme() -> dynamicDarkColorScheme(context)
        android.os.Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
        isSystemInDarkTheme() -> androidx.compose.material3.darkColorScheme()
        else -> androidx.compose.material3.lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
private fun App(viewModel: MainViewModel) {
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf(false) }

    when (val c = connection) {
        ConnectionState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        ConnectionState.NotSet -> SetupScreen(
            initial = null,
            onSave = viewModel::testAndSave,
            onDone = {},
            onCancel = null,
        )
        is ConnectionState.Set -> if (editing) {
            BackHandler { editing = false }
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
