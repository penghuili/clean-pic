package com.screensweep.data

import android.content.Context
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.tombstoneDataStore by preferencesDataStore(name = "tombstones")

/**
 * 已删除文件的墓碑记录：本地删除成功后先记下文件名，
 * 直到云端同步确认删除后才移除，保证删除最终传播到 Google Drive。
 */
data class Tombstone(
    val cloudKey: String?,
    val name: String,
    val deletedAtMs: Long,
    val sizeBytes: Long
)

class TombstoneStore(private val context: Context) {

    private object Keys {
        val ITEMS = stringSetPreferencesKey("tombstone_items")
    }

    fun countFlow(): Flow<Int> =
        context.tombstoneDataStore.data.map { it[Keys.ITEMS]?.size ?: 0 }

    suspend fun snapshot(): List<Tombstone> =
        context.tombstoneDataStore.data.first()[Keys.ITEMS]
            .orEmpty()
            .mapNotNull(::decode)

    /** 本地删除成功后调用，等待下次同步处理 */
    suspend fun record(cloudKey: String, name: String, sizeBytes: Long) {
        context.tombstoneDataStore.edit { p ->
            p[Keys.ITEMS] =
                (p[Keys.ITEMS] ?: emptySet()) +
                encode(cloudKey, name, System.currentTimeMillis(), sizeBytes)
        }
    }

    /** 云端删除成功（或云端本来就没有该文件）后调用 */
    suspend fun remove(t: Tombstone) {
        context.tombstoneDataStore.edit { p ->
            p[Keys.ITEMS] = (p[Keys.ITEMS] ?: emptySet()) - encode(t)
        }
    }

    private fun encode(cloudKey: String, name: String, ms: Long, size: Long): String =
        "v2|$cloudKey|${encodeText(name)}|$ms|$size"

    private fun encode(t: Tombstone): String =
        if (t.cloudKey == null) {
            "${encodeText(t.name)}|${t.deletedAtMs}|${t.sizeBytes}"
        } else {
            encode(t.cloudKey, t.name, t.deletedAtMs, t.sizeBytes)
        }

    private fun encodeText(value: String): String =
        Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    private fun decode(raw: String): Tombstone? {
        // Base64 字母表不含 '|'，可安全作为分隔符
        val parts = raw.split('|')
        if (parts.size == 5 && parts[0] == "v2") {
            val name = decodeText(parts[2]) ?: return null
            val ms = parts[3].toLongOrNull() ?: return null
            val size = parts[4].toLongOrNull() ?: return null
            return Tombstone(parts[1].ifEmpty { return null }, name, ms, size)
        }
        if (parts.size != 3) return null
        val name = decodeText(parts[0]) ?: return null
        val ms = parts[1].toLongOrNull() ?: return null
        val size = parts[2].toLongOrNull() ?: return null
        return Tombstone(null, name, ms, size)
    }

    private fun decodeText(raw: String): String? = runCatching {
        String(Base64.decode(raw, Base64.NO_WRAP), Charsets.UTF_8)
    }.getOrNull()
}
