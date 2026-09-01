# Android 加固工具（AndroidHardeningTool）项目交接文档

> 项目代号：AndroidHardeningTool
> 版本：9.4.27（versionCode 29）
> 交接日期：2026-09-01
> 交接人：AI 交接专家（MonkeyCode）

---

## 目录

1. [项目概述](#1-项目概述)
2. [技术栈与运行环境](#2-技术栈与运行环境)
3. [快速开始：构建](#3-快速开始构建)
4. [快速开始：运行与验收](#4-快速开始运行与验收)
5. [功能清单](#5-功能清单)
6. [源码目录结构](#6-源码目录结构)
7. [核心模块说明](#7-核心模块说明)
8. [关键配置项](#8-关键配置项)
9. [第三方依赖与镜像](#9-第三方依赖与镜像)
10. [产物清单](#10-产物清单)
11. [已知问题与注意事项](#11-已知问题与注意事项)
12. [二次开发指南](#12-二次开发指南)
13. [Git 与发布](#13-git-与发布)
14. [联系方式与支持](#14-联系方式与支持)

---

## 1. 项目概述

Android APK 加固工具（App 本身），用户在 App 内选择设备上已安装的 APK，勾选 15 项加固功能与 20 项保护功能后点击加固，工具对目标 APK 进行 DEX 加密、SO 加固、资源加密、签名重签等处理，输出加固后的 APK 到设备 `/storage/emulated/0/ADFXCBNM/` 目录。

## 2. 技术栈与运行环境

| 项目 | 版本/值 |
|------|---------|
| 语言 | Kotlin（引擎核心，包 `com.adfxcbnm.frostshell.*`） |
| 构建工具 | Gradle 8.4（wrapper） |
| AGP | 8.2.0 |
| Kotlin/Compose 编译器 | KGP 1.9.20 / kotlinCompilerExtensionVersion 1.5.5 |
| compileSdk / targetSdk | 34 / 34 |
| minSdk | 26 |
| NDK / CMake | 25.1.8937393 / 3.22.1（C++17，`app/src/main/cpp/`） |
| Java | 17（source/target 均为 17） |
| 运行时 | Compose BOM 2023.10.01，Material3，activity-compose 1.8.1 |
| 签名 | `com.android.tools.build:apksig:8.5.0`（加固输出 APK 的 v1/v2/v3 重签名） |

## 3. 快速开始：构建

前置：JDK 17、Android SDK（含 build-tools 34.0.0、platform 34、NDK 25.1.8937393、CMake 3.22.1）。

```bash
# 设置 SDK 路径（或写入 local.properties: sdk.dir=<你的 SDK 路径>）
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=<你的 Android SDK 路径>

# 清理 CMake 缓存（若曾移动过项目目录，必须执行，否则报 CXX1409）
rm -rf app/.cxx

# 构建 debug APK
./gradlew :app:assembleDebug --no-daemon --console=plain
```

产物：`app/build/outputs/apk/debug/app-debug.apk`（约 48MB）。

## 4. 快速开始：运行与验收

1. 用 `adb install -r app/build/outputs/apk/debug/app-debug.apk` 安装到 Android 8.0+（minSdk 26）设备。
2. 启动「Android加固工具」App，授予存储权限。
3. 点击「选择 APK」挑选一个已安装应用。
4. 切换「FrostShell 引擎」开关（默认关闭）：开启时使用 Kotlin 移植的 FrostShell 加固引擎；关闭时使用内置完整流程。
5. 点击「开始加固」，等待日志输出，加固完成提示成功。
6. 验收点：加固后的 APK 出现在 `/storage/emulated/0/ADFXCBNM/` 目录，大小与源 APK 不同，且可正常安装运行。

验收命令（可选，用于核对版本）：

```bash
aapt dump badging app/build/outputs/apk/debug/app-debug.apk | head -5
# 期望输出包含: package name='com.adfxcbnm.hardeningtool' versionName='9.4.27'
```

## 5. 功能清单

### 5.1 加固功能（15 项）

DEX 加密加固（AES-256-CBC）、DEX 虚拟化（自定义字节码）、SO 库加固（AES）、SO 符号剥离（ELF 符号表清零）、SO 加壳保护（压缩+AES）、资源文件加密（AES-256-CBC）、签名校验（SHA-256）、防调试检测（多重）、防 Hook 检测（Xposed/Frida）、防注入保护（反编译陷阱）、防内存 Dump、代码混淆（XOR 字符串池）、字符串加密（ASCII 移位）、防反编译（无效操作码）、防代理检测。

### 5.2 保护功能（20 项）

完整性校验（SHA-256）、运行时保护（JNI）、内存保护（完整性标记）、网络安全（SSL 固定）、ROOT 检测（su 扫描）、模拟器检测（多维度）、Xposed 检测（类加载）、Frida 检测（端口扫描）、Magisk 检测（路径）、调试器检测（JDWP）、代码注入检测（库扫描）、速度检测（时序分析）、多开检测（进程分析）、SSL 证书校验（证书固定）、数据防泄漏（保护标记）、日志保护（输出控制）、应用签名校验（哈希验证）、WebView 安全（配置保护）、文件访问控制（权限检测）、应用组件保护（Manifest）。

### 5.3 FrostShell 引擎（新增，v9.4.27 集成）

- Kotlin 移植引擎，包 `com.adfxcbnm.frostshell.*`，原 Java 引擎字节码编译进 `app/libs/ironshell-deps.jar`（11.8MB，编译必需依赖）。
- 引擎封装 `FrostShellEngine`（`prepare` / `protectApk` / `findOutputApk`），运行时从 assets 释放 `frostshell/` 目录到 App 私有目录。
- 引擎签名使用编程式 `com.android.apksig.ApkSigner` API（v1/v2/v3），避免调用 `ApkSignerTool.main` 导致 `System.exit` 杀进程。

## 6. 源码目录结构

```
AndroidHardeningTool/
├── build.gradle.kts / settings.gradle.kts / gradle.properties / gradlew / gradle/
├── .github/workflows/build-apk.yml   # CI：push 到 main 自动构建 debug APK
├── README.md
├── HANDOVER.md                        # 本文档
└── app/
    ├── build.gradle.kts               # 版本号 9.4.27 / versionCode 29
    ├── libs/ironshell-deps.jar        # FrostShell 引擎字节码依赖（11.8MB，必需）
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml        # 存储/安装/网络权限
        ├── cpp/CMakeLists.txt + protection.cpp   # 原生保护库（security_check）
        ├── assets/
        │   ├── frostshell/            # 引擎运行时资源（libs/*.so, dex, build-key）
        │   ├── lib/*/libsecurity_check.so  # 原生保护 .so（4 ABI）
        │   ├── ironshell.jks          # 引擎默认签名 keystore（运行时必需）
        │   ├── classes.dex / shell_loader.dex / security_check.dex
        ├── java/com/adfxcbnm/
        │   ├── hardeningtool/         # App 壳层（MainActivity/FrostShellEngine/HardeningApplication/ProtectionNative/AndroidManifestModifier）
        │   ├── frostshell/            # 引擎 Kotlin 源码（builder/config/dex/elf/model/res/task/util）
        │   └── protect/               # S.java / SecurityCheckProvider.java（native 绑定）
        └── res/                       # Compose 主题、图标、strings
```

## 7. 核心模块说明

| 模块 | 路径 | 职责 |
|------|------|------|
| 主界面 | `hardeningtool/MainActivity.kt` | UI、APK 选择、加固流程编排、日志展示、`useFrostEngine` 引擎开关 |
| 引擎封装 | `hardeningtool/FrostShellEngine.kt` | FrostShell 引擎的 prepare/protectApk/findOutputApk 封装 |
| 应用初始化 | `hardeningtool/HardeningApplication.kt` | Application 入口 |
| 原生保护 | `hardeningtool/ProtectionNative.kt` + `protect/S.java` + `protect/SecurityCheckProvider.java` + `cpp/protection.cpp` | 运行期完整性/安全检测 native 桥接 |
| Manifest 编辑 | `hardeningtool/AndroidManifestModifier.kt` | 二进制 AXML 编辑 |
| APK 处理 | `frostshell/builder/FrostApk.kt` | 重打包 + 编程式 ApkSigner 签名（v1/v2/v3） |
| 构建配置 | `app/build.gradle.kts` | SDK/NDK/版本/依赖配置 |

## 8. 关键配置项

| 配置 | 位置 | 说明 |
|------|------|------|
| versionName / versionCode | `app/build.gradle.kts` | `9.4.27` / `29` |
| applicationId / namespace | `app/build.gradle.kts` | `com.adfxcbnm.hardeningtool` |
| SDK 路径 | `local.properties`（不入库） | `sdk.dir=<你的 SDK 路径>` |
| 引擎开关默认值 | `MainActivity.kt` prefs `use_frost_engine` | 默认 `false`（内置流程） |
| 加固输出目录 | `MainActivity.kt` | `/storage/emulated/0/ADFXCBNM/` |
| 引擎签名 keystore | `assets/ironshell.jks` | 运行时由引擎读取（默认口令，非机密） |
| 数据下载密钥 | （无） | 项目无外部 API，无需真实密钥；如未来接入，一律用占位符 `<YOUR_API_KEY>` |

## 9. 第三方依赖与镜像

依赖源（`settings.gradle.kts`）：

- `https://maven.aliyun.com/repository/gradle-plugin`
- `https://maven.aliyun.com/repository/google`
- `https://maven.aliyun.com/repository/public`
- `https://maven.aliyun.com/repository/central`

主要依赖：

- `androidx.core:core-ktx:1.12.0`、`androidx.lifecycle:lifecycle-runtime-ktx:2.6.2`
- `androidx.activity:activity-compose:1.8.1`
- Compose BOM `2023.10.01`（ui / ui-graphics / material3 / material-icons-extended）
- `com.android.tools.build:apksig:8.5.0`（重签名）
- 本地 jar：`libs/ironshell-deps.jar`（FrostShell 引擎字节码，编译必需）

备用镜像（当阿里云镜像不可用时）：

- Google Maven：`https://maven.google.com`
- Gradle 插件：`https://plugins.gradle.org/m2`
- Maven Central：`https://repo1.maven.org/maven2`

## 10. 产物清单

| 产物 | 路径 | 说明 |
|------|------|------|
| Debug APK | `app/build/outputs/apk/debug/app-debug.apk` | 安装包（约 48MB） |
| 源码 zip | `AndroidHardeningTool源码.zip` | 完整可编译源码 + CI 配置 |
| 本交接文档 | `HANDOVER.md` | 本文档 |

GitHub 远程仓库：`https://github.com/yz8023/safe-apk.git`（本交接代码将推送至 main 分支并发布 Release）。

## 11. 已知问题与注意事项

1. **移动项目目录后必须清理 CMake 缓存**：`.cxx` 缓存记录旧绝对路径，会报 `CXX1409 ... custom target ... could not be found`。处理：`rm -rf app/.cxx` 后重新构建。
2. **deps jar 内禁止混入 assets**：若把 `ironshell.jks` 等打进 `ironshell-deps.jar`，打包时 zipflinger 报 `Zip file 'app-debug.apk' already contains entry 'assets/ironshell.jks', cannot overwrite`。deps jar 只含字节码，资产放 `assets/`。
3. **不要用 `ApkSignerTool.main` 签名**：会 `System.exit` 杀掉整个进程。用编程式 `ApkSigner.Builder`。
4. **不要升级依赖中的 apksig**：`com.android.tools.build:apksig:8.5.0` 与 AGP 自带 apksig 需保持一致，否则 `checkDebugDuplicateClasses` 报重复类。
5. **debug 构建带签名（自动 debug key）**：`assembleDebug` 已可直接安装；release 构建需自配签名（当前 `isMinifyEnabled=false`）。
6. **引擎输出验证**：FrostShell 引擎输出的 APK 命名 `<源名>_engine<时间戳>.apk`，输出在 `getExternalFilesDir/ADFXCBNM/`（App 私有目录），内置流程输出在公共 `/storage/emulated/0/ADFXCBNM/`。
7. **minSdk 26 限制**：Android 8.0 以下设备无法安装。

## 12. 二次开发指南

- **加加固项**：在 `MainActivity.kt` 的 `buildFeatureConfig` / 功能列表处增加条目，并在 `FrostApk` 或引擎对应阶段实现处理逻辑。
- **改输出目录**：搜 `ADFXCBNM` 常量，全局替换。
- **切回旧版 Java 引擎**：保留 `ironshell-deps.jar` 中 `com.ironshell.*` 字节码即可（当前 deps jar 已排除 `com/ironshell` 包，引擎逻辑在 `com.adfxcbnm.frostshell` Kotlin 侧重新实现）。
- **新增原生保护项**：在 `cpp/protection.cpp` 增加 JNI 方法，`ProtectionNative.kt` 声明 native 方法，`libsecurity_check.so` 需重新用 NDK 编译并放入 `assets/lib/<abi>/`。
- **CI 改发布**：`build-apk.yml` 当前仅构建+上传 artifact，可在 push tag 时追加 release 步骤。

## 13. Git 与发布

- 仓库：`https://github.com/yz8023/safe-apk.git`，分支 `main`。
- 版本标签：`v9.4.27`。
- CI：`.github/workflows/build-apk.yml` 在 push 到 main 或手动触发时构建 debug APK 并上传 artifact。
- 提交约定：功能改动用 `feat:`，修复用 `fix:`，文档用 `docs:`。

## 14. 联系方式与支持

- README 预留联系方式：Telegram `@ADFXCBNM`。
- 交接联系人：MonkeyCode AI 交接专家（本会话）。
- 遇到构建问题优先查本文档第 11 节「已知问题」。
