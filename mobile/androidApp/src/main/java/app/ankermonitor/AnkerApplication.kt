package app.ankermonitor

import android.app.Application
import android.content.Context
import androidx.glance.appwidget.updateAll
import app.ankermonitor.shared.AnkerApi
import app.ankermonitor.shared.DataStoreMonitorStorage
import app.ankermonitor.shared.Snapshot
import app.ankermonitor.shared.StatusPoller
import app.ankermonitor.shared.StatusResponse
import app.ankermonitor.shared.createAnkerHttpClient
import app.ankermonitor.widget.AnkerWidget
import app.ankermonitor.work.StatusRefreshWorker

class AnkerApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        StatusRefreshWorker.schedulePeriodic(this)
    }
}

val Context.appContainer: AppContainer get() = (applicationContext as AnkerApplication).container

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val storage = DataStoreMonitorStorage(appContext)
    val api = AnkerApi(createAnkerHttpClient())
    val poller = StatusPoller(api, onSuccess = ::pushToWidget)

    @Volatile
    private var lastWidgetPushMillis = 0L

    /** 次に読めた値を、待たずにウィジェットへ反映させる (アプリを開いたとき) */
    fun pushToWidgetOnNextSuccess() {
        lastWidgetPushMillis = 0L
    }

    // 画面は 3 秒おきに読むが、ウィジェットの描き直しと DataStore への書き込みは 1 分に 1 回で足りる
    private suspend fun pushToWidget(status: StatusResponse) {
        val now = System.currentTimeMillis()
        if (now - lastWidgetPushMillis < WIDGET_PUSH_INTERVAL_MILLIS) return
        lastWidgetPushMillis = now
        storage.saveSnapshot(Snapshot(status, now))
        AnkerWidget().updateAll(appContext)
    }

    private companion object {
        const val WIDGET_PUSH_INTERVAL_MILLIS = 60_000L
    }
}
