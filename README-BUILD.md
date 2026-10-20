# 编译说明（Android / GitHub Actions）

这个包用于**验证 APK 编译链路**，也可以直接作为后续开发的仓库骨架。

> ⚠️ 当前 `android/` 里的 UI 是**旧版（表格 + 按钮）**，判定逻辑也还是旧版。
> 目的：先确认「能编译出 APK、能装到手机」，验证通过后再把判定核心与 QuickPic 风格 UI 写进去。

---

## 方案 A：GitHub Actions（推荐，不需要本地装 Android SDK）

### 你需要准备

| 需要 | 说明 |
|---|---|
| GitHub 账号 | 免费即可 |
| 一个新仓库 | public / private 都行（private 的 Actions 每月 2000 分钟免费，这个工程一次构建约 3–5 分钟） |
| 能传文件 | 网页拖拽 / GitHub Desktop / git 命令行，任选 |

**不需要**：JDK、Android SDK、Gradle、NDK、签名文件（debug 签名即可自用）。

### 步骤

1. 新建仓库（不要勾选自动生成 README）。
2. 把这个包里的**两个东西**放到仓库根目录：
   ```
   仓库根/
   ├── .github/workflows/build-apk.yml     ← 必须在仓库根
   └── android/                            ← Gradle 工程
   ```
3. 上传（任一方式）：
   - 网页：仓库页 → Add file → Upload files → 把 `.github` 和 `android` 一起拖进去 → Commit
   - 命令行：
     ```bash
     git init && git add . && git commit -m "init" 
     git branch -M main
     git remote add origin https://github.com/<你>/<仓库>.git
     git push -u origin main
     ```
4. 仓库页 → **Actions** → 左侧 `build-apk` → **Run workflow** → 等 3–5 分钟。
5. 在该次运行页面底部 **Artifacts** 下载 `manga-dedupe-debug-apk`，解压得 `app-debug.apk`。
6. 传到手机安装（debug 签名，需要在系统里允许"未知来源"）。

### 第一次运行要看什么

| 看什么 | 期望 |
|---|---|
| 安装 SDK 组件 步骤 | 成功（会自动装 platform 34 / build-tools 34） |
| 单元测试 步骤 | 成功（当前还没有测试用例，直接通过；以后会显示 8 个 golden 用例） |
| 构建 Debug APK 步骤 | 成功，底部 Artifacts 出现 manga-dedupe-debug-apk |
| 装到手机 | 打开后显示「漫画重复筛选 / 编译链路验证版」 |

**如果失败**：把失败步骤的名字 + 报错最后 20 行贴给我即可（不用整个日志）。

### 可能的坑

| 现象 | 原因 / 解决 |
|---|---|
| Actions 里 `gradle: command not found` | workflow 用 `gradle/actions/setup-gradle` 安装 Gradle 8.7，检查该步骤是否成功 |
| `AGP requires Gradle 8.7+` | 版本已配对（AGP 8.5.2 ↔ Gradle 8.7），别自行升级其中一个 |
| `compileSdk 34 not found` | `android-actions/setup-android@v3` 会自动装；若失败，在 workflow 里加一行 `sdkmanager "platforms;android-34" "build-tools;34.0.0"` |
| 构建成功但装不上 | 手机上已有同包名不同签名的旧版，先卸载 |

---

## 方案 B：本地 Android Studio

### 你需要准备

| 需要 | 说明 |
|---|---|
| Android Studio（最新稳定版） | 自带 JDK 17 |
| SDK 组件 | `Android SDK Platform 34`、`Build-Tools 34.0.0`（Studio 首次运行会引导安装） |
| 网络 | 首次 Sync 要下载 AGP 8.5.2 与 androidx 依赖 |

### 步骤

1. Android Studio → **Open** → 选 `android/` 目录（不是仓库根）。
2. 等 Gradle Sync（首次 3–10 分钟，取决于网络）。
3. 连手机（开 USB 调试）→ 点 **Run ▶**，或 **Build → Build APK(s)**。
4. 产物：`android/app/build/outputs/apk/debug/app-debug.apk`。

---

## 方案 C：其它 CI

如果你有 GitLab CI / Gitee Go / Jenkins，把 `.github/workflows/build-apk.yml` 的三步翻译过去即可：

```
1. 装 JDK 17
2. 装 Android SDK（platform 34 + build-tools 34）
3. cd android && gradle assembleDebug
4. 收集 android/app/build/outputs/apk/debug/*.apk
```

---

## 我这边需要你确认/提供的

| 项 | 默认 | 是否需要改 |
|---|---|---|
| 仓库地址 | 无 | 建好后把 URL 给我，我可以核对 workflow 路径 |
| 应用包名 applicationId | `com.example.mangadedupe` | 想改成自己的（如 `com.你的名字.mangadedupe`）告诉我 |
| 最低 Android 版本 minSdk | 24（Android 7.0） | 你的手机更老就改 |
| 是否需要 release 签名 | 否（debug 够自用） | 要长期用/给他人装，需要生成 keystore（我可以给步骤） |
| 是否需要内置初始化数据 | 否 | 需要的话另说 |

---

## 我无法在本地做什么

我这边的环境**没有 JDK / Android SDK / 网络**（只能抓网页），所以：

- ❌ 不能本地 `gradle assembleDebug`
- ❌ 不能生成 APK、不能运行 adb 安装
- ✅ 能写全部源码、CI 配置、纯 Java 单元测试（用 Windows 自带 csc 验证判定逻辑）
- ✅ 能审读你的构建日志并定位错误（把 Actions 失败日志贴给我）
