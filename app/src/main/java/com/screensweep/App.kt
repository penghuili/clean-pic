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
    }

    companion object {
        const val AUTO_CLEAN_WORK = "auto_clean_daily"
    }
}
