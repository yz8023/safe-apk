# HANDOVER.md · Android 加固工具（AndroidHardeningTool）

> 项目代号：AndroidHardeningTool
> 交接对象：接手者
> 交接目标：30 分钟内从零构建并成功运行本工具，产出加固后的 APK。
> 交接日期：2026-09-05
> 交接人：MonkeyCode AI 交接专家

---

## 1. 项目概述

- **定位**：Android **APK 加固工具**（App 本身）。用户在 App 内选择设备上已安装的 APK，勾选加固/保护项，对目标 APK 执行 DEX 抽取加固（FrostShell 引擎）+ 运行时保护（native 检测）二合一处理，输出重签后的加固 APK。
- **技术栈**：Kotlin 1.9.20（App 壳层 + FrostShell 引擎，包 `com.adfxcbnm.frostshell.*`）+ C++17（原生保护库 `protection.cpp`）+ Compose（Material3）UI。
- **minSdk / targetSdk**：`26` / `34`（Android 8.0 - Android 14）。
- **包名 / 应用名**：applicationId `Forinxy.safe`；应用名「Android加固工具」；namespace `com.adfxcbnm.hardeningtool`。
- **当前版本**：`9.7.0`（versionCode 39）。
- **commit 哈希**：`3611559`（`feat: L1字符串加密与SO命名黑名单约束，版本提升至v9.7.0`）。
- **远程仓库**：`https://github.com/yz8023/safe-apk.git`（分支 `260902-fix-manifest-resource-id`，PR #1）。

## 2. 开发环境

| 项 | 要求 |
|----|------|
| OS | Linux（本工程验证于 Debian 12） |
| JDK | 17（source/target 均为 17） |
| Gradle | 8.4（wrapper，已含 gradle-wrapper.jar） |
| AGP | 8.2.0 |
| Kotlin / Compose 编译器 | KGP 1.9.20 / kotlinCompilerExtensionVersion 1.5.5 |
| compileSdk / build-tools | 34 / 34.0.0 |
| NDK / CMake | 25.1.8937393 / 3.22.1（C++17，`app/src/main/cpp/`） |
| 环境变量 | 无必须项；`JAVA_HOME` / `ANDROID_HOME` 可选 |
| 第三方 Key | 无（签名 keystore 内置在 assets，默认口令，非机密） |

## 3. 构建运行

### 从 clone 到 debug 包（复制粘贴即可执行）

```bash
git clone https://github.com/yz8023/safe-apk
cd safe-apk

# 设置 SDK 路径（或写入 local.properties: sdk.dir=<你的 SDK 路径>）
export ANDROID_HOME=<你的 Android SDK 路径>

# 清理 CMake 缓存（若曾移动过项目目录，必须执行，否则报 CXX1409）
rm -rf app/.cxx

# 构建 debug APK
./gradlew :app:assembleDebug --no-daemon --console=plain
```

- 产物：`app/build/outputs/apk/debug/app-debug.apk`（约 48MB）。
- 安装运行：`adb install -r app/build/outputs/apk/debug/app-debug.apk`，启动「Android加固工具」，选择 APK 并加固。
- **GitHub Actions 构建脚本**：`.github/workflows/build-apk.yml`（push 到 `main` 或手动 `workflow_dispatch` 触发，构建 debug APK 并上传 artifact）。

### 已知坑与解法

1. **移动目录后 CMake 缓存报错**：`CXX1409 ... custom target ... could not be found` → `rm -rf app/.cxx` 后重构建。
2. **deps jar 禁止混入 assets**：`libs/ironshell-deps.jar` 只含字节码；`ironshell.jks` 等资产放 `assets/`，否则 zipflinger 报 `already contains entry 'assets/ironshell.jks'`。
3. **不要用 `ApkSignerTool.main` 签名**：会 `System.exit` 杀掉进程；用编程式 `com.android.apksig.ApkSigner.Builder`（v1/v2/v3）。
4. **不要升级 apksig 依赖**：`com.android.tools.build:apksig:8.5.0` 需与 AGP 自带 apksig 一致，否则 `checkDebugDuplicateClasses` 报重复类。

## 4. 依赖与镜像

依赖获取优先级：**本地缓存 → 国内镜像 → 官方源 → 手动下载**。

仓库源（`settings.gradle.kts`，已全部指向阿里云镜像）：

| 官方源 | 镜像地址 |
|--------|----------|
| Google Maven | `https://maven.aliyun.com/repository/google` |
| Maven Central | `https://maven.aliyun.com/repository/central` |
| Maven Public | `https://maven.aliyun.com/repository/public` |
| Gradle 插件 | `https://maven.aliyun.com/repository/gradle-plugin` |
| Gradle 发行版 | `https://services.gradle.org/distributions/gradle-8.4-bin.zip`（官方源） |

主要依赖：

- `androidx.core:core-ktx:1.12.0`、`androidx.lifecycle:lifecycle-runtime-ktx:2.6.2`
- `androidx.activity:activity-compose:1.8.1`
- Compose BOM `2023.10.01`（ui / ui-graphics / material3 / material-icons-extended）
- `com.android.tools.build:apksig:8.5.0`（重签名）
- 本地 jar：`libs/ironshell-deps.jar`（11.8MB，FrostShell 引擎字节码，编译必需）

备用镜像（阿里云不可用时）：

- Google Maven：`https://maven.google.com`
- Gradle 插件：`https://plugins.gradle.org/m2`
- Maven Central：`https://repo1.maven.org/maven2`
- Gradle 发行版：`https://mirrors.cloud.tencent.com/gradle/`
- GitHub 依赖下载失败代理列表（依次尝试）：`https://ghproxy.com/`、`https://mirror.ghproxy.com/`、`https://gh-proxy.com/`、`https://ghfast.top/`、`https://ghps.cc/` 等，用法为 `https://<proxy>/https://github.com/...`

## 5. 本地依赖服务

- **无**。本工具为纯本地 APK 处理，不需要后端/数据库/Redis 等外部服务即可完整运行。加固全程在设备内完成。

## 6. 开发进度

- **已完成**：
  - FrostShell 引擎（Kotlin 移植）：DEX 抽取加固（`extractDexCode`）、SO 复制/加密、壳 dex 打包、编程式签名。
  - 运行时保护：native 检测（Frida/Xposed/Magisk/Hook/完整性等 24 项，`ProtectionNative.kt` + `protection.cpp`）+ 自毁/CRITICAL 集合（`SecurityCheckProvider.java`）。
  - L1 字符串加密（`FrostStringEncryptor`）+ SO 命名黑名单约束（`SoNamePolicy`）+ SO 随机化（`SoNameRandomizer.randomizeSafe`）。
  - 方法级抽取（仅抽取指定函数）：`FrostProtectRules.memberRules` 按 类名.方法名/类名.* 过滤，未命中方法保留原始指令不进入指令池（`FrostDexUtils.extractAllMethods`），UI 入口在 FrostShell 引擎选项"仅抽取指定函数"。
  - 加固类型选择 UI（FrostShell 引擎选项：keep-classes / smaller / verify-sign / ABI 剔除 / SO 随机化 / 伪装 / 字符串加密 / 方法级抽取）。
  - 输出兜底：公共共享目录(如 /storage/emulated/0/Download) File 直写 ENOENT 时经 MediaStore.Downloads 落盘（`OutputSettings.copyOutput`），三条加固链路（传统/叠加/引擎）均接入。
  - CI：`.github/workflows/build-apk.yml`。
- **进行中**：（无）
- **已搁置**：
  - `FrostReflectionClinitInjector`（反射类名混淆注入）OOM 修复中间态已 `git stash`（`stash@{0}`），未提交。
  - native 侧 VMP 解释器 / RC4 SO 解密 / ELF section 注入：仅文档对接点（`docs/NATIVE-DOCKING.md`），未实现。
- **最近可运行的 commit**：`1c2dce4`（v9.8.0，后续 ENOENT 修复见 git log）。

## 7. 待开发内容

| 功能 | 优先级 | 预估工时 | 前置依赖 |
|------|--------|----------|----------|
| FrostReflectionClinitInjector 恢复并修复 OOM | P1 | 4h | 恢复 `stash@{0}`，重建 dex 字符串池写入逻辑 |
| native VMP 指令解释器接入 | P2 | 16h | NDK 环境 + `docs/NATIVE-DOCKING.md` §2 方案 |
| native RC4 SO 解密 + 自校验 | P2 | 8h | `docs/NATIVE-DOCKING.md` §3 |
| ELF section 注入（vmp.bin） | P3 | 8h | `docs/NATIVE-DOCKING.md` §4 |

## 8. 架构与关键模块

### 引擎装配链路

```
MainActivity (UI + 开关) → FrostEngineOptions → FrostShellEngine.protectApk()
→ FrostApk.Builder.build().protect() → FrostAndroidPackage.protect()
  → FrostApk.process(): 解包 → extractDexCode(L1字符串加密+抽取) → junk code
  → compressDexFiles → copyNativeLibs → encryptSoFiles → writeConfig → buildPackage(签名)
```

### 核心模块

| 模块 | 路径 | 职责 |
|------|------|------|
| 主界面 / 流程编排 | `hardeningtool/MainActivity.kt` | UI、APK 选择、加固编排、日志、`useFrostEngine` 开关 |
| 引擎封装 | `hardeningtool/FrostShellEngine.kt` | `prepare` / `protectApk` / `findOutputApk` |
| 引擎选项 | `hardeningtool/FrostEngineOptions.kt` | 加固配置数据类（含字符串加密/SO 随机化等） |
| 抽取引擎 | `frostshell/builder/FrostAndroidPackage.kt` | `extractDexCode` / `copyNativeLibs` / `encryptSoFiles` / `buildPackage` |
| 重打包/签名 | `frostshell/builder/FrostApk.kt` | 解包重打包 + 编程式 ApkSigner v1/v2/v3 |
| 配置透传 | `frostshell/config/FrostShellConfig.kt` | 单例 JSON 序列化进 `assets/irn/.meta` |
| 字符串加密 | `frostshell/dex/FrostStringEncryptor.kt` | L1 XOR 加密 pass + 静态 helper |
| SO 命名策略 | `hardeningtool/SoNamePolicy.kt` / `SoNameRandomizer.kt` | 黑名单约束 + 随机名 + 兼容入口 |
| 原生保护 | `ProtectionNative.kt` + `cpp/protection.cpp` | 24 项运行期检测 JNI 桥接 |
| Manifest 编辑 | `hardeningtool/AndroidManifestModifier.kt` | 二进制 AXML 编辑 |

### 前后端对接点

- 引擎配置通过 `FrostEngineOptions`（数据类）传入；运行期配置经 `FrostShellConfig` 序列化到壳 APK `assets/irn/.meta`（JSON），字段名见 `FrostShellConfig.toJson()`：`insns_key`、`key_shard4`、`insns_store` 等。

### 已知技术债

- 引擎处理大 APK 时抽取阶段并行线程池占用内存较高。
- `FrostStringEncryptor` 目前只处理 const-string 字面量，未覆盖 `fill-array-data` 等复合数据结构。

## 9. 代码概览与已知问题

```
app/
├── build.gradle.kts               # v9.7.0 / versionCode 39
├── libs/ironshell-deps.jar        # FrostShell 引擎字节码依赖（11.8MB，必需）
└── src/main/
    ├── AndroidManifest.xml
    ├── cpp/CMakeLists.txt + protection.cpp   # 原生保护库
    ├── assets/
    │   ├── frostshell/            # 引擎运行时资源（libs/*.so, dex, build-key）
    │   ├── so_name_presets.json / string_presets.json  # 伪装预设 / 加密关键词库
    │   ├── lib/*/libsecurity_check.so  # 原生保护 .so（4 ABI）
    │   └── ironshell.jks          # 引擎默认签名 keystore
    └── java/com/adfxcbnm/
        ├── hardeningtool/         # 壳层（MainActivity/FrostShellEngine/...）
        ├── frostshell/            # 引擎 Kotlin（builder/config/dex/elf/model/res/task/util）
        └── protect/               # SecurityCheckProvider.java（native 绑定）
```

**致命/严重问题（仅列此类）**：

- （无致命问题。编译 `assembleDebug` 通过，aapt2 badging 校验包名/版本正确。）
- 轻微：`MainActivity.kt` 存在若干可空断言告警（`!!`），不影响功能。

## 10. 架构简评

- **架构模式**：加固引擎采用「Builder 装配 → 分阶段 pipeline 处理」；UI 与引擎解耦（`FrostEngineOptions` 数据类隔离）；native 层通过 JNI 桥接。
- **整体评价**：良好。模块边界清晰，配置透传链路完整，可扩展性强（新增开关只需 EngineOptions + Builder + UI 三处）。
- **改进方向**：
  1. 抽取引擎的并行线程池改为受控调度（当前 `CountDownLatch` 全量并行）。
  2. 将 `FrostShellConfig` 单例改为注入式，便于引擎级单元测试。
  3. L1 字符串加密补充 `fill-array-data` 处理与 helper 复用池。
  4. native 检测项输出结构化结果（当前为布尔合并）。
  5. 增加引擎中间产物的幂等清理（异常时残留临时目录）。

## 11. 测试建议

- **核心流程**：选 APK → 开 FrostShell 引擎 → 加固 → 输出加固 APK → 校验能安装运行。
- **重点测试模块**：
  - 字符串加密开关：开启后产物中目标敏感串（vip/token）不应以明文出现；关闭时产物不变。
  - SO 随机化 / 伪装：`assets/irn/{abi}/*.so` 名称符合黑名单规避约束。
  - keep-classes / smaller / verify-sign / ABI 剔除各开关组合。
  - 运行时保护：在 root/Frida 环境触发检测，确认 App 行为符合预期。
- **已知边界问题**：
  - minSdk 26 以下设备不可装。
  - 含 Compose 等 keep-in-place 类的目标 APK 依赖 `FrostDexUtils.dexContainsKeepInPlace` 分支。
  - 目标 APK 存在 VMP 等极端混淆时抽取可能失败（引擎有 `_split.dex` 兜底）。

## 12. 账号与密钥

- **无外部服务**，无 API Key。
- 签名 keystore：`assets/ironshell.jks`（内置默认，默认口令，非机密；如需更换，替换文件并在引擎 `setSignatureConfig` 传入新 keystore/别名/口令）。
- 如未来接入任何后端/密钥，一律使用占位符 `<YOUR_API_KEY>`，不得硬编码真实密钥。

## 13. 常见问题

| 报错 | 解法 |
|------|------|
| `CXX1409 custom target could not be found` | `rm -rf app/.cxx` 后重构建 |
| `Zip file already contains entry assets/...` | deps jar 只放字节码，资产放 `assets/` |
| `checkDebugDuplicateClasses` | apksig 版本与 AGP 一致（8.5.0），勿随意升级 |
| 依赖下载失败 | 换镜像（第 4 节）或 GitHub 代理 |
| 加固产物无法解析 | 检查 `--auto-verify` 日志；清单资源 ID 重写（v9.6.6 修复项） |

## 14. 验收标准

1. **构建**：按第 3 节命令，从干净环境成功产出 `app-debug.apk`（`aapt dump badging` 显示 `package name='Forinxy.safe' versionName='9.7.0' versionCode='39'`）。
2. **运行**：安装并启动到首页，能选择 APK 并完成一次加固，产物可安装运行。
3. **CI**：push 到 `main` 后 `.github/workflows/build-apk.yml` 自动构建出 debug APK 并上传 artifact。
4. 三样交接产物齐备：`HANDOVER.md`、`AndroidHardeningTool源码.zip`、`AndroidHardeningTool_v9.7.0_debug.apk`。
