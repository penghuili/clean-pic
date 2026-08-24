package com.screensweep.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.media.MediaScannerConnection
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import java.io.File

data class ImageSource(
    val key: String,
    val label: String,
    val treeUri: String? = null
) {
    val isCustom: Boolean
        get() = treeUri != null
}

object ImageSources {
    const val SCREENSHOTS = "screenshots"
    const val CHATGPT = "chatgpt"
    private const val CUSTOM_PREFIX = "tree:"

    val builtIns = listOf(
        ImageSource(SCREENSHOTS, "截图"),
        ImageSource(CHATGPT, "ChatGPT")
    )

    fun customKey(treeUri: String): String = CUSTOM_PREFIX + treeUri

    fun isCustomKey(key: String): Boolean = key.startsWith(CUSTOM_PREFIX)
}

fun labelForTreeUri(context: Context, treeUri: String): String =
    DocumentFile.fromTreeUri(context, Uri.parse(treeUri))?.name
        ?: Uri.parse(treeUri).lastPathSegment?.substringAfterLast(':')
        ?: "自定义文件夹"

data class ShotItem(
    val id: Long,
    val name: String,
    val path: String,
    val bucket: String,
    val addedMs: Long,
    val size: Long,
    val sourceKey: String = ImageSources.SCREENSHOTS,
    val sourceLabel: String = "截图",
    val customUri: Uri? = null
) {
    val uri: Uri
        get() = customUri
            ?: ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
}

data class DownloadItem(
    val name: String,
    val path: String,
    val size: Long,
    val modifiedMs: Long
)

data class ImageFolder(
    val key: String,
    val label: String,
    val imageCount: Int
)

class MediaRepository(private val context: Context) {

    companion object {
        private const val KEPT_RELATIVE_PATH = "Pictures/CleanPic/Kept/"
        private const val SCREENSHOTS_RELATIVE_PATH = "Pictures/Screenshots/"
    }

    /** 查询设备媒体库中所有包含图片的文件夹，不修改任何文件。 */
    fun queryImageFolders(): List<ImageFolder> {
        val projection = mutableListOf(
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME
        )
        if (Build.VERSION.SDK_INT >= 29) {
            projection += MediaStore.Images.Media.RELATIVE_PATH
        } else {
            projection += MediaStore.Images.Media.DATA
        }

        val counts = linkedMapOf<String, Int>()
        try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection.toTypedArray(),
                null,
                null,
                null
            )?.use { c ->
                val iBucket = c.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                val iLocation = if (Build.VERSION.SDK_INT >= 29) {
                    c.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
                } else {
                    c.getColumnIndex(MediaStore.Images.Media.DATA)
                }
                while (c.moveToNext()) {
                    val bucket = if (iBucket >= 0) c.getString(iBucket).orEmpty() else ""
                    val location = if (iLocation >= 0) c.getString(iLocation).orEmpty() else ""
                    val key = imageFolderKey(location, bucket)
                    if (key.isNotEmpty()) counts[key] = (counts[key] ?: 0) + 1
                }
            }
        } catch (_: Exception) {
            return emptyList()
        }
        return counts.map { (key, count) ->
            ImageFolder(key = key, label = imageFolderLabel(key), imageCount = count)
        }.sortedBy { it.key.lowercase() }
    }

    private fun imageFolderKey(location: String, bucket: String): String {
        if (Build.VERSION.SDK_INT >= 29 && location.isNotBlank()) {
            return location.trim().trim('/').replace(Regex("/+"), "/")
        }
        if (location.isNotBlank()) {
            val parent = File(location).parent
            if (!parent.isNullOrBlank()) return parent
        }
        return bucket.trim()
    }

    private fun imageFolderLabel(key: String): String =
        key.split('/').filter { it.isNotBlank() }.joinToString(" / ")

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
                        sourceKey = classifySourceKey(
                            if (iData >= 0) c.getString(iData) ?: "" else "",
                            if (iBucket >= 0) c.getString(iBucket) ?: "" else ""
                        ),
                        sourceLabel = classifySourceLabel(
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

    private fun classifySourceKey(path: String, bucket: String): String =
        if (path.contains("chatgpt", true) || bucket.contains("chatgpt", true)) {
            ImageSources.CHATGPT
        } else {
            ImageSources.SCREENSHOTS
        }

    private fun classifySourceLabel(path: String, bucket: String): String =
        if (path.contains("chatgpt", true) || bucket.contains("chatgpt", true)) {
            "ChatGPT"
        } else {
            "截图"
        }

    fun queryCustomFolders(treeUris: Set<String>): List<ShotItem> =
        treeUris.flatMap { queryCustomFolder(it) }
            .distinctBy { it.uri.toString() }

    private fun queryCustomFolder(treeUri: String): List<ShotItem> {
        val root = DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) ?: return emptyList()
        val label = root.name ?: labelForTreeUri(context, treeUri)
        val sourceKey = ImageSources.customKey(treeUri)
        return collectCustomImages(root, sourceKey, label)
    }

    private fun collectCustomImages(
        directory: DocumentFile,
        sourceKey: String,
        sourceLabel: String
    ): List<ShotItem> {
        return directory.listFiles().flatMap { file ->
            when {
                file.isDirectory -> collectCustomImages(file, sourceKey, sourceLabel)
                file.isFile && isImageName(file.name.orEmpty()) -> listOf(
                    ShotItem(
                        id = file.uri.toString().hashCode().toLong(),
                        name = file.name ?: "图片",
                        path = file.uri.toString(),
                        bucket = sourceLabel,
                        addedMs = file.lastModified(),
                        size = file.length(),
                        sourceKey = sourceKey,
                        sourceLabel = sourceLabel,
                        customUri = file.uri
                    )
                )
                else -> emptyList()
            }
        }
    }

    private fun isImageName(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in
            setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "bmp", "avif")

    /** 最后一道保险：只有路径或相册名确实是受支持的图片目录才允许操作 */
    private val ShotItem.looksLikeManagedImage: Boolean
        get() = ImageSources.isCustomKey(sourceKey)
            || path.contains("screenshot", true)
            || bucket.contains("screenshot", true)
            || bucket == "截屏"
            || bucket == "屏幕截图"
            || path.contains("chatgpt", true)
            || bucket.contains("chatgpt", true)

    /** Move a managed image out of its source folder into the kept folder. */
    fun moveShotToKept(item: ShotItem): Boolean {
        if (!item.looksLikeManagedImage) return false
        if (ImageSources.isCustomKey(item.sourceKey)) {
            return copyCustomFileToKept(item)
        }
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
        "CleanPic/Kept"
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

        val target = uniqueTarget(targetDirectory, source.name)
        if (!source.renameTo(target)) return false
        MediaScannerConnection.scanFile(
            context,
            arrayOf(source.absolutePath, target.absolutePath),
            arrayOf(null, null),
            null
        )
        return true
    }

    private fun uniqueTarget(directory: File, sourceName: String): File {
        var target = File(directory, sourceName)
        val extension = sourceName.substringAfterLast('.', "")
        val baseName = sourceName.substringBeforeLast('.', sourceName)
        var suffix = 1
        while (target.exists()) {
            val name = if (extension.isEmpty()) "$baseName-$suffix" else "$baseName-$suffix.$extension"
            target = File(directory, name)
            suffix++
        }
        return target
    }

    private fun copyCustomFileToKept(item: ShotItem): Boolean {
        val targetName = item.name
        return if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, targetName)
                put(MediaStore.Images.Media.MIME_TYPE, mimeTypeFor(targetName))
                put(MediaStore.Images.Media.RELATIVE_PATH, KEPT_RELATIVE_PATH)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val targetUri = try {
                context.contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    values
                )
            } catch (_: Exception) {
                null
            } ?: return false

            val copied = try {
                context.contentResolver.openInputStream(item.uri).use { input ->
                    context.contentResolver.openOutputStream(targetUri).use { output ->
                        if (input == null || output == null) false
                        else {
                            input.copyTo(output)
                            true
                        }
                    }
                }
            } catch (_: Exception) {
                false
            }
            val deleted = if (copied) {
                try {
                    DocumentFile.fromSingleUri(context, item.uri)?.delete() == true
                } catch (_: Exception) {
                    false
                }
            } else {
                false
            }
            if (copied && deleted) {
                context.contentResolver.update(
                    targetUri,
                    ContentValues().apply {
                        put(MediaStore.Images.Media.IS_PENDING, 0)
                    },
                    null,
                    null
                )
                true
            } else {
                context.contentResolver.delete(targetUri, null, null)
                false
            }
        } else {
            val targetDirectory = keptDirectory()
            if (!targetDirectory.exists() && !targetDirectory.mkdirs()) return false
            val target = uniqueTarget(targetDirectory, targetName)
            val copied = try {
                context.contentResolver.openInputStream(item.uri).use { input ->
                    target.outputStream().use { output ->
                        if (input == null) false else {
                            input.copyTo(output)
                            true
                        }
                    }
                }
            } catch (_: Exception) {
                false
            }
            if (!copied) {
                target.delete()
                return false
            }
            val deleted = try {
                DocumentFile.fromSingleUri(context, item.uri)?.delete() == true
            } catch (_: Exception) {
                false
            }
            if (!deleted) {
                target.delete()
                return false
            }
            MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), null, null)
            true
        }
    }

    private fun mimeTypeFor(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(
            name.substringAfterLast('.', "").lowercase()
        ) ?: "image/*"

    fun deleteShot(item: ShotItem): Boolean {
        if (!item.looksLikeManagedImage) return false
        if (ImageSources.isCustomKey(item.sourceKey)) {
            return try {
                DocumentFile.fromSingleUri(context, item.uri)?.delete() == true
            } catch (_: Exception) {
                false
            }
        }
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
        enabledSources: Set<String>,
        customFolderUris: Set<String>
    ): Pair<Int, Long> {
        var count = 0
        var bytes = 0L
        val allImages = (queryScreenshots() + queryCustomFolders(customFolderUris))
            .distinctBy { it.uri.toString() }
        for (s in allImages) {
            if (s.sourceKey !in enabledSources) continue
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
