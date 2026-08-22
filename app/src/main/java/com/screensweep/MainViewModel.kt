package com.screensweep

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.screensweep.data.DownloadItem
import com.screensweep.data.ImageFolder
import com.screensweep.data.MediaRepository
import com.screensweep.data.Settings
import com.screensweep.data.SettingsRepository
import com.screensweep.data.ShotItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class RefreshData(
    val migratedPaths: Set<String>,
    val migratedIds: Set<String>,
    val shots: List<ShotItem>,
    val keptShots: List<ShotItem>,
    val downloads: List<DownloadItem>
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsRepo = SettingsRepository(app)
    private val mediaRepo = MediaRepository(app)

    val settings: StateFlow<Settings?> = settingsRepo.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _shots = MutableStateFlow<List<ShotItem>>(emptyList())
    val shots: StateFlow<List<ShotItem>> = _shots.asStateFlow()

    private val _keptShots = MutableStateFlow<List<ShotItem>>(emptyList())
    val keptShots: StateFlow<List<ShotItem>> = _keptShots.asStateFlow()

    private val _downloads = MutableStateFlow<List<DownloadItem>>(emptyList())
    val downloads: StateFlow<List<DownloadItem>> = _downloads.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _scanning.value = true
            val savedSettings = settingsRepo.settings.first()
            val data = withContext(Dispatchers.IO) {
                // Older app versions only marked kept screenshots in DataStore.
                // Move those files once so the folder becomes the source of truth.
                val existingShots = mediaRepo.queryScreenshots()
                val legacyKept = existingShots.filter {
                    it.path in savedSettings.keptPaths || it.id.toString() in savedSettings.keptIds
                }
                val migrated = legacyKept.filter { mediaRepo.moveShotToKept(it) }
                RefreshData(
                    migratedPaths = migrated.map { it.path }.toSet(),
                    migratedIds = migrated.map { it.id.toString() }.toSet(),
                    shots = mediaRepo.queryScreenshots(),
                    keptShots = mediaRepo.queryKeptScreenshots(),
                    downloads = mediaRepo.queryDownloads()
                )
            }
            if (data.migratedPaths.isNotEmpty() || data.migratedIds.isNotEmpty()) {
                settingsRepo.removeKeptItems(data.migratedPaths, data.migratedIds)
            }
            _shots.value = data.shots
            _keptShots.value = data.keptShots
            _downloads.value = data.downloads
            _scanning.value = false
        }
    }

    fun deleteShots(items: List<ShotItem>, onDone: (Int, Long) -> Unit) {
        viewModelScope.launch {
            val (count, bytes) = withContext(Dispatchers.IO) {
                var c = 0
                var b = 0L
                for (s in items) {
                    if (mediaRepo.deleteShot(s)) {
                        c++
                        b += s.size
                    }
                }
                c to b
            }
            refresh()
            onDone(count, bytes)
        }
    }

    fun deleteDownloads(items: List<DownloadItem>, onDone: (Int, Long) -> Unit) {
        viewModelScope.launch {
            val (count, bytes) = withContext(Dispatchers.IO) {
                var c = 0
                var b = 0L
                for (d in items) {
                    if (mediaRepo.deleteDownload(d)) {
                        c++
                        b += d.size
                    }
                }
                c to b
            }
            refresh()
            onDone(count, bytes)
        }
    }

    fun keepShots(items: List<ShotItem>, onDone: (Int, Int) -> Unit) {
        viewModelScope.launch {
            val (kept, failed) = withContext(Dispatchers.IO) {
                var kept = 0
                var failed = 0
                items.forEach {
                    if (mediaRepo.moveShotToKept(it)) kept++ else failed++
                }
                kept to failed
            }
            refresh()
            onDone(kept, failed)
        }
    }

    fun restoreKeptShot(item: ShotItem, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val restored = withContext(Dispatchers.IO) { mediaRepo.restoreKeptShot(item) }
            refresh()
            onDone(restored)
        }
    }

    fun keepDownloads(items: List<DownloadItem>, onDone: () -> Unit) {
        viewModelScope.launch {
            settingsRepo.keepItems(items.map { it.path }.toSet(), emptySet())
            refresh()
            onDone()
        }
    }

    fun setAutoClean(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setAutoClean(enabled) }
    }

    fun setRetainDays(days: Int) {
        viewModelScope.launch { settingsRepo.setRetainDays(days) }
    }

    fun setAutoCleanFolder(folder: ImageFolder, enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setAutoCleanFolder(folder, enabled) }
    }

    fun unkeepPath(path: String) {
        viewModelScope.launch { settingsRepo.unkeepPath(path) }
    }

    /** 手动触发一次与自动清理同样规则的清理 */
    fun cleanNow(onDone: (Int, Long) -> Unit) {
        viewModelScope.launch {
            val s = settings.value ?: return@launch
            val cutoff = System.currentTimeMillis() - s.retainDays * 24L * 60 * 60 * 1000
            val (count, bytes) = withContext(Dispatchers.IO) {
                mediaRepo.cleanOldScreenshots(
                    cutoff,
                    s.keptPaths,
                    s.keptIds,
                    s.autoCleanFolders.mapNotNull { ImageFolder.fromKey(it) }.toSet()
                )
            }
            refresh()
            onDone(count, bytes)
        }
    }
}
