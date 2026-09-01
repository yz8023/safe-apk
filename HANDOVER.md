<<<<<<< HEAD
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
=======
# HANDOVER · FrostShell-CLI（Android 加固工具）

> 交接对象：接手者
> 交接目标：30 分钟内从零构建并成功运行本工具，产出二合一加固 APK。
> 交接日期：2026-08-30
>>>>>>> origin/main

---

## 1. 项目概述

<<<<<<< HEAD
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
- 当前提交哈希：`6133f4a`（2026-09-01）。
- CI：`.github/workflows/build-apk.yml` 在 push 到 main 或手动触发时构建 debug APK 并上传 artifact。
- 提交约定：功能改动用 `feat:`，修复用 `fix:`，文档用 `docs:`。

## 14. 联系方式与支持

- README 预留联系方式：Telegram `@ADFXCBNM`。
- 交接联系人：MonkeyCode AI 交接专家（本会话）。
- 遇到构建问题优先查本文档第 11 节「已知问题」。
=======
- **定位**：Android **函数抽取型加固**命令行工具（FrostShell）+ **ADFXCBNM 运行时保护** 二合一加固器。把 dex 方法字节码整体抽空、native 层运行时回填，并叠加 root/Magisk/Frida/Xposed/模拟器等 26 项运行时检测，一条命令输出加固并重签后的 APK，无需 Android SDK。
- **技术栈**：Python 3.11+（CLI/编排） + JDK 17+（加固引擎，Java） + Kotlin/Java + C++（保护模块源码）。引擎 `ironshell.jar` 自包含 `zipalign`/`apksig`/`dx`。
- **minSdk/targetSdk**：本工程是**桌面 CLI 加固器**，非 Android App，本身无 minSdk。其产出的保护模块要求宿主 App `minSdk >= 26`，目标 `targetSdk` 支持到 **35（Android 15）**；产物将原生库按 **16KB 页对齐**，兼容 Android 15 16KB 页设备。
- **包名 / 应用名**：加固器命令行入口 `protect.py`；应用名 **FrostShell**。
- **commit 哈希**：
  - 初始导入（原始压缩包）：`b0a7aa8`（`Upload from Android`）
  - 二合一整合（本交接代码）：`ec69b22`（`feat: 整合 ADFXCBNM 运行时保护模块...`）
- **远程仓库**：`https://github.com/yz8023/safe-apk`（分支 `main`）
- **备用镜像（无法直连 GitHub 时）**：`https://ghproxy.com/https://github.com/yz8023/safe-apk`、`https://mirror.ghproxy.com/...`（见第 4 节代理列表）

---

## 2. 开发环境

| 项 | 要求 |
|----|------|
| OS | Linux（验证于 Debian 12 bookworm） / macOS / Windows |
| JDK | **17+**（推荐 21）。Debian: `apt-get install -y openjdk-17-jdk-headless` |
| Python | 3.7+（推荐 3.11） |
| Android SDK | **不需要**（zipalign/apksigner/dx 内置进 jar） |
| NDK | 仅二次编译保护模块 `protection.cpp` 时需要（可选） |
| 环境变量 | 无必须项。`JAVA_HOME` 可选 |
| 第三方 Key | 无（签名 keystore 由用户自备或脚本自动生成 debug 签名） |

> 已知坑：脚本 `--auto-setup` 内置的 Temurin JDK 21 直链下载在部分网络会截断，改用系统包管理器安装更稳（见第 13 节）。

---

## 3. 构建运行

### 从 clone 到出包（复制粘贴即可执行）

```bash
git clone https://github.com/yz8023/safe-apk
cd safe-apk/FrostShell-CLI

# 0) 环境检查（缺 JDK 会自动提示）
python3 scripts/protect.py --check

# 1) 构造测试 APK（无需 Android SDK）
python3 scripts/make_test_apk.py build/

# 2) 二合一加固（函数抽取 + 26 项运行时保护 + 自动 debug 签名）
python3 scripts/protect.py build/test_app.apk --plus -o out

# 产物: out/test_app_signed_plus_signed.apk
# 验签:
java -cp engine/ironshell.jar com.android.apksigner.ApkSignerTool verify \
    --min-sdk-version 21 out/*_plus_signed.apk
```

### 用自有签名

```bash
keytool -genkeypair -v -keystore my.jks -alias key0 \
  -keyalg RSA -keysize 2048 -validity 10950 \
  -storepass android -keypass android -dname "CN=Me"

python3 scripts/protect.py build/test_app.apk --plus \
    --keystore my.jks --alias key0 --storepass android
```

### GitHub Actions 构建

- **脚本路径**：`.github/workflows/build-apk.yml`
- **触发方式**：push 到 `main`/`master`、PR 到上述分支、或仓库 Actions 页手动 `Run workflow`
- **内容**：checkout → setup JDK 17 → setup Python 3.11 → `--check` → 构造测试 APK → 二合一加固 → apksig 验签 → 上传 `frostshell-plus-apk` artifact

---

## 4. 依赖与镜像

- **主要依赖**：无第三方 Maven 依赖。引擎 jar 自包含；唯一外部下载是 **Temurin JDK**（`--auto-setup` 场景）。
- **获取优先级**：本地缓存 → 国内镜像 → 官方源 → 手动下载。
- **自动配置镜像**（如需接入 Gradle/远端依赖）：

| 官方源 | 镜像地址 |
|--------|----------|
| Google Maven | `https://maven.aliyun.com/repository/google` |
| JCenter | `https://maven.aliyun.com/repository/jcenter` |
| Maven Central | `https://maven.aliyun.com/repository/central` |
| Gradle 发行版 | `https://mirrors.cloud.tencent.com/gradle/` |

- **GitHub 下载失败**：依次尝试代理：`ghproxy.com`、`mirror.ghproxy.com`、`gh-proxy.com`、`ghfast.top`、`ghps.cc`、`hub.gitmirror.com`、`mirror.ghproxy.com/https://github.com`、`ghproxy.net`、`gh.ddlc.top`、`github.moeyy.xyz`、`gh.api.99988866.xyz`、`gitclone.com/github.com`
- **需手动下载的**：Temurin JDK 21（Linux x64）：`https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.5%2B11/OpenJDK21U-jdk_x64_linux_hotspot_21.0.5_11.tar.gz`，解压后 `--local-jdk <目录>` 指定。

---

## 5. 本地依赖服务

**无**。本工具为纯本地命令行运行，不需要后端 / 数据库 / Redis 等外部服务。CI（GitHub Actions）与本地行为一致。

---

## 6. 开发进度

- **已完成**
  - FrostShell 函数抽取壳引擎（`engine/ironshell.jar`，v2.20.0）
  - ADFXCBNM 运行时保护模块资产整合（`engine/protect-module/`，v9.4.27）：`security_check.dex`、4 ABI `libsecurity_check.so`、源码参考
  - 纯 Python 二进制 Manifest 注入器 `manifest_inject.py`（移植 AndroidManifestModifier.kt）
  - 保护模块注入器 `merge_protection.py`（Manifest/DEX/SO/features.cfg + 重打包 + 重签）
  - 二合一入口 `protect_plus.py`；`protect.py --plus` 一键转交
  - 复用 jar 内置 apksig 的重签助手 `SignerHelper.java`
  - GitHub Actions 构建脚本
  - 端到端验证通过（Manifest/DEX/SO/features.cfg/签名 v1+v2+v3 全绿）
- **进行中**：无
- **已搁置**：无需 Android SDK 的前提下，将保护模块源码（Kotlin/C++）接入本仓库直接编译出 dex/so 的流水线（当前使用预编译产物）
- **最近可运行 commit**：`ec69b22`

---

## 7. 待开发内容

| 功能 | 优先级 | 预估工时 | 前置依赖 |
|------|--------|----------|----------|
| 保护模块源码就地编译（d8/NDK 构建 dex/so） | P1 | 1-2 天 | 接入 android.jar 与 NDK toolchain |
| `protect.py --plus` 支持 AAB 输入 | P2 | 0.5 天 | 引擎 AAB 支持（参考 -f 处理） |
| features.cfg 按功能粒度注入（--features 与壳类排除联动） | P2 | 0.5 天 | 现有 --features 参数 |
| Windows 一键脚本（.bat） | P3 | 0.5 天 | 无 |
| CI 增加签名密钥注入（secrets 加固正式包） | P3 | 1 天 | 仓库 secrets |

---

## 8. 架构与关键模块

```
FrostShell-CLI/
├── engine/
│   ├── ironshell.jar                  # 加固引擎（自包含 zipalign/apksig/dx，命令行调 java -jar）
│   ├── shell-files/                   # 壳运行时资产（各 ABI .so + proxy dex + 密钥）
│   ├── protect-module/                # ★ ADFXCBNM 运行时保护层
│   │   ├── dex/security_check.dex     #   检测字节码
│   │   ├── libs/{abi}/libsecurity_check.so
│   │   ├── manifest_inject.py         #   纯 Python 二进制 Manifest 注入器
│   │   ├── features.cfg.template
│   │   ├── exclude.rules
│   │   └── src/                       #   保护模块源码（SecurityCheckProvider.java / protection.cpp / S_obfuscated.java）
│   ├── protect-config-template.json
│   └── exclude-classes-template.rules
└── scripts/
    ├── protect.py                     # 快捷加固（--plus 转交 protect_plus.py）
    ├── protect_plus.py                # ★ 二合一入口（阶段一函数抽取 → 阶段二保护注入）
    ├── merge_protection.py            # ★ 保护模块注入器（重打包 + 重签 + 校验）
    ├── SignerHelper.java              # 重签助手（复用 jar 内 apksig）
    └── make_test_apk.py               # 测试 APK 构造（无 SDK）
```

**核心入口 / 调用链**：
1. `protect.py` →（`--plus`）→ `protect_plus.py`
2. `protect_plus.py`：阶段一 `java -jar ironshell.jar -f <apk> -c cfg -o out` → 阶段二 `merge_protection.inject(...)`
3. `merge_protection.py`：读 APK 条目 → `manifest_inject.modify_manifest()` 注入 provider → 追加 `classes<N>.dex` → 注入 4 ABI SO（4096 对齐）→ 写 `assets/features.cfg`（含签名 SHA-256 基线 + dex CRC 基线）→ 手工 zip 重打包（`ZipBuilder`）→ `SignerHelper`（apksig v1+v2+v3）重签 → `verify()` 自检

**前后端对接点**：无前后端，纯 CLI + jar 引擎。
**已知技术债**：
- 重打包用自研 `ZipBuilder`，虽已验证，但极端 APK（非常规压缩、多 comment）未全覆盖
- 保护模块以预编译产物交付，源码侧未接构建链
- 脚本中 JDK 自动下载 URL 固定版本，需随 Temurin 发布维护

---

## 9. 代码概览与已知问题

- **结构简述**：见第 8 节树。核心逻辑集中在 `scripts/protect_plus.py`（编排）、`scripts/merge_protection.py`（注入/重打包/重签/校验）、`engine/protect-module/manifest_inject.py`（二进制 XML 解析/重写）。
- **致命/严重问题**：无已知致命问题。
- **注意点（非致命）**：
  1. 引擎内部 apksig 签名对个别 APK（无 minSdk 等）会报 `not supported on API Level(s) 9-17`，导致 `*_signed.apk` 为 0 字节 —— `protect_plus.py` 已回退使用 `*_unsign.apk`（merge 阶段会统一重签），不影响最终产物。
  2. `apksigner verify` 无 minSdk 参数时会按 API 9-17 校验报错，属误报，加 `--min-sdk-version 21` 即可（README/CI 已处理）。

---

## 10. 架构简评

- **架构模式**：面向过程的命令行工具 + 黑盒引擎 jar，模块按职责拆分为「编排 / 注入 / 签名 / 校验」四段。
- **整体评价**：良好。分层清晰（壳 vs 运行时保护），注入与重打包逻辑自洽且已端到端验证。
- **突出问题与改进方向**：
  1. 自研 `ZipBuilder` 与引擎内置 zipalign 功能重叠，长期应统一到单一可靠实现（或直接调 jar 内 zipalign）。
  2. 保护模块源码与产物分离，建议把 d8/NDK 构建纳入仓库，保证可追溯、可升级。
  3. 常量与路径散落脚本各处，建议引入统一配置（如 `protect-module/defs.py`）。
  4. JDK 自动下载固定版本，建议改为动态解析 Temurin API。
  5. 缺少自动化单元测试（至少为 `manifest_inject` / `ZipBuilder` 补 pytest）。

---

## 11. 测试建议

- **核心流程可走通**：已验证。`make_test_apk.py` → `protect.py --plus` → `_plus_signed.apk`，apksig v1+v2+v3 通过。
- **重点测试模块/场景**：
  - `manifest_inject.py`：对真实 App 的二进制 Manifest 注入/重写（重点：字符串池 UTF-8/UTF-16、多命名空间、resource_id）
  - `merge_protection.py`：多 dex、已存在同名 SO、非 4 对齐输入
  - `--features` 部分功能注入
  - 真实 App（minSdk>=26）在模拟器/真机启动到首页、root/Frida 环境触发检测
- **已知/可能边界问题**：
  - 超大 APK（>100MB）重打包性能
  - 带 APK Signature 扩展（v3 多 signer、SourceStamp）的输入
  - 壳引擎对部分反射密集类不兼容（需 `-r` 排除）

---

## 12. 账号与密钥

- **功能依赖**：唯一需要的是**签名 keystore**（自备或用脚本生成的 debug 签名）。
- **申请方式**：keystore 由用户自行生成（`keytool`，见第 3 节），不入库、不入源码包。CI 场景可将 keystore 与密码放 GitHub Secrets（`KEYSTORE`/`KEYSTORE_PASS` 占位）。
- **第三方 API Key**：无。

---

## 13. 常见问题

| 报错 / 现象 | 解法 |
|------------|------|
| `java: command not found` | 安装 JDK 17+；Debian: `apt-get install -y openjdk-17-jdk-headless`；或 `--local-jdk <目录>` |
| `--auto-setup` 下载 JDK 截断失败 | 用系统包管理器装 JDK，或手动下载 Temurin 后 `--local-jdk`（见第 4 节） |
| `JAR signature ... not supported on API Level(s) 9-17` | apksig 校验误报，验签时加 `--min-sdk-version 21` |
| 引擎 `*_signed.apk` 为 0 字节 | 属引擎内部签名失败；`protect_plus.py` 自动回退 unsign 产物，最终 `_plus_signed.apk` 正常 |
| `zipfile.BadZipFile` / 重打包后 ZIP 损坏 | 属历史 bug（ZipBuilder 中央目录 offset / deflate 头），当前版本已修复，如再遇请反馈 commit `ec69b22` |
| 加固后装不上/秒退 | 多为 minSdk 或反射类不兼容；用 `-r exclude.rules` 排除，或 `--debug --noisy-log` 看 logcat |
| 依赖下载失败 | 按第 4 节优先级：本地缓存 → 国内镜像 → 官方源 → 代理列表 → 手动下载 |

---

## 14. 验收标准

1. **本地**：按第 3 节命令从 clone 到产出 `out/*_plus_signed.apk`，`apksigner verify --min-sdk-version 21` 通过 → 接管成功。
2. **CI**：push 到 `main` 后 `.github/workflows/build-apk.yml` 自动运行，Artifacts 中出现 `frostshell-plus-apk`（内含 `*_plus_signed.apk`）→ 视为自动出包成功。
3. **产物**：源码包 `FrostShell_source.zip`、演示 `FrostShell_v2.20.0_debug.apk` 随仓库发行。

---

*本交接文档基于 commit `ec69b22` 生成；远程仓库 `https://github.com/yz8023/safe-apk`。*
>>>>>>> origin/main
