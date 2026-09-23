package com.screensweep.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.screensweep.util.Permissions
import java.util.Calendar

/**
 * 每天固定时刻的定时器。
 *
 * 触发时刻每次都按本地时钟字段重新计算（“下一个 HH:mm”），而不是在“上次实际运行时间”上
 * 加 24 小时，所以系统某天延迟唤起不会把后面几天一起推后。
 */
object CleanAlarmScheduler {

    const val ACTION_CLEAN = "com.screensweep.action.AUTO_CLEAN_ALARM"
    private const val REQUEST_CODE = 2001

    /** 下一次触发时刻，永远是未来，用于设定闹钟和展示“下次运行”。 */
    fun nextTriggerAt(hour: Int, minute: Int, now: Long = System.currentTimeMillis()): Long =
        triggerAt(hour, minute, now, mustBeFuture = true)

    /** 距离 now 最近的目标时刻（可能已经过去），用于判断当天是否已经执行过。 */
    fun currentTriggerAt(hour: Int, minute: Int, now: Long = System.currentTimeMillis()): Long =
        triggerAt(hour, minute, now, mustBeFuture = false)

    private fun triggerAt(hour: Int, minute: Int, now: Long, mustBeFuture: Boolean): Long {
        val target = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
            set(Calendar.MINUTE, minute.coerceIn(0, 59))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        // 按天加减（而不是加 24 小时），夏令时切换后仍然是同一个时钟时刻。
        if (mustBeFuture) {
            while (target.timeInMillis <= now) target.add(Calendar.DAY_OF_YEAR, 1)
        } else {
            while (target.timeInMillis > now) target.add(Calendar.DAY_OF_YEAR, -1)
        }
        return target.timeInMillis
    }

    /** 重新设定下一次触发。重复调用是幂等的，不会把时间越推越后。 */
    fun schedule(context: Context, hour: Int, minute: Int) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val triggerAt = nextTriggerAt(hour, minute)
        val pendingIntent = pendingIntent(context)
        if (Permissions.canScheduleExactAlarms(context)) {
            try {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                return
            } catch (_: SecurityException) {
                // 权限在检查之后被撤销，退回下面的非精确闹钟。
            }
        }
        // 未授予「闹钟与提醒」时只能设定不精确的闹钟：触发时刻仍然对准目标，
        // 实际唤起时间由系统决定，可能会延后。
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java)
            ?.cancel(pendingIntent(context))
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, CleanAlarmReceiver::class.java)
            .setAction(ACTION_CLEAN)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
