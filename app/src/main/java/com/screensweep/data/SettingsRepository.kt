package com.screensweep.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")
private val defaultAutoCleanFolders = ImageFolder.values().map { it.key }.toSet()

data class Settings(
    val autoCleanEnabled: Boolean = false,
    val retainDays: Int = 7,
    val keptPaths: Set<String> = emptySet(),
    val keptIds: Set<String> = emptySet(),
    val autoCleanFolders: Set<String> = defaultAutoCleanFolders
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val AUTO = booleanPreferencesKey("auto_clean_enabled")
        val DAYS = intPreferencesKey("retain_days")
        val KEPT_PATHS = stringSetPreferencesKey("kept_paths")
        val KEPT_IDS = stringSetPreferencesKey("kept_ids")
        val AUTO_FOLDERS = stringSetPreferencesKey("auto_clean_folders")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            autoCleanEnabled = p[Keys.AUTO] ?: false,
            retainDays = (p[Keys.DAYS] ?: 7).coerceIn(1, 90),
            keptPaths = p[Keys.KEPT_PATHS] ?: emptySet(),
            keptIds = p[Keys.KEPT_IDS] ?: emptySet(),
            autoCleanFolders = p[Keys.AUTO_FOLDERS] ?: defaultAutoCleanFolders
        )
    }

    suspend fun setAutoClean(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO] = enabled }
    }

    suspend fun setRetainDays(days: Int) {
        context.dataStore.edit { it[Keys.DAYS] = days.coerceIn(1, 90) }
    }

    suspend fun setAutoCleanFolder(folder: ImageFolder, enabled: Boolean) {
        context.dataStore.edit { p ->
            val current = p[Keys.AUTO_FOLDERS] ?: defaultAutoCleanFolders
            p[Keys.AUTO_FOLDERS] = if (enabled) current + folder.key else current - folder.key
        }
    }

    suspend fun keepItems(paths: Set<String>, ids: Set<String>) {
        context.dataStore.edit { p ->
            p[Keys.KEPT_PATHS] = (p[Keys.KEPT_PATHS] ?: emptySet()) + paths
            p[Keys.KEPT_IDS] = (p[Keys.KEPT_IDS] ?: emptySet()) + ids
        }
    }

    suspend fun removeKeptItems(paths: Set<String>, ids: Set<String>) {
        context.dataStore.edit { p ->
            p[Keys.KEPT_PATHS] = (p[Keys.KEPT_PATHS] ?: emptySet()) - paths
            p[Keys.KEPT_IDS] = (p[Keys.KEPT_IDS] ?: emptySet()) - ids
        }
    }

    suspend fun unkeepPath(path: String) {
        context.dataStore.edit { p ->
            p[Keys.KEPT_PATHS] = (p[Keys.KEPT_PATHS] ?: emptySet()) - path
        }
    }
}
