# HANDOVER.md · Android 加固工具（AndroidHardeningTool）

> 项目代号：AndroidHardeningTool / safe-apk
> 交接对象：接手者
> 交接目标：30 分钟内从零构建并成功运行本工具，产出加固后的 APK，并在 GitHub Actions 上自动出包。
> 交接日期：2026-09-12
> 交接人：MonkeyCode AI 交接专家
> 交接范围：Android 加固工具 App（AndroidHardeningTool）；FrostShell-CLI 为附属子项目。

---

## 1. 项目概述

- **定位**：**Android APK 加固工具**（App 本身）。用户在 App 内选择设备上已安装/本地的 APK，勾选「DEX 加密 + 虚拟化」「SO 保护」「类/字段重命名」「字符串加密」「debug 移除」「JniBridge 注入抽取」等加固项，内置 FrostShell 加固引擎（Kotlin 移植版，代码抽取 + 指令池 + 壳 so 还原），输出重签名后的加固 APK。
- **技术栈**：
  - App 壳层 + FrostShell 引擎：Kotlin 1.9.20（包 `com.adfxcbnm.frostshell.*`）
  - UI：Jetpack Compose（Material3，BOM 2023.10.01）
  - 原生保护：C++17（`app/src/main/cpp/`，产出 libsecurity_check.so）
  - 壳引擎 so：`app/src/main/assets/` 内置 `lib8012d9ae47c7f010.so` 等（编译期 asset，不打 native 库）
- **minSdk / targetSdk**：`26` / `34`（compileSdk 34，buildTools 34.0.0）。
- **包名 / 应用名**：applicationId `Forinxy.safe`；应用名「Android加固工具」；namespace `com.adfxcbnm.hardeningtool`。
- **当前版本**：`9.10.48`（versionCode 90）。
- **commit 哈希**：`22b8354`（HEAD，版本号 9.10.48，回退至v9.10.41抽取逻辑）；`e8c2126`（v9.10.47多dex对齐）；`2f8cee9`（v9.10.46两遍结构）。
- **远程仓库**：`https://github.com/yz8023/safe-apk.git`（分支 `260911-fix-anr-frost-group-round-float`）。
- **备用镜像**：`https://kkgithub.com/yz8023/safe-apk.git`（无法直连 GitHub 时使用）。

## 2. 开发环境

| 项 | 要求 |
|----|------|
| OS | Linux（本工程验证于 Debian 12） |
| JDK | 17（source/target 均 17，Java 17 特性） |
| Gradle | 8.4（wrapper 自带） |
| AGP | 8.2.0 |
| Kotlin / Compose 编译器 | KGP 1.9.20 / kotlinCompilerExtensionVersion 1.5.5 |
| compileSdk / build-tools | 34 / 34.0.0 |
| NDK / CMake | 25.1.8937393 / 3.22.1（C++17） |
| 环境变量 | 非必须；推荐 `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64`、`ANDROID_HOME=<sdk>` |
| local.properties | `sdk.dir=<Android SDK 路径>` |
| 第三方 Key | 无外部密钥；签名 keystore 内置在 `app/src/main/assets/`（默认口令，非机密） |

## 3. 构建运行

### 从 clone 到 debug 包（复制粘贴即可执行）

```bash
git clone https://github.com/yz8023/safe-apk
cd safe-apk
git checkout 260911-fix-anr-frost-group-round-float

# 设置 SDK 路径
export ANDROID_HOME=<你的 Android SDK 路径>
echo "sdk.dir=$ANDROID_HOME" > local.properties

# 清理 CMake 缓存（曾移动过项目目录时必须执行，否则报 CXX1409）
rm -rf app/.cxx

# 构建 debug APK
./gradlew :app:assembleDebug --no-daemon --console=plain
```

- 产物：`app/build/outputs/apk/debug/app-debug.apk`（约 52MB）。
- 安装运行：`adb install -r app/build/outputs/apk/debug/app-debug.apk`，启动「Android加固工具」→ 选择 APK → 勾选加固项 → 加固 → 输出到 `/storage/emulated/0/ADFXCBNM/`。
- **GitHub Actions 构建脚本**：`.github/workflows/build-apk.yml`，两种触发方式：
  1. push 到 `main` 分支（自动）；
  2. GitHub 页面 Actions → 手动 `Run workflow`（workflow_dispatch）。
  产物以上传 artifact `app-debug-apk`。

### 已知坑与解法

1. **移动目录后 CMake 缓存报错**：`CXX1409 ... custom target ... could not be found` → `rm -rf app/.cxx` 后重建。
2. **deps jar 禁止混入 assets**：`libs/ironshell-deps.jar` 只含字节码；`ironshell.jks` 等资产放 `assets/`，否则打包报 `already contains entry 'assets/ironshell.jks'`。
3. **离线构建依赖**：离线环境需提前在 gradle 缓存/本地 maven 就绪 compose 依赖，否则加 `--offline` 构建失败；参考 §4 配置镜像。

## 4. 依赖与镜像

| 依赖 | 来源 |
|------|------|
| androidx.* / compose（BOM 2023.10.01） | Google Maven |
| apksig 8.5.0（`com.android.tools.build:apksig`） | Google Maven（tool 自身用它签名产物） |
| `app/libs/ironshell-deps.jar` | 本地 jar（Dex 操作引擎，必须保留在 libs/） |
| `app/libs/bcprov-jdk15on-1.67.jar` | 本地 jar（crypto provider） |
| Gradle 8.4 发行版 | gradle-wrapper 自动下载 |
| NDK 25.1.8937393 / CMake 3.22.1 | sdkmanager 安装 |

获取优先级：**本地缓存 → 国内镜像 → 官方源 → 手动下载**。

| 官方源 | 镜像地址 |
|--------|----------|
| Google Maven | `https://maven.aliyun.com/repository/google` |
| JCenter | `https://maven.aliyun.com/repository/jcenter` |
| Maven Central | `https://maven.aliyun.com/repository/central` |
| Gradle 发行版 | `https://mirrors.cloud.tencent.com/gradle/` |

GitHub 相关下载（.so/.jar/element）失败时按序尝试代理：
`ghproxy.net`、`gh-proxy.com`、`mirror.ghproxy.com`、`ghps.cc`、`ghfast.top`、`ghproxy.cc`、`gitclone.com`、`kkgithub.com`、`hub.gitmirror.com`、`github.moeyy.xyz`、`hub.njuu.cf`、`gitproxy.click`。
需手动下载的（如 `sdkmanager --install "ndk;25.1.8937393"`）按官方地址下载后放置于 `$ANDROID_HOME/ndk/25.1.8937393`。

## 5. 本地依赖服务

- 无。本工具为纯本地 APK 处理工具：不需要后端、数据库、Redis、网络服务。唯一外部运行时依赖为 Android 设备/SDK 环境。

## 6. 开发进度

- **已完成**：v9.10.44 全量类/字段重命名（跨 dex 原子化）、`Debug 信息移除`、FrostShell 引擎抽取上线、全量抽取修复（方法级 methodFilter 与类级 excludeRules 均不再跳过抽取，共享 code 统计按 dex 分桶消除竞态）、App UI（Compose）+ 悬浮按钮；v9.10.46 池对齐两遍结构修复（见 §9）。
- **进行中**：加固产物真机冷启动最终复测（palm/gitapp 加固后确认不再抛 VerifyError，详见 §9；抽取侧修复已端到端验证，待真机确认）。
- **已搁置**：FrostShell-CLI（Python 版命令行加固器，见 `FrostShell-CLI/`）；多引擎/插件化架构改造。
- **最近可运行 commit**：`2f8cee9`（HEAD，v9.10.46，池对齐两遍结构修复 + 版本号更新）；`ab960e1`（v9.10.45，已推送远程）。

## 7. 待开发内容

| 内容 | 优先级 | 预估工时 | 前置依赖 |
|------|--------|----------|----------|
| v9.10.46 真机冷启动复测（palm/gitapp 加固后不崩 VerifyError） | P0 | 4h | 真机/模拟器 |
| 端到端加固回归测试（抽取计数 = 有 code 方法数 - 共享 code 数 的自动化断言） | P1 | 4h | 无 |
| 池契约文档化（池格式 version=2、RC4 key、method_ids 全表对齐、与 so 对齐规则） | P1 | 2h | 无 |
| 单测补充（dexlib2/dx 修复合写后验证、池序列化 round-trip、占位条目还原） | P1 | 8h | 无 |
| 多 ABI（arm/x86）真机矩阵验证 so 还原一致性 | P1 | 4h | 真机矩阵 |
| FrostShell-CLI 收敛复用 App 内引擎 | P2 | 8h | 无 |
| 插件市场 / 多加固引擎接入 | P3 | 16h | 架构改造 |

## 8. 架构与关键模块

### 模块划分

- **App 壳层**：`com.adfxcbnm.hardeningtool.*`（UI、配置面板、ApkMethodScanner、StageTracker、MethodRuleTemplate）。
- **FrostShell 加固引擎**：`com.adfxcbnm.frostshell.*`
  - `builder/FrostApk.kt`：对外入口 `protect() → process() → buildPackage()`。
  - `builder/FrostAndroidPackage.kt`：加固管线编排（注入 JniBridge → splitDex(keep-in-place) → 字符串加密 → 文件级混淆 → 类/字段重命名 → 阶段3抽取 → 打包）。
  - `builder/FrostIronShell.kt`：将 `ironshell.dens` 池与密钥补丁写入 assets so（符号打补丁）。
  - `builder/FrostAab.kt` / `res/FrostAabManifestEditor.kt` / `res/FrostApkManifestEditor.kt`：APK/AAB 重打包与 manifest 改写。
  - `dex/*`：Dex 操作（混淆、重命名、Junk 代码、字符串加密/解密、字符串池、JniBridge 注入）。
  - `util/FrostDexUtils.kt`：Dex 抽取核心（extractAllMethods、stub 写入、saveCodeOffAppear）。
  - `util/FrostMultiDexCodeUtils.kt`：指令池序列化（version=2）。
  - `config/FrostProtectRules.kt` / `FrostShellConfig.kt`：排除规则与加固配置。
  - `task/FrostThreadPool.kt`：并行抽取线程池（每 dex 一线程）。
- **原生**：`app/src/main/cpp/*`（security_check 检测库）；壳还原在 assets so 内（ARM64 可逆）。
- **前后端对接点**：无（纯本地，无网络服务端）。
- **已知技术债**：抽取序列化与 assets 内壳 so 强耦合、无池格式权威文档、引擎无自动化单测；`ironshell-deps.jar` 为闭源二进制。

### 关键路径说明（抽取 → 壳还原）

`extractAllMethods`（每 dex 并行）→ 写入 stub（方法体重写为 return+load）+ `instructionMap[dexNo]=List<Instruction>` → `makeMultiDexCode`（按 dex 顺序序列化池）→ 池整体 AES 加密 → `FrostIronShell` 写入壳 so → 运行时壳 so 用 `aesKey‖LE(methodIndex)` 作 RC4 key 按方法顺序解密写回。
壳 so 关键地址：JNI_OnLoad@0x9ffa8；RegisterNatives@0xb2120（10 项）；`ia()`=0x9e708、`rde`=0x9dd4c、`cbde`=0x9dcb4；池解析/写回主体 ≈0x9ec94/0x9ee50。

## 9. 代码概览与已知问题

- 结构简述：`app/src/main/java/com/adfxcbnm/` 下 `hardeningtool`（UI 壳）与 `frostshell`（引擎）两包，Kotlin 主代码约 60 文件；assets 下含壳 so、签名文件、安全库；`libs/` 两个本地 jar。

### 致命 / 严重问题

1. **加固产物 VerifyError（三次真机复现，已根因定位 + 两轮抽取侧修复，待真机终验）**
   - 现象：`java.lang.VerifyError: Verifier rejected class ...MainActivity ... invalid argument count (8) exceeds outsSize (2)`，涉及 onCreate/onBackPressed/onDestroy/onActivityResult/c/e/f/b/d 等方法的指令因还原错位被换成了其他方法的字节（指令 outs 需求大于声明）。
   - 根因 A（单 dex 内 method_index 维度，v9.10.46 修复）：壳 so 还原时按「方法记录的 method_index」直接索引池条目 vector（`0xa0868 vector[methodIndex]`），**并非按池文件顺序消费**，也**不跳过共享 code_item**。抽取侧必须保证：池条目数 == method_ids 总数、每个 method_index 恰一条、且按 method_index 升序连续排列。此前两个错位源：① 抽取阶段按 methodFilter/excludeRules 类级跳过方法（palm classes.dex 曾仅 9/61234 条入池）；② 即使全量抽取，method_ids 中仍有大量索引未在任何 class_data 中声明（palm classes.dex 65441 个 method_ids 中 4207 个缺失，classes7 65266 中 11195 个缺失），只遍历 class_data 生成的紧实池 vector 仍会错位（对齐率仅 9/62159）。
   - 修复 A（v9.10.46，两遍结构，端到端验证 PASS）：第一遍按 class_data 顺序遍历所有方法，抽取指令、stub 写回（共享 code 用缓存原始指令、极小方法不 stub）、RC4 加密后按 method_index 存入 map；第二遍按 `dex.methodIds()` 全表 `0..methodCount` 顺序出池条目，map 未命中的索引全部补 size=0 占位条目。池条目数恒等于 method_ids 总数、天然升序、完全连续。
   - 根因 B（多 dex「池块 ↔ dex 序号」维度，v9.10.47 修复）：v9.10.46 发布后 gitapp（9 dex 级、R8 混淆）冷启动仍全部方法错位。反汇编确认壳 so 池解析（`0x9ec94`）按 `dexCodesIndex[i]` 顺序给每 dex 建 512KB vector（`vector[i]=第 i 块`），还原时用运行时从 dex 标识解析出的序号 w21（`0x42880`，dex 文件名尾部数字 -1，如 classes2.dex→w21=1）定位 vector。因此契约还要求「池内第 k 块 == dexNo k 的方法」。原 `makeMultiDexCode` 按 HashMap 迭代序遍历且**缺失 dexNo 时跳过（continue）**、`dexCount=map.size`——任意 dexNo 缺失（抽取失败/并发 put 丢失）都会让该 dexNo 之后所有 dex 的整体块错位（整类 VerifyError 复现）。并实测 HashMap 并发 put 确有低频丢 key；palm 5 dex 因全成功而无此现象（故端到端 PASS 但 gitapp 必现崩溃）。
   - 修复 B（v9.10.47）：`makeMultiDexCode` 改为按 `0..maxDexNo` 显式遍历，缺失 dexNo 写 methodCount=0 空块占位，`dexCount=maxDexNo+1`（不再依赖 map.size/迭代序）；阶段③抽取 map 由 HashMap 改 ConcurrentHashMap 消除并发丢 key。验证：`/tmp/pool_test/MultiDexVerify.java` 构造缺失 dexNo=3 的 9 dex 场景——修复前 5 块级联错位、修复后 0 错位（8 块正确 + 1 空块占位）。
   - 待办：gitapp/palm 真机冷启动终验（v9.10.47 产物）。若仍崩，剩余变量集中于 so 端 w21 输入字符串语义与抽取端 dexNo 的一致性，需进一步逆向 `0x42880` 调用处。
2. **抽取/壳契约依赖闭源 so，无文档**：池格式 version=2（version+dexCount+dexCodesIndex+各 dex[methodCount+条目区]），壳 so 以 method_index 索引条目、RC4 key = AES key + method_index(LE)，共享 code 还原用缓存原始指令；任何抽取侧改动必须保持「池 = method_ids 全集、按 method_index 升序、缺失索引占位」。该契约已写死并附端到端验证工具，应加回归断言（见 §7 P1 项）。

## 10. 架构简评

- **定位**：中等规模的单 App 加固工具，模块划分为「UI 壳 + 引擎」两层。
- **整体评价**：良好。管线编排清晰（FrostAndroidPackage 阶段化），抽取/池/补丁链路有明确的时序注释。
- **突出问题与改进方向**：
  1. 抽取-池-壳 so 三方 `顺序契约` 无自动化测试与文档，回归成本高 → 建立「抽取计数 = 有 code 全集 - 共享 code」断言 + 池格式文档。
  2. 壳 so 只验算了 ARM64 恢复，多 ABI（arm/x86）还原的一致性未覆盖 → 真机矩阵验证。
  3. 并行抽取的共享状态（codeOffAppearMap）出现过竞态；已分桶修复，但建议进一步把「每 dex 独立统计」前置为流水线步骤（phase），避免在任务内做共享写。
  4. 引擎核心仍依赖闭源 `ironshell-deps.jar`，无源码可审 → 规划逐步以 dx 库替代或容器化。
  5. UI 层缺测试；加固流程无“进度-产物-日志”统一的持久化记录 → 增加运行日志落盘便于线上排查。

## 11. 测试建议

- 核心流程（选择 APK → 加固 → 签名 → 输出）本地可走通，已用 `libs` + dx 库做过抽取计数与管线 smoke 验证（RunFrost / CountExtracted / SimFilterFix，见 `/tmp/opencode/regress/`）。
- **端到端池对齐验证（v9.10.46 新增）**：`/tmp/pool_test/verify_endtoend.java` 对目标 dex 执行真实抽取+加密+写池，独立 RC4 解密逐条对比原 dex 指令，断言池条目数 == method_ids 数、连续唯一、解密命中 100%。palm 5 个 dex 已全部 PASS。
- **重点测试模块/场景**：
  - 加固后 APK **真机安装冷启动**：确认不再抛 VerifyError（v9.10.46 两遍结构修复的核心验收）。
  - 含大量第三方库混排的大型 APK（主 dex > 5 万方法）抽取后池序对齐。
  - 多 dex、keep-in-place（Compose）类处理、共享 code_item 方法、abstract/native 方法。
  - 类/字段重命名跨 dex 原子性（任一 dex 失败整体回滚）。
- **已知/可能边界问题**：池 > 512KB 单 dex 缓冲区的块内条目截断风险（so 侧验证过解析头）；并行抽取下 GC 压力；超 100MB 应用处理时长。

## 12. 账号与密钥

- **功能依赖**：无任何后台 API。
- **签名**：工具产出 APK 使用内置 keystore（`assets/ironshell.jks`，默认口令），接管无需申请密钥。
- **GitHub**：若需 CI 自动出包，仓库绑定 GitHub token/Secret（公开仓库可不用）；推送到 `main` 触发 `.github/workflows/build-apk.yml`。
- 一切真实密钥以占位符处理，勿写入仓库。

## 13. 常见问题

| 问题 | 解法 |
|------|------|
| 加固后 App 冷启动 `VerifyError: invalid argument count exceeds outsSize` | 单 dex 维度 v9.10.46 两遍结构已修复；多 dex 「池块↔装置序号」级联错位 v9.10.47 修复（按 dexNo 显式对齐 + 空块占位 + ConcurrentHashMap）。若仍崩按 §9 检查壳 so 消费链对齐 |
| `CXX1409 custom target could not be found` | `rm -rf app/.cxx` 后重建 |
| 打包报 `already contains entry 'assets/ironshell.jks'` | deps jar 不能混入 assets；jks 仅放 `app/src/main/assets/` |
| `apksig` 类找不到（CLI 侧 RunFrost） | classpath 追加 `apksig-8.2.0.jar`（`~/.gradle/caches/modules-2/files-2.1/com.android.tools.build/apksig/...`） |
| 依赖下载失败 | 按 §4 优先级：本地缓存 → aliyun 镜像 → 官方 → 手动；GitHub 下载失败用上表代理 |
| `--offline` 构建失败 | 说明部分依赖未入缓存；去掉 `--offline` 联网拉依赖 |
| targetSdk 34 安装提示未知 | 需 Android 8.0+（minSdk 26） |

## 14. 验收标准

1. `./gradlew :app:assembleDebug` 成功产出 `app-debug.apk`（约 52MB），安装后可启动工具首页。
2. 用该工具加固一个真实 APK，输出重签名加固包，真机安装冷启动**不抛 VerifyError**、功能正常。
3. push 到 `main` 或手动 Run workflow 后，GitHub Actions 自动构建并产出 `app-debug-apk` artifact。
4. （交接快照）源码包 `Android加固工具_v9.10.46_source.zip` 与 `Android加固工具_v9.10.46_debug.apk` 与仓库 HEAD 一致。