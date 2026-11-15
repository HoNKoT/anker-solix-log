package app.ankermonitor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.ankermonitor.ui.AnkerTheme
import app.ankermonitor.ui.App
import app.ankermonitor.ui.MainViewModel
import app.ankermonitor.work.StatusRefreshWorker

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels {
        viewModelFactory {
            initializer {
                val container = appContainer
                MainViewModel(container.storage, container.api, container.poller) {
                    StatusRefreshWorker.refreshNow(applicationContext)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AnkerTheme { App(viewModel) }
        }
    }

    override fun onStart() {
        super.onStart()
        appContainer.pushToWidgetOnNextSuccess()
    }
}
