package com.screensweep.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.screensweep.MainViewModel
import com.screensweep.BuildConfig
import com.screensweep.R
import com.screensweep.data.ImageFolderCheck
import com.screensweep.data.ImageSources
import com.screensweep.data.labelForTreeUri
import com.screensweep.ui.components.OnResumeEffect
import com.screensweep.util.Permissions
import com.screensweep.util.approximateSize
import com.screensweep.util.formatDateTime
import kotlin.math.roundToInt
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val keptShots by vm.keptShots.collectAsStateWithLifecycle()
    val s = settings

    var storageOk by remember { mutableStateOf(Permissions.hasStorageAccess(context)) }
    var notifOk by remember { mutableStateOf(Permissions.hasNotificationAccess(context)) }
    OnResumeEffect {
        storageOk = Permissions.hasStorageAccess(context)
        notifOk = Permissions.hasNotificationAccess(context)
    }

    var message by remember { mutableStateOf<String?>(null) }
    var folderCheck by remember { mutableStateOf<ImageFolderCheck?>(null) }
    var showKept by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val keptCount = (s?.keptPaths?.size ?: 0) + keptShots.size

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            message = null
        }
    }

    val requestStorage = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }
    val requestNotif = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { notifOk = Permissions.hasNotificationAccess(context) }
    val addFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let(vm::addCustomFolder) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("设置", style = MaterialTheme.typography.titleLarge) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        if (s == null) return@Scaffold
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ---------- 权限 ----------
            item {
                SettingsCard(title = "权限") {
                    PermissionRow(
                        label = "文件访问",
                        ok = storageOk,
                        onRequest = {
                            if (Build.VERSION.SDK_INT >= 30) {
                                context.startActivity(Permissions.allFilesAccessIntent(context))
                            } else {
                                requestStorage.launch(
                                    arrayOf(
                                        Manifest.permission.READ_EXTERNAL_STORAGE,
                                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                                    )
                                )
                            }
                        }
                    )
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    PermissionRow(
                        label = "通知",
                        ok = notifOk,
                        onRequest = {
                            if (Build.VERSION.SDK_INT >= 33) {
                                requestNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                context.startActivity(Permissions.appNotificationSettingsIntent(context))
                            }
                        }
                    )
                }
            }

            // ---------- 自动清理目录 ----------
            item {
                SettingsCard(title = "自动清理目录") {
                    Text(
                        "选中的目录会按照上面的保留天数自动删除；第一 tab 也可以快速切换。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    ImageSources.builtIns.forEach { source ->
                        AutoCleanFolderRow(
                            label = source.label,
                            selected = source.key in s.autoCleanFolders,
                            onToggle = {
                                vm.setAutoCleanFolder(
                                    source.key,
                                    source.key !in s.autoCleanFolders
                                )
                            }
                        )
                    }
                    s.customFolderUris.forEach { uri ->
                        AutoCleanFolderRow(
                            label = labelForTreeUri(context, uri),
                            selected = ImageSources.customKey(uri) in s.autoCleanFolders,
                            onToggle = {
                                val key = ImageSources.customKey(uri)
                                vm.setAutoCleanFolder(key, key !in s.autoCleanFolders)
                            },
                            onRemove = { vm.removeCustomFolder(uri) }
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(onClick = { addFolder.launch(null) }) {
                        Icon(Icons.Rounded.Add, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("添加文件夹")
                    }
                }
            }

            // ---------- 自动清理 ----------
            item {
                SettingsCard(title = "自动清理文件") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.CleaningServices,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "开启自动清理",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                "每天检查文件页选中的目录，删除超过保留天数的文件，完成后发通知",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = s.autoCleanEnabled,
                            onCheckedChange = { vm.setAutoClean(it) }
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    var days by remember(s.retainDays) {
                        mutableFloatStateOf(s.retainDays.toFloat())
                    }
                    Text(
                        "保留最近 ${days.roundToInt()} 天",
                        style = MaterialTheme.typography.titleSmall,
                        color = if (s.autoCleanEnabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = days,
                        onValueChange = { days = it },
                        onValueChangeFinished = { vm.setRetainDays(days.roundToInt()) },
                        valueRange = 3f..7f,
                        enabled = s.autoCleanEnabled
                    )
                    Text(
                        "更早的文件会被自动删除；保留的内容永远不会被动。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        s.lastAutoCleanAt?.let { "上次自动清理：${formatDateTime(it)}" }
                            ?: "上次自动清理：尚未执行",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = { showTimePicker = true },
                        enabled = s.autoCleanEnabled
                    ) {
                        Icon(Icons.Rounded.AccessTime, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("每天 ${formatTime(s.autoCleanHour, s.autoCleanMinute)} 运行")
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            vm.cleanNow { c, b, check ->
                                message = if (c > 0)
                                    "已清理 $c 项，释放 ${approximateSize(b)}"
                                else "没有需要清理的文件"
                                if (check.folders.isNotEmpty()) folderCheck = check
                            }
                        },
                        enabled = storageOk
                    ) {
                        Text("立即清理一次")
                    }
                }
            }

            // ---------- 保留项 ----------
            item {
                SettingsCard(title = "保留项") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.Bookmark,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "已保留 $keptCount 项",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                        "图片会移到 Pictures/CleanPic/Kept，其他文件加入保留列表",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(
                            onClick = { showKept = true },
                            enabled = keptCount > 0
                        ) {
                            Text("管理")
                        }
                    }
                }
            }

            // ---------- 关于 ----------
            item {
                SettingsCard(title = "关于") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(R.drawable.ic_launcher_foreground),
                            contentDescription = null,
                            modifier = Modifier.size(52.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("净图 · 本地图片整理", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "v${BuildConfig.VERSION_NAME} · 本地文件整理",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
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

    if (showKept && s != null) {
        AlertDialog(
            onDismissRequest = { showKept = false },
            confirmButton = {
                TextButton(onClick = { showKept = false }) { Text("关闭") }
            },
            title = { Text("保留项（$keptCount）") },
            text = {
                LazyColumn(Modifier.height(320.dp)) {
                    items(keptShots, key = { it.id }) { shot ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 6.dp)
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    shot.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    "保留目录",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton24 {
                                vm.restoreKeptShot(shot) { restored ->
                                    message = if (restored) {
                                        "已恢复到截图目录"
                                    } else {
                                        "恢复失败，请检查文件权限"
                                    }
                                }
                            }
                        }
                    }
                    items(s.keptPaths.sorted()) { path ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 6.dp)
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    path.substringAfterLast('/'),
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    path.substringBeforeLast('/'),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton24 { vm.unkeepPath(path) }
                        }
                    }
                }
            }
        )
    }

    if (showTimePicker) {
        val timePickerState = rememberTimePickerState(
            initialHour = s?.autoCleanHour ?: 3,
            initialMinute = s?.autoCleanMinute ?: 0,
            is24Hour = true
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("设置每日运行时间") },
            text = { TimePicker(state = timePickerState) },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.setAutoCleanTime(timePickerState.hour, timePickerState.minute)
                        showTimePicker = false
                    }
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun AutoCleanFolderRow(
    label: String,
    selected: Boolean,
    onToggle: () -> Unit,
    onRemove: (() -> Unit)? = null
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (onRemove != null) {
            TextButton(onClick = onRemove) { Text("移除") }
        }
        Switch(checked = selected, onCheckedChange = { onToggle() })
    }
}

@Composable
private fun IconButton24(onClick: () -> Unit) {
    androidx.compose.material3.IconButton(onClick = onClick) {
        Icon(
            Icons.Rounded.Close,
            contentDescription = "取消保留",
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(10.dp))
            content()
        }
    }

}

private fun formatTime(hour: Int, minute: Int): String =
    String.format(Locale.ROOT, "%02d:%02d", hour, minute)

@Composable
private fun PermissionRow(label: String, ok: Boolean, onRequest: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
            contentDescription = null,
            tint = if (ok) androidx.compose.ui.graphics.Color(0xFF2E7D32)
            else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (!ok) {
            TextButton(onClick = onRequest) { Text("去开启") }
        } else {
            Text(
                "已授权",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
