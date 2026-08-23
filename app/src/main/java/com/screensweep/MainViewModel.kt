package com.screensweep

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.screensweep.data.DownloadItem
import com.screensweep.data.MediaRepository
import com.screensweep.data.Settings
import com.screensweep.data.SettingsRepository
import com.screensweep.data.ShotItem
import com.screensweep.data.TombstoneStore
import com.screensweep.drive.DriveSyncManager
import com.screensweep.drive.DriveSyncResult
import com.screensweep.work.DriveSyncScheduler
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
    val keptShots: List<ShotItem>,
    val downloads: List<DownloadItem>
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsRepo = SettingsRepository(app)
    private val mediaRepo = MediaRepository(app)
    private val tombstoneStore = TombstoneStore(app)

    val settings: StateFlow<Settings?> = settingsRepo.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** 等待传播到云端的删除条数 */
    val pendingCloudDeletions: StateFlow<Int> = tombstoneStore.countFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

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
                val existingShots = (
                    mediaRepo.queryScreenshots() +
                        mediaRepo.queryCustomFolders(savedSettings.customFolderUris)
                    ).distinctBy { it.uri.toString() }
                RefreshData(
                    shots = existingShots,
                    keptShots = mediaRepo.queryKeptScreenshots(),
                    downloads = mediaRepo.queryDownloads()
                )
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
            if (count > 0) requestDriveSyncIfEnabled()
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
    fun cleanNow(onDone: (Int, Long) -> Unit) {
        viewModelScope.launch {
            val s = settings.value ?: return@launch
            val cutoff = System.currentTimeMillis() - s.retainDays * 24L * 60 * 60 * 1000
            val (count, bytes) = withContext(Dispatchers.IO) {
                mediaRepo.cleanOldScreenshots(
                    cutoff,
                    s.keptPaths,
                    s.keptIds,
                    s.autoCleanFolders,
                    s.customFolderUris
                )
            }
            refresh()
            onDone(count, bytes)
            if (count > 0) requestDriveSyncIfEnabled()
        }
    }

    // ---------- Google Drive 同步 ----------

    fun signInDrive(accountName: String?) {
        if (accountName.isNullOrEmpty()) return
        viewModelScope.launch {
            settingsRepo.setDriveAccount(accountName)
        }
    }

    fun signOutDrive() {
        viewModelScope.launch {
            settingsRepo.setDriveAccount(null)
            DriveSyncScheduler.cancel(getApplication())
        }
    }

    fun setDriveSync(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepo.setDriveEnabled(enabled)
            if (enabled) {
                DriveSyncScheduler.schedulePeriodic(getApplication())
                requestDriveSyncIfEnabled()
            } else {
                DriveSyncScheduler.cancel(getApplication())
            }
        }
    }

    /** 手动触发一次同步；需要授权时通过回调把 Intent 交给 UI 拉起授权页 */
    fun syncDriveNow(onResult: (DriveSyncResult) -> Unit) {
        viewModelScope.launch {
            val s = settingsRepo.settings.first()
            val result = withContext(Dispatchers.IO) {
                try {
                    DriveSyncManager(getApplication()).sync(s)
                } catch (e: Exception) {
                    DriveSyncResult.Error("同步失败：${e.message ?: "未知错误"}")
                }
            }
            onResult(result)
        }
    }

    private fun requestDriveSyncIfEnabled() {
        val app = getApplication<Application>()
        viewModelScope.launch {
            val s = settingsRepo.settings.first()
            if (s.driveEnabled && !s.driveAccount.isNullOrEmpty()) {
                DriveSyncScheduler.requestSyncAfterDeletion(app)
            }
        }
    }
}
