package com.screensweep.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.screensweep.App
import com.screensweep.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 定时触发入口。
 *
 * - 闹钟到点：交给 [CleanWorker] 的一次性任务执行，避免文件操作超出广播的时间限制
 * - 开机、系统时间/时区变化、应用更新、精确闹钟授权变化：重新对齐下一次触发
 *
 * 每次触发都会顺手重设下一天的闹钟，因此长期不打开应用也能保持准点。
 */
class CleanAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val triggered = intent.action == CleanAlarmScheduler.ACTION_CLEAN
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (triggered) CleanWorker.enqueueNow(appContext)
                val settings = SettingsRepository(appContext).settings.first()
                if (settings.autoCleanEnabled) {
                    App.scheduleAutoClean(
                        appContext,
                        settings.autoCleanHour,
                        settings.autoCleanMinute
                    )
                } else {
                    CleanAlarmScheduler.cancel(appContext)
                }
            } catch (_: Exception) {
                // 后台唤醒失败不做处理，等待下一次触发或用户打开应用时重新对齐。
            } finally {
                pending.finish()
            }
        }
    }
}
