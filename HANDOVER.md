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
- **当前版本**：`9.10.5`（versionCode 47）。
- **commit 哈希**：`bf6145b`（v9.10.0），`467caea`（v9.10.1），`473efc6`（v9.10.2），（v9.10.3~v9.10.5 见 §6）。
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
  - **v9.9.0 方法抽取增强（需求1）**：① `FrostProtectRules` 方法规则支持正则（`.*vip.*` 纯方法名正则、`regex:` 整体正则、类名正则 `Lcom/a/.*;.method`）；② 内置常用关键词模板（会员VIP/敏感业务/数据信息时间等，一键生成规则）+ 自定义方案保存/加载/删除（`MethodRuleTemplate`，prefs JSON）；③ 罗列所选 APK 的方法/类（`ApkMethodScanner`，解析 classes*.dex）+ 搜索/正则过滤 + 勾选生成规则（`MethodBrowserDialog`）。
  - **v9.9.0 复制兜底强化（需求2）**：`OutputSettings.copyOutput` 多级兜底——① 删除旧目标后 `File.copyTo`；② MediaStore.Downloads（Q+，IS_PENDING+RELATIVE_PATH）；③ 应用专属 downloads 目录。返回值统一为实际落盘路径，落盘位置决定成功判定，彻底消除旧文件残留假成功（`deliveredPath` 三链路统一接入）。
  - **v9.9.0 伪装加固修复（需求3）**：`SoNameDisguiser.disguise` 先核实 `libs/<abi>/` 下存在与 dex 引用对应的 so 文件，无匹配则不动 dex 并明确报错；多 ABI 改名原子化（任一失败回滚已改名的 so），杜绝"dex 已引用新名却无对应 so"的伪成功。
  - **v9.10.1 方法浏览器重复 key 崩溃修复（需求4）**：`ApkMethodScanner.scan` 对扫描结果按 `MethodEntry.uniqueKey` 去重，杜绝同一类方法（多 dex 重复声明）在 `LazyColumn items(key=...)` 撞 key 导致 `IllegalArgumentException: Key ... was already used`。
  - **v9.10.2 真机两项修复（需求5）**：
    - ① 512MB 堆 OOM 根治：`FrostStringEncryptor`（字符串加密 L1）、`FrostDexUtils.splitDex`（keep-classes）、`SoNameDisguiser`（伪装加固）三处此前均构造 `ImmutableDexFile`+`ImmutableClassDef`，会对整 dex 所有类做 immutable 化与 TreeSet 排序并遍历全部指令，栈顶 `ImmutableClassDef.immutableSetOf` 正是 OOM 现场；全部改为共享委托式 `RewrittenClassDef`/`RewrittenDexFile`（`dex/RewrittenDexFile.kt`，`LinkedHashSet` 保序、交由 DexPool 原样写入），与反射注入同一手法。
    - ② 伪装加固找不到壳库 so 失败：`FrostShellEngine.prepare` 原先仅在 `shell-files` 为空时从 assets 解压，而 `filesDir` 跨会话持久化——上次运行（随机化/伪装/OOM 中断）改写的 dex 引用（libvenSec.so）与 libs/ 内 so 文件失配，导致 `SoNameDisguiser` 匹配不到对应 so 中止；现改为每次加固前强制删除并重装 `shell-files`（assets 基线自洽：dex 引用 `lib8012d9ae47c7f010.so` 与各 ABI 文件一致），随机化/伪装始终基于干净基线。
  - **v9.10.5 字符串加密参数寄存器类型破坏修复（需求7 续）**：
    - 根因：v9.10.4 只修了跳转偏移，但 `FrostStringEncryptor.rewriteMethod` 提升 registerCount（baseRegs→+2）容纳临时寄存器时，未处理 **Dalvik 参数寄存器锚定最高位** 这一事实——registerCount 增加后参数整体上移，方法内指令对参数寄存器（this/显式参数）的原始编号引用不迁移，ART verifier 在入口把最高位寄存器标为参数类型而指令读取原编号（现无定义 local）→ `instance field access on object that has non-reference type Undefined`（onBackPressed 首条 iget 即 [0x0]）。
    - 修复：方法头部插入参数搬移指令（新参数区 src=baseRegs+4+pos 逐槽搬回原参数区 dst=low+pos；this/引用用 MOVE_OBJECT_16、宽用 MOVE_WIDE_16、其余 MOVE_16），既有指令引用保持有效；临时寄存器置参数区之上（baseRegs+2/+3）；target 索引统一 +headShift；上限检查 newRegCount=baseRegs+参数槽数+4≤0xFFFF。
    - 本地回归：javac+d8 样例（onBackPressed 分支形状 / packed-switch / 多参数 long+int+String+Object 与 static int+long+String），dexlib2 双校验（offset 边界 + iget/iput 对象寄存器类型流，等价 ART verifier）三组均为 0 错误，clinit 注入后亦 0。
  - **v9.10.4 dex 改写 pass 跳转目标错位修复（需求7）**：
    - ① 根因：`FrostStringEncryptor.rewriteMethod` 与 `FrostReflectionClinitInjector.injectHelperCall` 重建方法体时把 DexBacked 指令（保留原始 codeOffset）与新指令混拼进 `ImmutableMethodImplementation`——插入新指令后方法指令流右移，但 goto/if/switch 仍引用原始偏移，跳转目标落在指令中间，ART verifier 拒绝加载（真机崩溃 `void onBackPressed(): [0x15] target dex pc 0x28 is not at instruction start`，App 启动即闪退）。
    - ② 修复：两处均改用 `MutableMethodImplementation(MethodImplementation)` 复制方法体——构造函数将全部 offset 指令经 codeAddress→index 映射转为 label 式 builder 指令，插入/替换后 `fixInstructions` 统一重算所有跳转偏移；`FrostStringEncryptor` 将命中 const-string 替换为密文常量并以 (idx+1) 锚点倒序插入 4 条（const/16 + invoke-static/range + move-result-object + move-object/16），因 Mutable 的 registerCount 为 private final，用 `MethodImplementation` facade 覆盖 `getRegisterCount()` 提升 2 个临时寄存器。
    - ③ 本地回归：javac+d8 构造含分支/goto 的 onBackPressed、for 循环 clinit、packed-switch 的样例 dex，java -cp 直调两个 pass 后用 dexlib2 校验所有 offset 指令目标是否落在指令起始（等价 ART verifier 检查）：baseline=0，字符串加密 7 处后=0，clinit 注入后=0。
  - **v9.10.3 manifest 写路径去 meditor 化（需求6）**：
    - ① 根因：meditor(pxb `com.wind.meditor`/`pxb.android.axml`) 写新属性时「属性名→android 资源 ID」映射依赖 classloader 资源 `assets/public.xml`，目标 APK assets 未打包该文件 → `getResourceId()` 恒 -1，新增/改写属性丢 ID 或错位，在系统解析侧表现为 `appComponentFactory` 被写成非法字面量（如 "activity"）、activity 块丢失/桌面无图标。
    - ② 修复：APK 写路径全部弃用 meditor，改用自研 `AndroidManifestModifier`：`modifyManifest` 新增 `appAttrs: List<ApplicationAttrPatch>` 参数，支持写 application 的 `name`/`appComponentFactory`（STRING 0x03）、`debuggable`/`extractNativeLibs`（BOOLEAN 0x12，true→data=-1/0xffffffff）；`FrostApkManifestEditor.writeApplicationName/writeAppComponentFactory/writeDebuggable` 与新增 `writeApplicationExtractNativeLibs` 全部改调 `AndroidManifestModifier`（保留 pxb `AxmlParser` 的只读属性读取）；`FrostApk.setExtractNativeLibs` 同步切换；AAB 路径本就无 meditor（protobuf `FrostAabManifestEditor`/`FrostAndroidResourcesEditor`，资源 ID 已正确），不动。
    - ③ 本地回归：编译 `app/build/tmp/kotlin-classes/debug`+kotlin-stdlib+android.jar+ironshell-deps.jar 后 `java -cp` 直调引擎写方法跑 三步链（name→appComponentFactory→extractNativeLibs），重打包后 `aapt dump xmltree` 校验：新属性均带正确资源 ID（0x01010003/0x0101057a/0x010104ea）、类型正确（BOOLEAN 显示 0xffffffff）、MainActivity+MAIN/LAUNCHER+CoreComponentFactory 保留。
  - **v9.10.0 崩溃修复（需求1-3）**：
    - ① `FrostReflectionClinitInjector` 反射类名注入 OOM 修复：主循环改用 `Array<ClassDef>`，命中反射 clinit 的类经委托式 `RewrittenClassDef` 透传（不再重建 `ImmutableClassDef`，规避 dexlib2 TreeSet 排序+toString 的 512MB 堆打满）；写入用 `RewrittenDexFile`（`LinkedHashSet` 保持顺序，规避 `ImmutableDexFile` 全量排序）；`getMethods()` 合并 direct+virtual 迭代器；新增 `peelDebugInfo` 剥离 debugItems 不触发整体重建。
    - ② Compose 动画/首页 OOM 缓解：随第一组 OOM 链路修复（dex 线程不再打满堆 → GC 压力下降 → UI 动画不再被挤压 OOM）。
    - ③ `MethodBrowserDialog` LazyColumn 重复 key 崩溃修复：`MethodEntry` 增加 `parameterTypes`（解析 `protoIds().parametersOffset` 类型列表）与全局唯一 `uniqueKey`（类名.方法名+参数签名），`items(key={it.uniqueKey})`、选中集与规则生成全部改用 `uniqueKey`，消除方法重载导致的 key 冲突。
    - 首页「函数抽取」上移：新增独立卡片（选 APK 后即见），内置「仅抽取指定函数」开关+已配置规则数摘要+「配置抽取规则」按钮（打开方法浏览器），同时保留设置页 FrostShell 引擎选项内的高级配置入口；版本提升至 v9.10.0。
  - CI：`.github/workflows/build-apk.yml`。
- **进行中**：（无）
- **已搁置**：
  - native 侧 VMP 解释器 / RC4 SO 解密 / ELF section 注入：仅文档对接点（`docs/NATIVE-DOCKING.md`），未实现。
- **最近可运行的 commit**：`473efc6`（v9.10.2，已推送并打 tag）；v9.10.0 `bf6145b`、v9.10.1 `467caea`。（v9.10.3~v9.10.5 提交哈希见 §6）

## 7. 待开发内容

| 功能 | 优先级 | 预估工时 | 前置依赖 |
|------|--------|----------|----------|
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
| Manifest 编辑 | `hardeningtool/AndroidManifestModifier.kt` | 二进制 AXML 编辑 + application 属性写 patch（`appAttrs`）|

### 前后端对接点

- 引擎配置通过 `FrostEngineOptions`（数据类）传入；运行期配置经 `FrostShellConfig` 序列化到壳 APK `assets/irn/.meta`（JSON），字段名见 `FrostShellConfig.toJson()`：`insns_key`、`key_shard4`、`insns_store` 等。

### 已知技术债

- 引擎处理大 APK 时抽取阶段并行线程池占用内存较高。
- `FrostStringEncryptor` 目前只处理 const-string 字面量，未覆盖 `fill-array-data` 等复合数据结构。

## 9. 代码概览与已知问题

```
app/
├── build.gradle.kts               # v9.10.5 / versionCode 47
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
| 加固产物无法解析 | 检查 `--auto-verify` 日志；清单资源 ID 重写（v9.6.6 修复项）+ meditor 缺 `public.xml` 导致属性丢 ID/错位（v9.10.3 已去 meditor，见 §6）+ dex 改写 pass 偏移错位 VerifyError（v9.10.4 已修）+ 提升 registerCount 未重映射参数引用致 Undefined VerifyError（v9.10.5 已修，见 §6） |

## 14. 验收标准

1. **构建**：按第 3 节命令，从干净环境成功产出 `app-debug.apk`（`aapt dump badging` 显示 `package name='Forinxy.safe' versionName='9.10.5' versionCode='47'`）。
2. **运行**：安装并启动到首页，能选择 APK 并完成一次加固，产物可安装运行。
3. **CI**：push 到 `main` 后 `.github/workflows/build-apk.yml` 自动构建出 debug APK 并上传 artifact。
4. 三样交接产物齐备：`HANDOVER.md`、`AndroidHardeningTool源码.zip`、`AndroidHardeningTool_v9.10.5_debug.apk`。
