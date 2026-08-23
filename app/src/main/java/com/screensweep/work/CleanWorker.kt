package com.screensweep.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.screensweep.data.MediaRepository
import com.screensweep.data.SettingsRepository
import com.screensweep.notify.Notifier
import com.screensweep.util.Permissions
import kotlinx.coroutines.flow.first

/**
 * 每天执行一次：若用户开启了自动清理，则删除超过保留天数的截图
 * （已保留的条目永远跳过），完成后发送通知。
 */
class CleanWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext
        if (!Permissions.hasStorageAccess(app)) return Result.success()

        val settings = SettingsRepository(app).settings.first()
        if (!settings.autoCleanEnabled) return Result.success()

        val cutoff = System.currentTimeMillis() - settings.retainDays * 24L * 60 * 60 * 1000
        val (count, bytes) = MediaRepository(app)
            .cleanOldScreenshots(
                cutoff,
                settings.keptPaths,
                settings.keptIds,
                settings.autoCleanFolders,
                settings.customFolderUris
            )

        if (count > 0) {
            Notifier.notifyCleanResult(app, count, bytes)
        }
        return Result.success()
    }
}
