package com.screensweep

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.screensweep.notify.Notifier
import com.screensweep.work.CleanWorker
import java.util.concurrent.TimeUnit

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        Notifier.ensureChannel(this)
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            AUTO_CLEAN_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CleanWorker>(1, TimeUnit.DAYS).build()
        )
        // 1.1.0 曾注册过云端同步任务；升级后立即取消，避免已移除的 Worker 被再次唤起。
        WorkManager.getInstance(this).cancelUniqueWork("drive_sync_once")
        WorkManager.getInstance(this).cancelUniqueWork("drive_sync_periodic")
    }

    companion object {
        const val AUTO_CLEAN_WORK = "auto_clean_daily"
    }
}
