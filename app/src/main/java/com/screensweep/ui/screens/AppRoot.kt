package com.screensweep.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.screensweep.MainViewModel
import com.screensweep.R
import com.screensweep.ui.components.OnResumeEffect
import com.screensweep.util.Permissions

@Composable
fun AppRoot(vm: MainViewModel = viewModel()) {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(0) }
    val selectedTab = if (tab == 0) 0 else 1
    var storageGranted by remember {
        mutableStateOf(Permissions.hasStorageAccess(context))
    }
    OnResumeEffect {
        storageGranted = Permissions.hasStorageAccess(context)
    }
    LaunchedEffect(storageGranted) {
        if (storageGranted) vm.refresh()
    }

    if (!storageGranted) {
        PermissionScreen()
    } else {
        AskNotificationPermissionOnce()
        Scaffold(
            // Each tab owns its TopAppBar and its status-bar inset. Do not apply
            // the same top inset again from this tab shell.
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { tab = 0 },
                        icon = { Icon(Icons.Rounded.PhotoLibrary, contentDescription = null) },
                        label = { Text("文件") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { tab = 1 },
                        icon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
                        label = { Text("设置") }
                    )
                }
            }
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                when (tab) {
                    0 -> ScreenshotsScreen(vm)
                    else -> SettingsScreen(vm)
                }
            }
        }
    }
}

@Composable
private fun PermissionScreen() {
    val context = LocalContext.current
    val requestLegacy = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.size(132.dp)
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "欢迎使用净图",
            style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
            color = androidx.compose.material3.MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "自动清理你选择的文件夹，包括 Download。\n\n" +
                "· 只处理你选择的文件夹，绝不碰其他文件\n" +
                "· 已保留的内容永远不会被清理\n" +
                "· 全部在本地完成，不联网、不上传",
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                if (Build.VERSION.SDK_INT >= 30) {
                    context.startActivity(Permissions.allFilesAccessIntent(context))
                } else {
                    requestLegacy.launch(
                        arrayOf(
                            Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE
                        )
                    )
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (Build.VERSION.SDK_INT >= 30) "授予「所有文件访问」权限" else "授予存储权限")
        }
        Spacer(Modifier.height(8.dp))
        if (Build.VERSION.SDK_INT >= 30) {
            Text(
                "点击后会跳转到系统设置页，找到本应用并打开「允许管理所有文件」后返回即可。",
                style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AskNotificationPermissionOnce() {
    if (Build.VERSION.SDK_INT >= 33) {
        val context = LocalContext.current
        var asked by rememberSaveable { mutableStateOf(false) }
        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { }
        if (!asked) {
            LaunchedEffect(Unit) {
                asked = true
                if (!Permissions.hasNotificationAccess(context)) {
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }
}
