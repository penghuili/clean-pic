# 截图清理 ScreenSweep

<p align="left">
  <img src="docs/icon.svg" width="96" alt="logo" />
</p>

一个自用 Android 小工具：自动清理手机里过期的**截图**，并帮你手动整理 **Download** 目录。纯本地运行，**没有任何网络权限**。

## 功能

- 🧹 **自动清理截图** —— 可设置保留天数（1–60 天），每天后台检查一次，删除过期截图，完成后发送通知
- 🖼️ **手动批量清理** —— 按天分组的相册式网格，长按进入多选，支持「选 3 / 7 / 30 天前」快捷筛选、全选、大图预览
- 🛡️ **只动截图** —— 只识别 `Pictures/Screenshots`、`DCIM/Screenshots` 及厂商的「截屏 / 屏幕截图」相册，**绝不碰其他照片**；删除前还有路径二次校验
- 📥 **下载管理** —— 单独页面展示 Download 目录顶层文件（按类型显示图标 / 缩略图），**只支持手动删除**，永不自动清理
- 🔖 **保留标记** —— 任意截图 / 文件可标记「保留」，之后不再出现在列表里，也永远不会被自动清理；可在设置里管理保留项
- 🎨 Material 3 界面，支持深色模式与动态取色（Android 12+）

## 安装

到 [Releases](../../releases) 下载最新的 `ScreenSweep-vX.X.X.apk` 安装即可。

- 首次安装需要在系统里允许「从此来源安装」（这是 Android 对所有侧载应用的统一要求，仅授权一次）
- 首次打开需要授予「所有文件访问」权限（Android 11+）或存储权限（旧系统），应用内有引导
- Android 13+ 会请求通知权限，用于自动清理完成后的结果通知

## 权限说明

| 权限 | 用途 |
| --- | --- |
| 所有文件访问 / 存储读写 | 扫描并删除截图、Download 文件 |
| 通知 | 自动清理完成后告知清理了多少张、释放了多少空间 |

无网络权限、无定位、无任何后台上报。

## 构建

```bash
# 需要 JDK 17 与 Android SDK（platform 35 / build-tools 35）
./gradlew assembleRelease
```

发布签名读取项目根目录的 `keystore.properties`（已被 .gitignore 忽略）：

```properties
storeFile=your.keystore
storePassword=***
keyAlias=***
keyPassword=***
```

> ⚠️ 签名文件只存在于本地，请自行备份；丢了就无法覆盖安装升级，只能卸载重装。

## 技术栈

Kotlin · Jetpack Compose (Material 3) · WorkManager（每日定时清理） · DataStore（设置与保留列表） · Coil（缩略图）
