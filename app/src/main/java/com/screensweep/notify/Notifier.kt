package com.screensweep.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.screensweep.MainActivity
import com.screensweep.R
import com.screensweep.data.ImageFolder

object Notifier {

    private const val CHANNEL_ID = "auto_clean"
    private const val NOTIFICATION_ID = 1001

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "自动清理",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "自动清理完成后的结果通知" }
        )
    }

    fun notifyCleanResult(
        context: Context,
        deletedCount: Int,
        freedBytes: Long,
        newFolders: List<ImageFolder> = emptyList()
    ) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cleanText = if (deletedCount > 0) {
            "清理了 $deletedCount 个过期文件，释放约 " +
                Formatter.formatShortFileSize(context, freedBytes)
        } else {
            "没有需要清理的文件"
        }
        val folderText = if (newFolders.isNotEmpty()) {
            val folderNames = newFolders.take(3).joinToString("、") { it.label }
            "发现新的图片文件夹：$folderNames${if (newFolders.size > 3) " 等" else ""}。" +
                "请到 Google Photos 检查备份。"
        } else ""
        val text = listOf(cleanText, folderText).filter { it.isNotEmpty() }.joinToString("\n")
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (newFolders.isEmpty()) "自动清理完成" else "自动清理后发现新文件夹")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
        }
    }
}
