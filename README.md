# DoBao TTS Android App

一个 Android 应用，用于导入并运行 DoBao-TTS 服务，带终端命令行输出界面。

## 功能特性

- **ZIP 导入**：从文件管理器选择 `DoBao-TTS-Android-*.zip` 安装包，一键解压
- **自动运行**：首次启动自动解压 `node_modules.tar.gz`，无需手动配置
- **Node.js 运行时**：自动获取 Node.js（内置或在线下载），无需 Termux
- **终端界面**：实时彩色显示 TTS 服务的 stdout/stderr 日志
- **前台服务**：支持后台持续运行，系统通知栏显示状态
- **一键打开**：检测服务端口后，可直接打开浏览器访问 Web 界面

## 使用流程

1. 安装 APK
2. 打开应用，点击「导入 ZIP」
3. 选择 `DoBao-TTS-Android-v1.0.3.zip`
4. 等待解压完成
5. 点击「▶ 启动」
6. 等待 Node.js 就绪（首次约需 1-3 分钟解压依赖）
7. 服务启动后点击「打开页面」访问 Web 界面

## 构建

### 使用 GitHub Actions（推荐）

Push 到 `main` 分支后自动触发构建，在 Actions 页面下载 APK artifact。

### 本地构建

```bash
# 需要 Android SDK、JDK 17
./gradlew assembleDebug
# APK 路径: app/build/outputs/apk/debug/app-debug.apk
```

## 系统要求

- Android 8.0 (API 26) 及以上
- 架构：arm64-v8a（主流设备）
- 存储空间：安装包 ~150MB + 解压后 ~400MB

## 技术架构

```
MainActivity         ← 界面（终端输出、按钮控制）
├── ZipUtils         ← ZIP 解压工具
└── NodeService      ← 前台服务
    ├── ensureNodeBinary()    ← 管理 Node.js 运行时
    ├── extractNodeModules()  ← 解压 npm 依赖
    └── startNodeProcess()    ← 启动 server.js 进程
```

## Node.js 获取策略

1. **优先**：检查 `files/node-runtime/bin/node`（已缓存）
2. **其次**：从 `assets/node` 提取（打包方式，体积大）
3. **最后**：在线下载 Node.js v20.18.0 arm64（~50MB，仅首次）
