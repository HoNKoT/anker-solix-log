package app.ankermonitor.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import app.ankermonitor.shared.AnkerApi
import app.ankermonitor.shared.StatusPoller
import app.ankermonitor.shared.UserDefaultsMonitorStorage
import app.ankermonitor.shared.createAnkerHttpClient
import platform.UIKit.UIViewController

/** iOS アプリ (SwiftUI) から呼ぶ入口 */
@Suppress("FunctionName", "unused")
fun MainViewController(): UIViewController = ComposeUIViewController {
    val viewModel = remember {
        val api = AnkerApi(createAnkerHttpClient())
        MainViewModel(UserDefaultsMonitorStorage(), api, StatusPoller(api), onSaved = {})
    }
    AnkerTheme { App(viewModel) }
}
