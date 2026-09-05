# User Instruction Memory

This file records user instructions, preferences, and teachings for reference in future interactions.

## Format

### User Instruction Entry
User instruction entries should follow this format:

[User Instruction Summary]
- Date: [YYYY-MM-DD]
- Context: [Mentioned scenario or time]
- Instructions:
  - [Content of user teaching or instruction, described line by line]

### Project Knowledge Entry
Entries discovered by the Agent during task execution should follow this format:

[Project Knowledge Summary]
- Date: [YYYY-MM-DD]
- Context: Discovered by Agent while performing [specific task description]
- Category: [Operations & Deployment|Build Methods|Testing Methods|Troubleshooting & Debugging|Workflow & Collaboration|Environment Configuration]
- Instructions:
  - [Specific knowledge points, described line by line]

## Deduplication Strategy
- Before adding a new entry, check for similar or identical instructions.
- If a duplicate is found, skip the new entry or merge it with the existing one.
- When merging, update the context or date information.
- This helps avoid redundant entries and keeps the memory file tidy.

## Entries

[Project Knowledge Summary]
- Date: 2026-08-30
- Context: Discovered by Agent while performing FrostShell 与 ADFXCBNM 二合一加固合并任务的验证
- Category: Environment Configuration
- Instructions:
  - 本环境（Debian 12 bookworm）默认无 Java，apt 源为清华镜像（mirrors.tuna.tsinghua.edu.cn），用 `apt-get install -y openjdk-17-jdk-headless` 安装 JDK 17 比 Temurin 直链下载快且可靠（Temurin github 下载常被截断）。
  - FrostShell `protect.py --auto-setup` 内置的 Temurin JDK 21 下载在此网络环境易中断，改走 apt 更稳。

[Project Knowledge Summary]
- Date: 2026-08-30
- Context: Discovered by Agent while performing FrostShell 二合一加固 merge_protection.py 验证
- Category: Troubleshooting & Debugging
- Instructions:
  - FrostShell 引擎（ironshell.jar）在 zipalign 后内部 apksig 签名可能失败，导致 `*_signed.apk` 为 0 字节，但 `*_unsign.apk` 有效；合并流程已回退使用 unsign 产物（merge 阶段会重新签名）。
  - apksig 验证报 "JAR signature ... not supported on API Level(s) 9-17" 是无 minSdk 的测试 APK 的误报，需加 `--min-sdk-version 21` 再验证。

[Project Knowledge Summary]
- Date: 2026-08-30
- Context: Discovered by Agent while implementing merge_protection.py 的 ZipBuilder 手动重打包
- Category: Build Methods
- Instructions:
  - 手写 ZIP 时，DEFLATED 条目必须用裸 deflate 流（`zlib.compressobj(6, zlib.DEFLATED, -15)`），不能用 `zlib.compress()`（带 zlib 头 0x789c 会导致 apksig/zipfile 解压失败）。
  - ZipBuilder 写中央目录时必须递增 offset，否则 EOCD 的 cd_size 为 0 导致中央目录损坏。

[Project Knowledge Summary]
- Date: 2026-09-05
- Context: Discovered by Agent while performing v9.10.0 崩溃修复的 assembleDebug 编译验证
- Category: Build Methods
- Instructions:
  - 自定义 `ClassDef`/`DexFile` 实现（如 FrostReflectionClinitInjector 内的委托式 RewrittenClassDef/RewrittenDexFile）必须遵循 jar 内 dexlib2 的接口约束：`ClassDef` 经 `TypeReference` 间接实现 `CharSequence`，Kotlin 编译器会要求实现 `get(index: Int): Char`，因此自定义类应继承 `com.android.tools.smali.dexlib2.base.reference.BaseTypeReference` 而非裸实现 `ClassDef` 接口（jar 自带 RewrittenClassDef 即此模式）。
  - jar 内 dexlib2 的 `getOpcodes()` 等在 Kotlin 侧为动态解析属性，`override val opcodes` 无法匹配，需用显式 `override fun getOpcodes()`。
  - `Iterable.debugItems` 无 `isNotEmpty()`，用 `.any()` 判空。
  - 编译命令：`cd /tmp/opencode/merge/android/AndroidHardeningTool && ./gradlew :app:assembleDebug --no-daemon --offline`；内存上限 3.5GiB(45%)，CPU 300%，约 47s-1m49s；产物校验用 `aapt dump badging app-debug.apk` 看 versionName/versionCode。
  - Jar 编译类路径（compileDebugKotlin 的 KCP）会引用 `app/libs/ironshell-deps.jar` 的 AGP transformed 快照（`ironshell-deps_jar-snapshot.bin`），其内容与源 jar 直接 javap 观察存在差异，排查接口签名异常时以「编译报错提示的成员」为准。
