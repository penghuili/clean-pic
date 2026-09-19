package com.screensweep

import android.app.Application
import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.screensweep.data.SettingsRepository
import com.screensweep.notify.Notifier
import com.screensweep.work.CleanAlarmScheduler
import com.screensweep.work.CleanWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        Notifier.ensureChannel(this)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val settingsRepo = SettingsRepository(this@App)
            settingsRepo.ensureDownloadSourceEnabled()
            val settings = settingsRepo.settings.first()
            if (settings.autoCleanEnabled) {
                scheduleAutoClean(this@App, settings.autoCleanHour, settings.autoCleanMinute)
            } else {
                cancelAutoClean(this@App)
            }
        }
        // 1.1.0 曾注册过云端同步任务；升级后立即取消，避免已移除的 Worker 被再次唤起。
        WorkManager.getInstance(this).cancelUniqueWork("drive_sync_once")
        WorkManager.getInstance(this).cancelUniqueWork("drive_sync_periodic")
    }

    companion object {
        /** 兜底任务名：精确闹钟被系统拦截时，保证当天仍然执行一次。 */
        const val AUTO_CLEAN_WORK = "auto_clean_daily"

        /** 兜底任务比目标时刻晚多久执行，留给精确闹钟先跑。 */
        private const val BACKSTOP_DELAY_MS = 15L * 60 * 1000

        /**
         * 按当前设置重新对齐定时。每次调用都按本地时钟重算下一次触发时刻，
         * 因此反复调用不会让执行时间越来越晚。
         */
        fun scheduleAutoClean(context: Context, hour: Int, minute: Int) {
            CleanAlarmScheduler.schedule(context, hour, minute)
            ensureBackstopScheduled(context, hour, minute)
        }

        /** 关闭自动清理时取消闹钟，避免无意义地唤醒设备。 */
        fun cancelAutoClean(context: Context) {
            CleanAlarmScheduler.cancel(context)
        }

        private fun ensureBackstopScheduled(context: Context, hour: Int, minute: Int) {
            val delay = (CleanAlarmScheduler.nextTriggerAt(hour, minute) -
                System.currentTimeMillis()).coerceAtLeast(0L) + BACKSTOP_DELAY_MS
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                AUTO_CLEAN_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<CleanWorker>(1, TimeUnit.DAYS)
                    .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                    .build()
            )
        }
    }
}
