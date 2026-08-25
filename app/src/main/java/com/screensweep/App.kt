package com.screensweep

import android.app.Application
import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.screensweep.data.SettingsRepository
import com.screensweep.notify.Notifier
import com.screensweep.work.CleanWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.concurrent.TimeUnit

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        Notifier.ensureChannel(this)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val settings = SettingsRepository(this@App).settings.first()
            scheduleAutoClean(this@App, settings.autoCleanHour, settings.autoCleanMinute)
        }
        // 1.1.0 曾注册过云端同步任务；升级后立即取消，避免已移除的 Worker 被再次唤起。
        WorkManager.getInstance(this).cancelUniqueWork("drive_sync_once")
        WorkManager.getInstance(this).cancelUniqueWork("drive_sync_periodic")
    }

    companion object {
        const val AUTO_CLEAN_WORK = "auto_clean_daily"

        fun scheduleAutoClean(context: Context, hour: Int, minute: Int) {
            val now = Calendar.getInstance()
            val nextRun = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
                set(Calendar.MINUTE, minute.coerceIn(0, 59))
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (!after(now)) add(Calendar.DAY_OF_YEAR, 1)
            }
            val initialDelay = (nextRun.timeInMillis - now.timeInMillis).coerceAtLeast(1_000L)
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                AUTO_CLEAN_WORK,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<CleanWorker>(1, TimeUnit.DAYS)
                    .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
                    .build()
            )
        }
    }
}
