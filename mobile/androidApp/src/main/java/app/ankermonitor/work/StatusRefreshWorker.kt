package app.ankermonitor.work

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.ankermonitor.appContainer
import app.ankermonitor.shared.refreshSnapshot
import app.ankermonitor.widget.AnkerWidget
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/** /status を 1 回読んでスナップショットに書き、ウィジェットを描き直す */
class StatusRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = applicationContext.appContainer
        val connection = container.storage.connection.first() ?: return Result.success()
        refreshSnapshot(container.api, container.storage, connection, container.storage.snapshot.first())
        AnkerWidget().updateAll(applicationContext)
        // 失敗も「古い値 + 理由」としてウィジェットに出すので、WorkManager の再試行には回さない (次の 15 分で読み直す)
        return Result.success()
    }

    companion object {
        private const val PERIODIC = "status-refresh"
        private const val NOW = "status-refresh-now"

        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** WorkManager の定期実行の最短間隔 (15 分)。KEEP なので、起動のたびに間隔がずれることはない */
        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<StatusRefreshWorker>(15, TimeUnit.MINUTES)
                .setConstraints(network)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun refreshNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<StatusRefreshWorker>().setConstraints(network).build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
