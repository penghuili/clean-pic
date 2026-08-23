package com.screensweep.drive

import android.accounts.AccountManager
import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.screensweep.data.MediaRepository
import com.screensweep.data.Settings
import com.screensweep.data.ShotItem
import com.screensweep.data.Tombstone
import com.screensweep.data.TombstoneStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

sealed class DriveSyncResult {
    data class Ok(val uploaded: Int, val removed: Int) : DriveSyncResult()
    data class NeedsConsent(val intent: Intent) : DriveSyncResult()
    data class Error(val message: String, val retryable: Boolean = true) : DriveSyncResult()
}

/**
 * 把受管图片镜像到用户 Drive 的 CleanPic 文件夹。
 * 每个文件通过私有 appProperties 唯一键匹配，删除时绝不依赖可能重复的文件名。
 */
class DriveSyncManager(private val context: Context) {

    private class Auth(private val context: Context, private val account: String) {
        var consent: Intent? = null
            private set
        private var cached: String? = null

        fun token(): String? {
            cached?.let { return it }
            consent = null
            return try {
                GoogleAuthUtil.getToken(context, account, SCOPE).also { cached = it }
            } catch (e: UserRecoverableAuthException) {
                consent = e.intent
                null
            } catch (_: Exception) {
                null
            }
        }

        fun invalidate() {
            cached?.let { runCatching { GoogleAuthUtil.clearToken(context, it) } }
            cached = null
        }
    }

    private data class RemoteFile(val id: String, val cloudKey: String)
    private data class HttpResponse(val code: Int, val body: ByteArray?)
    private class DriveRequestException(
        message: String,
        val statusCode: Int? = null,
        val retryable: Boolean = true
    ) : Exception(message)

    suspend fun sync(settings: Settings): DriveSyncResult = syncMutex.withLock {
        withContext(Dispatchers.IO) { syncLocked(settings) }
    }

    private suspend fun syncLocked(settings: Settings): DriveSyncResult {
        val account = settings.driveAccount
            ?: return DriveSyncResult.Error("尚未登录 Google 账号", retryable = false)
        val auth = Auth(context, account)
        var token = auth.token() ?: return consentOrConnectionError(auth)

        repeat(2) { attempt ->
            try {
                return syncWithToken(settings, token)
            } catch (e: DriveRequestException) {
                if (e.statusCode == HttpURLConnection.HTTP_UNAUTHORIZED && attempt == 0) {
                    auth.invalidate()
                    token = auth.token() ?: return consentOrConnectionError(auth)
                } else {
                    return DriveSyncResult.Error(e.message ?: "Google Drive 同步失败", e.retryable)
                }
            } catch (_: Exception) {
                return DriveSyncResult.Error("Google Drive 返回了无法识别的数据，请稍后重试")
            }
        }
        return DriveSyncResult.Error("Google Drive 授权已失效，请重新授权", retryable = false)
    }

    private suspend fun syncWithToken(settings: Settings, token: String): DriveSyncResult {
        val folderId = ensureFolder(token)
        val remoteByKey = listRemote(token, folderId).groupByTo(mutableMapOf()) { it.cloudKey }
        val tombstoneStore = TombstoneStore(context)
        var removed = 0

        for (tombstone in tombstoneStore.snapshot()) {
            val cloudKey = tombstone.cloudKey
            if (cloudKey == null) {
                // 旧记录只有文件名，无法证明对应哪一个云端文件；不执行可能误删的操作。
                tombstoneStore.remove(tombstone)
                continue
            }
            val matches = remoteByKey.remove(cloudKey).orEmpty()
            for (remote in matches) {
                deleteFile(token, remote.id)
                removed++
            }
            tombstoneStore.remove(tombstone)
        }

        val pendingKeys = tombstoneStore.snapshot().mapNotNullTo(mutableSetOf()) { it.cloudKey }
        val repository = MediaRepository(context)
        val images = (
            repository.queryScreenshots() +
                repository.queryCustomFolders(settings.customFolderUris)
            ).distinctBy { it.uri.toString() }

        var uploaded = 0
        for (image in images) {
            val cloudKey = DriveFileIdentity.keyFor(image)
            if (cloudKey in pendingKeys || remoteByKey.containsKey(cloudKey)) continue
            uploadFile(token, folderId, image, cloudKey)
            uploaded++
            remoteByKey[cloudKey] = mutableListOf(RemoteFile("local", cloudKey))
        }
        return DriveSyncResult.Ok(uploaded, removed)
    }

    private fun consentOrConnectionError(auth: Auth): DriveSyncResult =
        auth.consent?.let { DriveSyncResult.NeedsConsent(it) }
            ?: DriveSyncResult.Error("无法连接 Google Drive，请检查网络后重试")

    private fun ensureFolder(token: String): String {
        val query = "name = '$FOLDER_NAME' and mimeType = '$FOLDER_MIME' and trashed = false"
        val url = BASE + "/files?q=" + URLEncoder.encode(query, "UTF-8") +
            "&fields=" + URLEncoder.encode("files(id,name)", "UTF-8") + "&pageSize=10"
        val listed = requireSuccess(request(token, url, "GET"), "读取云端文件夹失败")
        val folders = JSONObject(listed.bodyText()).optJSONArray("files") ?: JSONArray()
        for (i in 0 until folders.length()) {
            val folder = folders.getJSONObject(i)
            if (folder.optString("name") == FOLDER_NAME) return folder.getString("id")
        }

        val metadata = JSONObject()
            .put("name", FOLDER_NAME)
            .put("mimeType", FOLDER_MIME)
            .toString()
            .toByteArray(Charsets.UTF_8)
        val created = requireSuccess(
            request(token, "$BASE/files?fields=id", "POST", metadata, "application/json; charset=UTF-8"),
            "创建云端文件夹失败"
        )
        return JSONObject(created.bodyText()).optString("id")
            .ifEmpty { throw DriveRequestException("创建云端文件夹失败：响应中没有文件 ID") }
    }

    private fun listRemote(token: String, folderId: String): List<RemoteFile> {
        val query = "'$folderId' in parents and trashed = false"
        val baseUrl = "$BASE/files?q=" + URLEncoder.encode(query, "UTF-8") +
            "&pageSize=1000&orderBy=name&fields=" +
            URLEncoder.encode("nextPageToken,files(id,name,appProperties)", "UTF-8")
        val result = mutableListOf<RemoteFile>()
        var pageToken: String? = null
        do {
            val pageUrl = if (pageToken == null) baseUrl else
                baseUrl + "&pageToken=" + URLEncoder.encode(pageToken, "UTF-8")
            val response = requireSuccess(request(token, pageUrl, "GET"), "读取云端图片失败")
            val json = JSONObject(response.bodyText())
            val files = json.optJSONArray("files") ?: JSONArray()
            for (i in 0 until files.length()) {
                val file = files.getJSONObject(i)
                val id = file.optString("id")
                val cloudKey = file.optJSONObject("appProperties")
                    ?.optString(APP_PROPERTY_KEY)
                    .orEmpty()
                if (id.isNotEmpty() && cloudKey.isNotEmpty()) result += RemoteFile(id, cloudKey)
            }
            pageToken = json.optString("nextPageToken").ifEmpty { null }
        } while (pageToken != null)
        return result
    }

    private fun uploadFile(token: String, folderId: String, item: ShotItem, cloudKey: String) {
        val bytes = context.contentResolver.openInputStream(item.uri)?.use { it.readBytes() }
            ?: throw DriveRequestException("无法读取图片：${item.name}", retryable = false)
        val mime = mimeFor(item.name)
        val metadata = JSONObject()
            .put("name", item.name)
            .put("mimeType", mime)
            .put("parents", JSONArray().put(folderId))
            .put("appProperties", JSONObject().put(APP_PROPERTY_KEY, cloudKey))
        val boundary = "cleanpic${System.currentTimeMillis()}"
        val body = ("--$boundary\r\n" +
            "Content-Type: application/json; charset=UTF-8\r\n\r\n" + metadata + "\r\n" +
            "--$boundary\r\nContent-Type: $mime\r\n\r\n").toByteArray(Charsets.UTF_8) +
            bytes + "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        requireSuccess(
            request(
                token,
                "$UPLOAD/files?uploadType=multipart&fields=id",
                "POST",
                body,
                "multipart/related; boundary=$boundary"
            ),
            "上传图片失败：${item.name}"
        )
    }

    private fun deleteFile(token: String, fileId: String) {
        val response = request(token, "$BASE/files/$fileId", "DELETE")
        if (response.code == HttpURLConnection.HTTP_NOT_FOUND) return
        requireSuccess(response, "删除云端图片失败")
    }

    private fun request(
        token: String,
        url: String,
        method: String,
        body: ByteArray? = null,
        contentType: String? = null
    ): HttpResponse {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.setRequestProperty("Content-Type", contentType ?: "application/octet-stream")
                connection.outputStream.use { it.write(body) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            return HttpResponse(code, stream?.use { it.readBytes() })
        } catch (e: Exception) {
            throw DriveRequestException("网络请求失败：${e.message ?: "未知错误"}")
        } finally {
            connection.disconnect()
        }
    }

    private fun requireSuccess(response: HttpResponse, operation: String): HttpResponse {
        if (response.code in 200..299) return response
        val retryable = response.code == HttpURLConnection.HTTP_CLIENT_TIMEOUT ||
            response.code == 429 || response.code >= 500 ||
            response.code == HttpURLConnection.HTTP_UNAUTHORIZED
        throw DriveRequestException(
            "$operation（HTTP ${response.code}）",
            statusCode = response.code,
            retryable = retryable
        )
    }

    private fun HttpResponse.bodyText(): String = body?.toString(Charsets.UTF_8)
        ?: throw DriveRequestException("Google Drive 返回了空响应")

    private fun mimeFor(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(
            name.substringAfterLast('.', "").lowercase()
        ) ?: "image/jpeg"

    companion object {
        private const val SCOPE = "oauth2:https://www.googleapis.com/auth/drive.file"
        private const val FOLDER_NAME = "CleanPic"
        private const val BASE = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        private const val FOLDER_MIME = "application/vnd.google-apps.folder"
        private const val APP_PROPERTY_KEY = "cleanPicKey"
        private val syncMutex = Mutex()

        fun newAccountPickerIntent(): Intent =
            AccountManager.newChooseAccountIntent(
                null,
                null,
                arrayOf("com.google"),
                null,
                null,
                null,
                null
            )

        fun accountNameFromResult(data: Intent?): String? =
            data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
    }
}
