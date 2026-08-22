package com.screensweep.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.screensweep.MainViewModel
import com.screensweep.data.DownloadItem
import com.screensweep.ui.components.EmptyState
import com.screensweep.ui.components.SelectionBottomBar
import com.screensweep.util.approximateSize
import com.screensweep.util.formatDateTime
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val allDownloads by vm.downloads.collectAsStateWithLifecycle()

    val keptPaths = settings?.keptPaths ?: emptySet()
    val downloads = remember(allDownloads, keptPaths) {
        allDownloads.filter { it.path !in keptPaths }
    }
    val totalSize = remember(downloads) { downloads.sumOf { it.size } }

    val selected = remember { mutableStateMapOf<String, DownloadItem>() }
    var confirmDelete by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            message = null
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("下载", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "共 ${downloads.size} 个文件 · ${approximateSize(totalSize)} · 仅手动删除",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "刷新")
                    }
                    IconButton(onClick = {
                        if (selected.size == downloads.size && downloads.isNotEmpty()) selected.clear()
                        else downloads.forEach { selected[it.path] = it }
                    }) {
                        Icon(Icons.Rounded.SelectAll, contentDescription = "全选")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            if (selected.isNotEmpty()) {
                SelectionBottomBar(
                    count = selected.size,
                    bytes = selected.values.sumOf { it.size },
                    allSelected = selected.size == downloads.size,
                    onKeep = {
                        val items = selected.values.toList()
                        vm.keepDownloads(items) {
                            message = "已保留 ${items.size} 项，不会再出现在列表里"
                        }
                        selected.clear()
                    },
                    onSelectAll = {
                        if (selected.size == downloads.size) selected.clear()
                        else downloads.forEach { selected[it.path] = it }
                    },
                    onDelete = { confirmDelete = true }
                )
            }
        }
    ) { padding ->
        if (downloads.isEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                EmptyState(
                    icon = Icons.Rounded.FolderOpen,
                    title = "下载目录很干净",
                    subtitle = "Download 目录顶层的文件会显示在这里，只有你会手动删除它们"
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 16.dp, vertical = 8.dp
                ),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(downloads, key = { it.path }) { item ->
                    DownloadRow(
                        item = item,
                        isSelected = selected.containsKey(item.path),
                        selectionMode = selected.isNotEmpty(),
                        onClick = {
                            if (selected.isNotEmpty()) {
                                if (selected.containsKey(item.path)) selected.remove(item.path)
                                else selected[item.path] = item
                            }
                        },
                        onLongClick = { selected[item.path] = item }
                    )
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除 ${selected.size} 个文件？") },
            text = { Text("将从 Download 目录永久删除这些文件，无法恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        val items = selected.values.toList()
                        vm.deleteDownloads(items) { c, b ->
                            message = "已删除 $c 个文件，释放 ${approximateSize(b)}"
                        }
                        selected.clear()
                    },
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            }
        )
    }
}

private val imageExtensions =
    setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "bmp", "avif")

private fun isImage(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase() in imageExtensions

private fun iconFor(name: String): ImageVector {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "apk", "xapk" -> Icons.Rounded.Android
        "zip", "rar", "7z", "tar", "gz", "bz2" -> Icons.Rounded.FolderZip
        "pdf" -> Icons.Rounded.PictureAsPdf
        "mp4", "mkv", "avi", "mov", "webm", "3gp", "ts" -> Icons.Rounded.Movie
        "mp3", "wav", "flac", "aac", "ogg", "m4a", "opus" -> Icons.Rounded.MusicNote
        "doc", "docx", "txt", "md", "xls", "xlsx", "ppt", "pptx", "csv" ->
            Icons.Rounded.Description
        "jpg", "jpeg", "png", "gif", "webp", "heic", "bmp", "avif" -> Icons.Rounded.Image
        else -> Icons.Rounded.InsertDriveFile
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DownloadRow(
    item: DownloadItem,
    isSelected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (isSelected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isImage(item.name)) {
            AsyncImage(
                model = File(item.path),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
            )
        } else {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    iconFor(item.name),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "${approximateSize(item.size)} · ${formatDateTime(item.modifiedMs)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        if (selectionMode || isSelected) {
            Box(
                Modifier
                    .size(24.dp)
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary
                        else Color.Transparent,
                        CircleShape
                    )
                    .border(
                        1.5.dp,
                        if (isSelected) Color.Transparent else MaterialTheme.colorScheme.outline,
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}
