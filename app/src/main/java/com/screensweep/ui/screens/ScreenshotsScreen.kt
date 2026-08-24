package com.screensweep.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.screensweep.MainViewModel
import com.screensweep.data.ImageSources
import com.screensweep.data.ImageFolderCheck
import com.screensweep.data.ShotItem
import com.screensweep.data.labelForTreeUri
import com.screensweep.ui.components.EmptyState
import com.screensweep.ui.components.SelectionBottomBar
import com.screensweep.util.dayLabel
import com.screensweep.util.formatDateTime
import com.screensweep.util.formatSize

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenshotsScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val allShots by vm.shots.collectAsStateWithLifecycle()

    val customFolderUris = settings?.customFolderUris ?: emptySet()
    val sources = ImageSources.builtIns + customFolderUris.map { uri ->
        com.screensweep.data.ImageSource(
            key = ImageSources.customKey(uri),
            label = labelForTreeUri(context, uri),
            treeUri = uri
        )
    }
    val autoCleanFolders = settings?.autoCleanFolders
        ?: ImageSources.builtIns.map { it.key }.toSet()
    val keptPaths = settings?.keptPaths ?: emptySet()
    val keptIds = settings?.keptIds ?: emptySet()
    val shots = remember(allShots, keptPaths, keptIds) {
        allShots.filter { it.path !in keptPaths && it.id.toString() !in keptIds }
    }
    val groups = remember(shots) {
        shots.groupBy { dayLabel(it.addedMs) }.map { it.key to it.value }
    }
    val totalSize = remember(shots) { shots.sumOf { it.size } }

    val selected = remember { mutableStateMapOf<Long, ShotItem>() }
    var preview by remember { mutableStateOf<ShotItem?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var folderCheck by remember { mutableStateOf<ImageFolderCheck?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            message = null
        }
    }

    fun selectOlderThan(days: Int) {
        val cutoff = System.currentTimeMillis() - days * 24L * 60 * 60 * 1000
        val older = shots.filter { it.addedMs < cutoff }
        if (older.isEmpty()) {
            message = "没有 $days 天前的图片"
        } else {
            older.forEach { selected[it.id] = it }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.surface)) {
                TopAppBar(
                    title = {
                        Column {
                            Text("图片", style = MaterialTheme.typography.titleLarge)
                            Text(
                                "共 ${shots.size} 张 · ${formatSize(context, totalSize)} · 长按多选",
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
                            if (selected.size == shots.size && shots.isNotEmpty()) selected.clear()
                            else shots.forEach { selected[it.id] = it }
                        }) {
                            Icon(Icons.Rounded.SelectAll, contentDescription = "全选")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "自动清理目录",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    sources.forEach { source ->
                        FilterChip(
                            selected = source.key in autoCleanFolders,
                            onClick = {
                                vm.setAutoCleanFolder(
                                    source.key,
                                    source.key !in autoCleanFolders
                                )
                            },
                            label = { Text(source.label) }
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AssistChip(onClick = { selectOlderThan(3) }, label = { Text("选 3 天前") })
                    AssistChip(onClick = { selectOlderThan(7) }, label = { Text("选 7 天前") })
                    AssistChip(onClick = { selectOlderThan(30) }, label = { Text("选 30 天前") })
                }
                Spacer(Modifier.height(8.dp))
            }
        },
        bottomBar = {
            if (selected.isNotEmpty()) {
                SelectionBottomBar(
                    count = selected.size,
                    bytes = selected.values.sumOf { it.size },
                    allSelected = selected.size == shots.size,
                    onKeep = {
                        val items = selected.values.toList()
                        vm.keepShots(items) { kept, failed ->
                            message = if (failed == 0) {
                                "已保留 $kept 项，已移到保留图片目录"
                            } else {
                                "已移动 $kept 项，$failed 项移动失败"
                            }
                        }
                        selected.clear()
                    },
                    onSelectAll = {
                        if (selected.size == shots.size) selected.clear()
                        else shots.forEach { selected[it.id] = it }
                    },
                    onDelete = { confirmDelete = true }
                )
            }
        }
    ) { padding ->
        if (shots.isEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                EmptyState(
                    icon = Icons.Rounded.PhotoLibrary,
                    title = "没有需要清理的图片",
                    subtitle = "下拉刷新，或去设置里开启自动清理"
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(110.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                groups.forEach { (label, items) ->
                    item(key = "header_$label", span = { GridItemSpan(maxLineSpan) }) {
                        Row(
                            Modifier.padding(start = 14.dp, top = 12.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                label,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "${items.size} 张",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    items(items, key = { it.id }) { shot ->
                        ShotCell(
                            shot = shot,
                            isSelected = selected.containsKey(shot.id),
                            onClick = {
                                if (selected.isNotEmpty()) {
                                    if (selected.containsKey(shot.id)) selected.remove(shot.id)
                                    else selected[shot.id] = shot
                                } else {
                                    preview = shot
                                }
                            },
                            onLongClick = { selected[shot.id] = shot }
                        )
                    }
                }
            }
        }
    }

    preview?.let { shot ->
        ShotPreviewDialog(
            shot = shot,
            onDismiss = { preview = null },
            onKeep = {
                vm.keepShots(listOf(shot)) { kept, _ ->
                    if (kept > 0) message = "已保留，已移到保留图片目录"
                }
                preview = null
            },
            onDelete = {
                vm.deleteShots(listOf(shot)) { c, b, check ->
                    message = "已删除 $c 张，释放 ${formatSize(context, b)}"
                    if (check.folders.isNotEmpty()) folderCheck = check
                }
                preview = null
            }
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除 ${selected.size} 张图片？") },
            text = { Text("删除后无法恢复。若想保留某些图片，可以先「保留」再清理。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        val items = selected.values.toList()
                        vm.deleteShots(items) { c, b, check ->
                            message = "已删除 $c 张，释放 ${formatSize(context, b)}"
                            if (check.folders.isNotEmpty()) folderCheck = check
                        }
                        selected.clear()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            }
        )
    }

    folderCheck?.let { check ->
        val photosInstalled = remember {
            context.packageManager.getLaunchIntentForPackage("com.google.android.apps.photos") != null
        }
        AlertDialog(
            onDismissRequest = { folderCheck = null },
            title = {
                Text(if (check.firstCheck) "请检查 Google Photos 的备份文件夹" else "发现新的图片文件夹")
            },
            text = {
                Column {
                    Text(
                        if (check.firstCheck) {
                            "这是净图第一次检查到的图片文件夹。请在 Google Photos 的备份设置中确认需要备份哪些文件夹。"
                        } else {
                            "删除后发现了以前没见过的图片文件夹。Google Photos 可能不会自动把它加入备份，请检查。"
                        }
                    )
                    Spacer(Modifier.height(10.dp))
                    check.folders.take(12).forEach { folder ->
                        Text("• ${folder.label}（${folder.imageCount} 张）")
                    }
                    if (check.folders.size > 12) {
                        Spacer(Modifier.height(4.dp))
                        Text("还有 ${check.folders.size - 12} 个文件夹未展开")
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("建议在 Google Photos 中开启“自动包含新文件夹”；净图提醒只是补充检查。")
                }
            },
            confirmButton = {
                TextButton(onClick = { folderCheck = null }) { Text("知道了") }
            },
            dismissButton = if (photosInstalled) {
                {
                    TextButton(
                        onClick = {
                            context.packageManager
                                .getLaunchIntentForPackage("com.google.android.apps.photos")
                                ?.let(context::startActivity)
                        }
                    ) { Text("打开 Google Photos") }
                }
            } else null
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShotCell(
    shot: ShotItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val borderColor =
        if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
    Box(
        Modifier
            .aspectRatio(1f)
            .fillMaxWidth()
            .padding(1.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(2.dp, borderColor, RoundedCornerShape(10.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        AsyncImage(
            model = ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                .data(shot.uri)
                .crossfade(true)
                .build(),
            contentDescription = shot.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        if (isSelected) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
            )
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .size(22.dp)
                .background(
                    if (isSelected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.35f),
                    CircleShape
                )
                .border(1.5.dp, Color.White, CircleShape),
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

@Composable
private fun ShotPreviewDialog(
    shot: ShotItem,
    onDismiss: () -> Unit,
    onKeep: () -> Unit,
    onDelete: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, contentDescription = "关闭", tint = Color.White)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        shot.name,
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1
                    )
                    Text(
                        "${formatDateTime(shot.addedMs)} · ${
                            com.screensweep.util.approximateSize(shot.size)
                        }",
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1
                    )
                }
                Spacer(Modifier.width(8.dp))
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(8.dp)
            ) {
                AsyncImage(
                    model = shot.uri,
                    contentDescription = shot.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onKeep,
                    modifier = Modifier.weight(1f)
                ) { Text("保留", color = Color.White) }
                Button(
                    onClick = onDelete,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = Color.White
                    )
                ) {
                    Icon(
                        Icons.Rounded.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("删除")
                }
            }
        }
    }
}
