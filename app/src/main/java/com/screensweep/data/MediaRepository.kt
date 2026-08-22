package com.screensweep.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.media.MediaScannerConnection
import java.io.File

enum class ImageFolder(val key: String, val label: String) {
    SCREENSHOTS("screenshots", "截图"),
    CHATGPT("chatgpt", "ChatGPT");

    companion object {
        fun fromKey(key: String): ImageFolder? = values().firstOrNull { it.key == key }
    }
}

data class ShotItem(
    val id: Long,
    val name: String,
    val path: String,
    val bucket: String,
    val addedMs: Long,
    val size: Long,
    val folder: ImageFolder = ImageFolder.SCREENSHOTS
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

    companion object {
        private const val KEPT_RELATIVE_PATH = "Pictures/ScreenSweep/Kept/"
        private const val SCREENSHOTS_RELATIVE_PATH = "Pictures/Screenshots/"
    }

    /**
     * 查询截图目录和 ChatGPT 目录里的图片，绝不动其他照片。
     */
    fun queryScreenshots(): List<ShotItem> {
        val bucketColumn = MediaStore.Images.Media.BUCKET_DISPLAY_NAME
        val selection: String
        val args: List<String>
        if (Build.VERSION.SDK_INT >= 29) {
            selection = "(" + MediaStore.Images.Media.RELATIVE_PATH + " LIKE ?" +
                " OR " + bucketColumn + " LIKE ?" +
                " OR " + bucketColumn + " IN (?,?)" +
                " OR " + MediaStore.Images.Media.RELATIVE_PATH + " LIKE ?" +
                " OR " + bucketColumn + " LIKE ? )"
            args = listOf(
                "%Screenshots%", "%Screenshot%", "截屏", "屏幕截图",
                "%ChatGPT%", "%ChatGPT%"
            )
        } else {
            selection = "(" + MediaStore.Images.Media.DATA + " LIKE ?" +
                " OR " + bucketColumn + " LIKE ?" +
                " OR " + bucketColumn + " IN (?,?)" +
                " OR " + MediaStore.Images.Media.DATA + " LIKE ?" +
                " OR " + bucketColumn + " LIKE ? )"
            args = listOf(
                "%Screenshot%", "%Screenshot%", "截屏", "屏幕截图",
                "%ChatGPT%", "%ChatGPT%"
            )
        }
        return queryImages(selection, args.toTypedArray()).filter { it.looksLikeManagedImage }
    }

    /** Images physically moved to the app's kept-images folder. */
    fun queryKeptScreenshots(): List<ShotItem> {
        val selection: String
        val args: Array<String>
        if (Build.VERSION.SDK_INT >= 29) {
            selection = MediaStore.Images.Media.RELATIVE_PATH + " LIKE ?"
            args = arrayOf("$KEPT_RELATIVE_PATH%")
        } else {
            selection = MediaStore.Images.Media.DATA + " LIKE ?"
            args = arrayOf("${keptDirectory().absolutePath}${File.separator}%")
        }
        return queryImages(selection, args)
    }

    private fun queryImages(selection: String, args: Array<String>): List<ShotItem> {
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

        val result = mutableListOf<ShotItem>()
        try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection.toTypedArray(),
                selection,
                args,
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
                        size = c.getLong(iSize),
                        folder = classifyFolder(
                            if (iData >= 0) c.getString(iData) ?: "" else "",
                            if (iBucket >= 0) c.getString(iBucket) ?: "" else ""
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }
        return result
    }

    private fun classifyFolder(path: String, bucket: String): ImageFolder =
        if (path.contains("chatgpt", true) || bucket.contains("chatgpt", true)) {
            ImageFolder.CHATGPT
        } else {
            ImageFolder.SCREENSHOTS
        }

    /** 最后一道保险：只有路径或相册名确实是受支持的图片目录才允许操作 */
    private val ShotItem.looksLikeManagedImage: Boolean
        get() = path.contains("screenshot", true)
            || bucket.contains("screenshot", true)
            || bucket == "截屏"
            || bucket == "屏幕截图"
            || path.contains("chatgpt", true)
            || bucket.contains("chatgpt", true)

    /** Move a managed image out of its source folder into the kept folder. */
    fun moveShotToKept(item: ShotItem): Boolean {
        if (!item.looksLikeManagedImage) return false
        if (Build.VERSION.SDK_INT >= 29) {
            return try {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.RELATIVE_PATH, KEPT_RELATIVE_PATH)
                }
                context.contentResolver.update(item.uri, values, null, null) > 0
            } catch (_: Exception) {
                false
            }
        }
        return moveLegacyFile(item.path, keptDirectory())
    }

    /** Restore a kept screenshot to the standard Pictures/Screenshots folder. */
    fun restoreKeptShot(item: ShotItem): Boolean {
        if (Build.VERSION.SDK_INT >= 29) {
            return try {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.RELATIVE_PATH, SCREENSHOTS_RELATIVE_PATH)
                }
                context.contentResolver.update(item.uri, values, null, null) > 0
            } catch (_: Exception) {
                false
            }
        }
        return moveLegacyFile(item.path, screenshotsDirectory())
    }

    private fun keptDirectory(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
        "ScreenSweep/Kept"
    )

    private fun screenshotsDirectory(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
        "Screenshots"
    )

    private fun moveLegacyFile(sourcePath: String, targetDirectory: File): Boolean {
        if (sourcePath.isEmpty()) return false
        val source = File(sourcePath)
        if (!source.isFile) return false
        if (!targetDirectory.exists() && !targetDirectory.mkdirs()) return false

        val extension = source.extension
        val baseName = source.nameWithoutExtension
        var target = File(targetDirectory, source.name)
        var suffix = 1
        while (target.exists()) {
            val name = if (extension.isEmpty()) "$baseName-$suffix" else "$baseName-$suffix.$extension"
            target = File(targetDirectory, name)
            suffix++
        }
        if (!source.renameTo(target)) return false
        MediaScannerConnection.scanFile(
            context,
            arrayOf(source.absolutePath, target.absolutePath),
            arrayOf(null, null),
            null
        )
        return true
    }

    fun deleteShot(item: ShotItem): Boolean {
        if (!item.looksLikeManagedImage) return false
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

    /** 删除超过 [olderThanMs] 的受支持图片，跳过已保留项；返回 (删除数量, 释放字节数) */
    fun cleanOldScreenshots(
        olderThanMs: Long,
        keptPaths: Set<String>,
        keptIds: Set<String>,
        enabledFolders: Set<ImageFolder>
    ): Pair<Int, Long> {
        var count = 0
        var bytes = 0L
        for (s in queryScreenshots()) {
            if (s.folder !in enabledFolders) continue
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
