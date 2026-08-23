package com.screensweep.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.screensweep.data.SettingsRepository
import com.screensweep.drive.DriveSyncManager
import com.screensweep.drive.DriveSyncResult
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * 有网络时执行一次 Drive 同步：上传新图 + 处理删除墓碑。
 * 未开启同步或未登录时直接跳过。
 */
class DriveSyncWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settings = SettingsRepository(applicationContext).settings.first()
        if (!settings.driveEnabled || settings.driveAccount.isNullOrEmpty()) {
            return Result.success()
        }
        return when (val result = DriveSyncManager(applicationContext).sync(settings)) {
            is DriveSyncResult.Ok -> Result.success()
            is DriveSyncResult.NeedsConsent -> Result.failure()
            is DriveSyncResult.Error -> if (result.retryable) Result.retry() else Result.failure()
        }
    }
}

object DriveSyncScheduler {

    private const val ONE_TIME_WORK = "drive_sync_once"
    private const val PERIODIC_WORK = "drive_sync_periodic"

    /** 删除图片后调用：有网就尽快传播删除 */
    fun requestSyncAfterDeletion(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            ONE_TIME_WORK,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<DriveSyncWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
        )
    }

    fun schedulePeriodic(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<DriveSyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(ONE_TIME_WORK)
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK)
    }
}
