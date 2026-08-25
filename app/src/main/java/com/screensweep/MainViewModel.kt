package com.screensweep

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.screensweep.data.ImageFolderCheck
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
    val shots: List<ShotItem>,
    val keptShots: List<ShotItem>
)

private data class DeleteResult(
    val count: Int,
    val bytes: Long,
    val folderCheck: ImageFolderCheck
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
                    val existingShots = (
                        mediaRepo.queryScreenshots() +
                        mediaRepo.queryCustomFolders(savedSettings.customFolderUris) +
                        mediaRepo.queryDownloadFiles()
                    ).distinctBy { if (it.path.isNotBlank()) it.path else it.uri.toString() }
                RefreshData(
                    shots = existingShots,
                    keptShots = mediaRepo.queryKeptScreenshots()
                )
            }
            _shots.value = data.shots
            _keptShots.value = data.keptShots
            _scanning.value = false
        }
    }

    fun deleteShots(items: List<ShotItem>, onDone: (Int, Long, ImageFolderCheck) -> Unit) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                // 在删除前取快照，避免删掉某文件夹最后一张图片后漏提醒。
                val hasImages = items.any { it.isImage }
                val folderSnapshot = if (hasImages) mediaRepo.queryImageFolders() else emptyList()
                var c = 0
                var b = 0L
                for (s in items) {
                    if (mediaRepo.deleteShot(s)) {
                        c++
                        b += s.size
                    }
                }
                DeleteResult(
                    count = c,
                    bytes = b,
                    folderCheck = if (c > 0 && hasImages) {
                        settingsRepo.rememberImageFolders(folderSnapshot)
                    } else {
                        ImageFolderCheck()
                    }
                )
            }
            refresh()
            onDone(result.count, result.bytes, result.folderCheck)
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

    fun keepNonImageFiles(items: List<ShotItem>, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            settingsRepo.keepItems(items.map { it.path }.toSet(), emptySet())
            refresh()
            onDone()
        }
    }

    fun restoreKeptShot(item: ShotItem, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val restored = withContext(Dispatchers.IO) { mediaRepo.restoreKeptShot(item) }
            refresh()
            onDone(restored)
        }
    }

    fun setAutoClean(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setAutoClean(enabled) }
    }

    fun setRetainDays(days: Int) {
        viewModelScope.launch { settingsRepo.setRetainDays(days) }
    }

    fun setAutoCleanTime(hour: Int, minute: Int) {
        viewModelScope.launch {
            settingsRepo.setAutoCleanTime(hour, minute)
            App.scheduleAutoClean(getApplication(), hour, minute)
        }
    }

    fun setAutoCleanFolder(sourceKey: String, enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setAutoCleanFolder(sourceKey, enabled) }
    }

    fun addCustomFolder(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            getApplication<Application>().contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: SecurityException) {
            try {
                getApplication<Application>().contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                return
            }
        }
        viewModelScope.launch {
            settingsRepo.addCustomFolder(uri.toString())
            refresh()
        }
    }

    fun removeCustomFolder(uri: String) {
        viewModelScope.launch {
            settingsRepo.removeCustomFolder(uri)
            refresh()
        }
    }

    fun unkeepPath(path: String) {
        viewModelScope.launch { settingsRepo.unkeepPath(path) }
    }

    /** 手动触发一次与自动清理同样规则的清理 */
    fun cleanNow(onDone: (Int, Long, ImageFolderCheck) -> Unit) {
        viewModelScope.launch {
            val s = settings.value ?: return@launch
            val cutoff = System.currentTimeMillis() - s.retainDays * 24L * 60 * 60 * 1000
            val result = withContext(Dispatchers.IO) {
                // 同样使用删除前快照，保证清空新文件夹时也能提示。
                val folderSnapshot = mediaRepo.queryImageFolders()
                val deleted = mediaRepo.cleanOldScreenshots(
                    cutoff,
                    s.keptPaths,
                    s.keptIds,
                    s.autoCleanFolders,
                    s.customFolderUris
                )
                DeleteResult(
                    count = deleted.first,
                    bytes = deleted.second,
                    folderCheck = if (deleted.first > 0) {
                        settingsRepo.rememberImageFolders(folderSnapshot)
                    } else {
                        ImageFolderCheck()
                    }
                )
            }
            refresh()
            onDone(result.count, result.bytes, result.folderCheck)
        }
    }
}
