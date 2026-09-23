package com.screensweep

import android.app.Application
import android.content.Context
import androidx.work.WorkManager
import com.screensweep.data.SettingsRepository
import com.screensweep.notify.Notifier
import com.screensweep.work.CleanAlarmScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
        val workManager = WorkManager.getInstance(this)
        workManager.cancelUniqueWork("drive_sync_once")
        workManager.cancelUniqueWork("drive_sync_periodic")
        // 1.5.1 起定时只由精确闹钟驱动，取消 1.5.0 残留的每日兜底任务。
        workManager.cancelUniqueWork(LEGACY_AUTO_CLEAN_WORK)
    }

    companion object {
        /** 1.5.0 的兜底周期任务名，仅用于升级后清理残留。 */
        private const val LEGACY_AUTO_CLEAN_WORK = "auto_clean_daily"

        /**
         * 按当前设置重新对齐定时。每次调用都按本地时钟重算下一次触发时刻，
         * 因此反复调用不会让执行时间越来越晚。
         */
        fun scheduleAutoClean(context: Context, hour: Int, minute: Int) {
            CleanAlarmScheduler.schedule(context, hour, minute)
        }

        /** 关闭自动清理时取消闹钟，避免无意义地唤醒设备。 */
        fun cancelAutoClean(context: Context) {
            CleanAlarmScheduler.cancel(context)
        }
    }
}
