package com.screensweep.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

data class ShotItem(
    val id: Long,
    val name: String,
    val path: String,
    val bucket: String,
    val addedMs: Long,
    val size: Long
) {
    val uri: Uri
        get() = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
}

data class DownloadItem(
    val name: String,
    val path: String,
    val size: Long,
    val modifiedMs: Long
)

class MediaRepository(private val context: Context) {

    /**
     * 查询系统截图目录里的图片（Pictures/Screenshots、DCIM/Screenshots、
     * 以及部分厂商的「截屏 / 屏幕截图」相册），绝不动其他照片。
     */
    fun queryScreenshots(): List<ShotItem> {
        val projection = mutableListOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATA
        )
        if (Build.VERSION.SDK_INT >= 29) {
            projection.add(MediaStore.Images.Media.RELATIVE_PATH)
        }

        val bucketColumn = MediaStore.Images.Media.BUCKET_DISPLAY_NAME
        val selection: String
        val args: List<String>
        if (Build.VERSION.SDK_INT >= 29) {
            selection = "(" + MediaStore.Images.Media.RELATIVE_PATH + " LIKE ?" +
                " OR " + bucketColumn + " LIKE ?" +
                " OR " + bucketColumn + " IN (?,?))"
            args = listOf("%Screenshots%", "%Screenshot%", "截屏", "屏幕截图")
        } else {
            selection = "(" + bucketColumn + " LIKE ? OR " + bucketColumn + " IN (?,?))"
            args = listOf("%Screenshot%", "截屏", "屏幕截图")
        }

        val result = mutableListOf<ShotItem>()
        try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection.toTypedArray(),
                selection,
                args.toTypedArray(),
                MediaStore.Images.Media.DATE_ADDED + " DESC"
            )?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val iName = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val iBucket = c.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                val iDate = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val iSize = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val iData = c.getColumnIndex(MediaStore.Images.Media.DATA)
                while (c.moveToNext()) {
                    result += ShotItem(
                        id = c.getLong(iId),
                        name = c.getString(iName) ?: "",
                        path = if (iData >= 0) c.getString(iData) ?: "" else "",
                        bucket = if (iBucket >= 0) c.getString(iBucket) ?: "" else "",
                        addedMs = c.getLong(iDate) * 1000L,
                        size = c.getLong(iSize)
                    )
                }
            }
        } catch (_: Exception) {
        }
        return result.filter { it.looksLikeScreenshot }
    }

    /** 最后一道保险：只有路径或相册名确实是截图目录才允许删除 */
    private val ShotItem.looksLikeScreenshot: Boolean
        get() = path.contains("screenshot", true)
            || bucket.contains("screenshot", true)
            || bucket == "截屏"
            || bucket == "屏幕截图"

    fun deleteShot(item: ShotItem): Boolean {
        if (!item.looksLikeScreenshot) return false
        val viaProvider = try {
            context.contentResolver.delete(item.uri, null, null) > 0
        } catch (_: Exception) {
            false
        }
        if (viaProvider) return true
        if (item.path.isEmpty()) return false
        return try {
            File(item.path).delete()
        } catch (_: Exception) {
            false
        }
    }

    /** 删除超过 [olderThanMs] 的截图，跳过已保留项；返回 (删除数量, 释放字节数) */
    fun cleanOldScreenshots(
        olderThanMs: Long,
        keptPaths: Set<String>,
        keptIds: Set<String>
    ): Pair<Int, Long> {
        var count = 0
        var bytes = 0L
        for (s in queryScreenshots()) {
            if (s.addedMs < olderThanMs && s.path !in keptPaths && s.id.toString() !in keptIds) {
                if (deleteShot(s)) {
                    count++
                    bytes += s.size
                }
            }
        }
        return count to bytes
    }

    /** Download 目录顶层的文件（不递归），只做手动管理、永不自动删除 */
    fun queryDownloads(): List<DownloadItem> {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val files = dir.listFiles() ?: return emptyList()
        return files
            .filter { it.isFile && it.name != ".nomedia" }
            .map { DownloadItem(it.name, it.absolutePath, it.length(), it.lastModified()) }
            .sortedByDescending { it.modifiedMs }
    }

    fun deleteDownload(item: DownloadItem): Boolean {
        val downloadRoot = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS
        ).absolutePath
        if (!item.path.startsWith(downloadRoot)) return false
        return try {
            File(item.path).delete()
        } catch (_: Exception) {
            false
        }
    }
}
