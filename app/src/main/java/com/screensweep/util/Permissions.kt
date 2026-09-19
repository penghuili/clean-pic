package com.screensweep.util

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

object Permissions {

    /** Android 11+ 用「所有文件访问」，更早版本用读写存储权限 */
    fun hasStorageAccess(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun allFilesAccessIntent(context: Context): Intent {
        return try {
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
        } catch (_: Exception) {
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        }
    }

    fun hasNotificationAccess(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
    }

    fun appNotificationSettingsIntent(context: Context): Intent {
        return Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra("android.provider.extra.APP_PACKAGE", context.packageName)
    }

    /** Android 12+ 要准时触发需要「闹钟与提醒」特殊权限，未授权时系统只能延后执行。 */
    fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        val manager = context.getSystemService(AlarmManager::class.java) ?: return false
        return manager.canScheduleExactAlarms()
    }

    fun exactAlarmSettingsIntent(context: Context): Intent {
        return Intent(
            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
            Uri.parse("package:${context.packageName}")
        )
    }

    /** 部分国产系统会清理未加入省电白名单的应用，定时任务需要忽略电池优化。 */
    fun ignoresBatteryOptimizations(context: Context): Boolean {
        val manager = context.getSystemService(PowerManager::class.java) ?: return true
        return manager.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun batteryOptimizationSettingsIntent(context: Context): Intent {
        return Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}")
        )
    }
}
