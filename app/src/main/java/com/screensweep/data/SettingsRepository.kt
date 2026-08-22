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

data class Settings(
    val autoCleanEnabled: Boolean = false,
    val retainDays: Int = 7,
    val keptPaths: Set<String> = emptySet(),
    val keptIds: Set<String> = emptySet()
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val AUTO = booleanPreferencesKey("auto_clean_enabled")
        val DAYS = intPreferencesKey("retain_days")
        val KEPT_PATHS = stringSetPreferencesKey("kept_paths")
        val KEPT_IDS = stringSetPreferencesKey("kept_ids")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            autoCleanEnabled = p[Keys.AUTO] ?: false,
            retainDays = (p[Keys.DAYS] ?: 7).coerceIn(1, 90),
            keptPaths = p[Keys.KEPT_PATHS] ?: emptySet(),
            keptIds = p[Keys.KEPT_IDS] ?: emptySet()
        )
    }

    suspend fun setAutoClean(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO] = enabled }
    }

    suspend fun setRetainDays(days: Int) {
        context.dataStore.edit { it[Keys.DAYS] = days.coerceIn(1, 90) }
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
