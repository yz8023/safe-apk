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

[Project Knowledge Summary]
- Date: 2026-09-05
- Context: Discovered by Agent while performing v9.10.2 真机 OOM 与伪装失败的修复
- Category: Troubleshooting & Debugging
- Instructions:
  - 委托式 `RewrittenClassDef`/`RewrittenDexFile` 已抽取为共享顶层类（`app/src/main/java/com/adfxcbnm/frostshell/dex/RewrittenDexFile.kt`），后续任何 dex 重建 pass（字符串加密/keep-classes split/伪装 so 改名/反射注入）必须复用它们，不得再直接 `ImmutableClassDef`+`ImmutableDexFile`（内部会对整 dex 全量 immutable 化 + TreeSet 排序 + 遍历全部指令，真机 512MB 堆 OOM）。
  - 真机加固工具（MainActivity）的 `FrostShellEngine.prepare` 会在每次加固前从 assets 强制重装 `shell-files`（filesDir 跨会话持久化，上次随机化/伪装/OOM 中断可能改写 dex 引用与 so 名导致失配），排查「伪装加固找不到壳库 so」时先确认 assets 基线（`frostshell/dex/classes.dex` 引用 `lib8012d9ae47c7f010.so`，libs 各 ABI 同名一致）。
  - 发布流程：`git push` 分支 → `git tag vX.Y.Z` + push tag → `gh release create vX.Y.Z`（本环境 gh CLI 走 `[bot]` token）→ `gh release upload` 附件 → `gh release edit` 用 `--notes-file` 写 body；注意 zsh 反引号会触发命令替换，release notes 含 backtick 或特殊符号时必须用 `--notes-file` 而非内联参数。
