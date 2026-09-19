package com.screensweep.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.screensweep.data.MediaRepository
import com.screensweep.data.ImageFolderCheck
import com.screensweep.data.SettingsRepository
import com.screensweep.notify.Notifier
import com.screensweep.util.Permissions
import kotlinx.coroutines.flow.first

/**
 * 每天执行一次：若用户开启了自动清理，则删除超过保留天数的文件
 * （已保留的条目永远跳过），完成后发送通知。
 *
 * 精确闹钟到点后由 [enqueueNow] 唤起本 Worker，WorkManager 的周期任务只作兜底；
 * 两个来源都按同一个目标时刻判断，保证一天只真正执行一次。
 */
class CleanWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext
        val settingsRepo = SettingsRepository(app)
        val settings = settingsRepo.settings.first()
        if (!settings.autoCleanEnabled) return Result.success()
        if (!Permissions.hasStorageAccess(app)) return Result.success()

        // 当天目标时刻已经执行过就静默跳过，避免兜底任务重复清理和重复通知。
        val target = CleanAlarmScheduler.currentTriggerAt(
            settings.autoCleanHour,
            settings.autoCleanMinute
        )
        if ((settings.lastAutoCleanAt ?: 0L) >= target) return Result.success()

        // 记录 Worker 真正被系统唤起的时间，便于区分“未运行”和“运行后没有可删文件”。
        settingsRepo.recordAutoCleanRun()

        val cutoff = System.currentTimeMillis() - settings.retainDays * 24L * 60 * 60 * 1000
        val mediaRepo = MediaRepository(app)
        val folderSnapshot = mediaRepo.queryImageFolders()
        val (count, bytes) = mediaRepo.cleanOldScreenshots(
            cutoff,
            settings.keptPaths,
            settings.keptIds,
            settings.autoCleanFolders,
            settings.customFolderUris
        )

        val folderCheck = if (count > 0) {
            SettingsRepository(app).rememberImageFolders(
                folderSnapshot
            )
        } else {
            ImageFolderCheck()
        }
        // 即使没有过期文件也发一次结果，配合设置页时间戳让后台执行可见。
        Notifier.notifyCleanResult(app, count, bytes, folderCheck.folders)
        return Result.success()
    }

    companion object {
        const val NOW_WORK = "auto_clean_now"

        /** 精确闹钟到点后立即执行一次；是否真的需要清理由 Worker 自己判断。 */
        fun enqueueNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                NOW_WORK,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<CleanWorker>().build()
            )
        }
    }
}
