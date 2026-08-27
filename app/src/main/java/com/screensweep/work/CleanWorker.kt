package com.screensweep.work

import android.content.Context
import androidx.work.CoroutineWorker
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
 */
class CleanWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext
        // 记录 Worker 真正被系统唤起的时间，便于区分“未运行”和“运行后没有可删文件”。
        SettingsRepository(app).recordAutoCleanRun()
        if (!Permissions.hasStorageAccess(app)) return Result.success()

        val settings = SettingsRepository(app).settings.first()
        if (!settings.autoCleanEnabled) return Result.success()

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
}
