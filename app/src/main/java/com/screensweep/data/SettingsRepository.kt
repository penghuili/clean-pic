package com.screensweep.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")
private val defaultAutoCleanFolders = setOf(
    ImageSources.SCREENSHOTS,
    ImageSources.CHATGPT,
    ImageSources.DOWNLOADS
)

data class Settings(
    val autoCleanEnabled: Boolean = false,
    val retainDays: Int = 7,
    val autoCleanHour: Int = 3,
    val autoCleanMinute: Int = 0,
    val lastAutoCleanAt: Long? = null,
    val keptPaths: Set<String> = emptySet(),
    val keptIds: Set<String> = emptySet(),
    val autoCleanFolders: Set<String> = defaultAutoCleanFolders,
    val customFolderUris: Set<String> = emptySet()
)

data class ImageFolderCheck(
    val folders: List<ImageFolder> = emptyList(),
    val firstCheck: Boolean = false
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val AUTO = booleanPreferencesKey("auto_clean_enabled")
        val DAYS = intPreferencesKey("retain_days")
        val AUTO_HOUR = intPreferencesKey("auto_clean_hour")
        val AUTO_MINUTE = intPreferencesKey("auto_clean_minute")
        val AUTO_SCHEDULE_VERSION = intPreferencesKey("auto_clean_schedule_version")
        val LAST_AUTO_CLEAN_AT = longPreferencesKey("last_auto_clean_at")
        val KEPT_PATHS = stringSetPreferencesKey("kept_paths")
        val KEPT_IDS = stringSetPreferencesKey("kept_ids")
        val AUTO_FOLDERS = stringSetPreferencesKey("auto_clean_folders")
        val CUSTOM_FOLDERS = stringSetPreferencesKey("custom_folders")
        val DOWNLOAD_SOURCE_MIGRATED = booleanPreferencesKey("download_source_migrated")
        val KNOWN_IMAGE_FOLDERS = stringSetPreferencesKey("known_image_folders")
        val IMAGE_FOLDER_BASELINE = booleanPreferencesKey("image_folder_baseline")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            autoCleanEnabled = p[Keys.AUTO] ?: false,
            retainDays = (p[Keys.DAYS] ?: 7).coerceIn(3, 7),
            autoCleanHour = (p[Keys.AUTO_HOUR] ?: 3).coerceIn(0, 23),
            autoCleanMinute = (p[Keys.AUTO_MINUTE] ?: 0).coerceIn(0, 59),
            lastAutoCleanAt = p[Keys.LAST_AUTO_CLEAN_AT],
            keptPaths = p[Keys.KEPT_PATHS] ?: emptySet(),
            keptIds = p[Keys.KEPT_IDS] ?: emptySet(),
            autoCleanFolders = p[Keys.AUTO_FOLDERS] ?: defaultAutoCleanFolders,
            customFolderUris = p[Keys.CUSTOM_FOLDERS] ?: emptySet()
        )
    }

    suspend fun setAutoClean(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO] = enabled }
    }

    suspend fun recordAutoCleanRun(at: Long = System.currentTimeMillis()) {
        context.dataStore.edit { it[Keys.LAST_AUTO_CLEAN_AT] = at }
    }

    /** 1.4.3 首次启动时重排一次旧任务，之后启动只保留现有任务。 */
    suspend fun shouldMigrateAutoCleanSchedule(): Boolean {
        var shouldMigrate = false
        context.dataStore.edit { p ->
            if ((p[Keys.AUTO_SCHEDULE_VERSION] ?: 0) < 1) {
                p[Keys.AUTO_SCHEDULE_VERSION] = 1
                shouldMigrate = true
            }
        }
        return shouldMigrate
    }

    suspend fun ensureDownloadSourceEnabled() {
        context.dataStore.edit { p ->
            if (p[Keys.DOWNLOAD_SOURCE_MIGRATED] != true) {
                p[Keys.AUTO_FOLDERS] = (p[Keys.AUTO_FOLDERS] ?: defaultAutoCleanFolders) +
                    ImageSources.DOWNLOADS
                p[Keys.DOWNLOAD_SOURCE_MIGRATED] = true
            }
        }
    }

    suspend fun setRetainDays(days: Int) {
        context.dataStore.edit { it[Keys.DAYS] = days.coerceIn(3, 7) }
    }

    suspend fun setAutoCleanTime(hour: Int, minute: Int) {
        context.dataStore.edit {
            it[Keys.AUTO_HOUR] = hour.coerceIn(0, 23)
            it[Keys.AUTO_MINUTE] = minute.coerceIn(0, 59)
        }
    }

    suspend fun setAutoCleanFolder(sourceKey: String, enabled: Boolean) {
        context.dataStore.edit { p ->
            val current = p[Keys.AUTO_FOLDERS] ?: defaultAutoCleanFolders
            p[Keys.AUTO_FOLDERS] = if (enabled) current + sourceKey else current - sourceKey
        }
    }

    suspend fun addCustomFolder(uri: String) {
        context.dataStore.edit { p ->
            p[Keys.CUSTOM_FOLDERS] = (p[Keys.CUSTOM_FOLDERS] ?: emptySet()) + uri
            p[Keys.AUTO_FOLDERS] =
                (p[Keys.AUTO_FOLDERS] ?: defaultAutoCleanFolders) + ImageSources.customKey(uri)
        }
    }

    suspend fun removeCustomFolder(uri: String) {
        context.dataStore.edit { p ->
            p[Keys.CUSTOM_FOLDERS] = (p[Keys.CUSTOM_FOLDERS] ?: emptySet()) - uri
            p[Keys.AUTO_FOLDERS] =
                (p[Keys.AUTO_FOLDERS] ?: defaultAutoCleanFolders) - ImageSources.customKey(uri)
        }
    }

    suspend fun keepItems(paths: Set<String>, ids: Set<String>) {
        context.dataStore.edit { p ->
            p[Keys.KEPT_PATHS] = (p[Keys.KEPT_PATHS] ?: emptySet()) + paths
            p[Keys.KEPT_IDS] = (p[Keys.KEPT_IDS] ?: emptySet()) + ids
        }
    }

    suspend fun unkeepPath(path: String) {
        context.dataStore.edit { p ->
            p[Keys.KEPT_PATHS] = (p[Keys.KEPT_PATHS] ?: emptySet()) - path
        }
    }

    /**
     * 记录本次看到的图片文件夹，并返回本次以前没有见过的文件夹。
     * 这是提醒机制的本地基线，不代表 Google Photos 的备份状态。
     */
    suspend fun rememberImageFolders(current: List<ImageFolder>): ImageFolderCheck {
        var result = ImageFolderCheck()
        context.dataStore.edit { p ->
            val baselineCreated = p[Keys.IMAGE_FOLDER_BASELINE] ?: false
            val known = p[Keys.KNOWN_IMAGE_FOLDERS] ?: emptySet()
            val currentKeys = current.map { it.key }.toSet()
            result = ImageFolderCheck(
                folders = current
                    .filter { !baselineCreated || it.key !in known }
                    .sortedBy { it.key.lowercase() },
                firstCheck = !baselineCreated
            )
            p[Keys.KNOWN_IMAGE_FOLDERS] = known + currentKeys
            p[Keys.IMAGE_FOLDER_BASELINE] = true
        }
        return result
    }
}
