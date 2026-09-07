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

[Project Knowledge Summary]
- Date: 2026-09-05
- Context: Discovered by Agent while diagnosing 加固后 APK AndroidManifest 被破坏（appComponentFactory 写成 "activity"、activity 块丢失）
- Category: Troubleshooting & Debugging
- Instructions:
  - 根因：meditor(pxb `com.wind.meditor` / `pxb.android.axml`) 写新属性时属性名→android 资源 ID 映射依赖 classloader 资源 `assets/public.xml`（`ResourceIdXmlReader.findIdFromXmlFile`）；目标 APK 的 assets 未打包该文件 → `getResourceId()` 恒 -1，新增/改写属性丢 ID 或错位，在系统解析侧表现为属性值错乱、activity 丢失。校验方法：`aapt dump xmltree xxx.apk AndroidManifest.xml` 看新属性是否带 `(0x01010003)` 之类资源 ID。
  - 决定：引擎 APK 写路径全部弃用 meditor，改用自研 `AndroidManifestModifier`（`modifyManifest(private appAttrs: List<ApplicationAttrPatch>)` 支持写 application 的 name/appComponentFactory（STRING 0x03）、debuggable/extractNativeLibs（BOOLEAN 0x12，data=true 用 -1/0xffffffff））。关键资源 ID：0x01010003=android:name、0x0101057a=appComponentFactory、0x010104ea=extractNativeLibs、0x0101000f=debuggable。
  - 本地回归方式：编译 `app/build/tmp/kotlin-classes/debug` + kotlin-stdlib + android.jar + ironshell-deps.jar 后，直接 `java -cp` 调 `FrostApkManifestEditor.writeApplicationName/writeAppComponentFactory/writeApplicationExtractNativeLibs` 对真实 manifest 跑三步链，再用 `ChainToApk` 重打包 → `aapt dump xmltree` 校验。AAB 路径无 meditor（protobuf `FrostAabManifestEditor`/`FrostAndroidResourcesEditor`，资源 ID 已正确），无需改动。
  - 复合场景下 meditor 产物在设备端（OPPO PLG110/Android 16/512MB 堆）表现出 appComponentFactory="activity" 等字符串错位；本地用完整 public.xml 时症状消失，佐证 public.xml 依赖是根因。

[Project Knowledge Summary]
- Date: 2026-09-05
- Context: Discovered by Agent while diagnosing 加固产物启动闪退 VerifyError（onBackPressed target dex pc not at instruction start）
- Category: Troubleshooting & Debugging
- Instructions:
  - 根因：dex 改写 pass 用 `ImmutableMethodImplementation` 重建方法体时把 DexBacked 指令（保留原始 codeOffset）与新指令混拼，插入指令后方法指令流右移，但 goto/if/switch 仍引用原始偏移 → 跳转目标落在指令中间，ART verifier 拒绝加载（`VerifyError: void ...onBackPressed(): [0x15] target dex pc 0x28 is not at instruction start`）。dexlib2 的 `getCodeOffset()` 对 goto/if 是相对当前指令的偏移（target=指令起始+offset），对 switch/payload 是方法内绝对偏移。
  - 修复范式：凡需在既有方法体内插入/替换指令，必须用 `MutableMethodImplementation(MethodImplementation)` 复制方法体（构造函数会把全部 offset 指令经 codeAddress→index 映射转 label 式 builder 指令，插入/替换后 `fixInstructions` 自动重算所有偏移），不可直接拼接 backed 指令；该构造函数还会保留 tryBlocks/debugItems（用 `mapCodeAddressToIndex` 重新映射）。`FrostStringEncryptor.rewriteMethod` 与 `FrostReflectionClinitInjector.injectHelperCall` 均已按此修复（v9.10.4）。
  - 注意：`MutableMethodImplementation` 的 `registerCount` 是 `private final` 无 setter，需增寄存器时用匿名 `MethodImplementation` facade 覆盖 `getRegisterCount()` 透传 mutable 的 instructions/tryBlocks/debugItems（不可把 builder 指令抽出另包 Immutable，会丢 label 上下文）。
  - 本地 dex 级回归验证：javac+d8 构造含分支/goto/packed-switch 的样例 dex，java -cp 直调 pass 后遍历所有 `OffsetInstruction`，检查 `target = 指令起始+getCodeOffset()`（goto/if）或 `getCodeOffset()`（switch）是否落在指令起始边界集合，等价 ART verifier 检查；运行时需 `android.util.Log` stub（编译后的 app classes 会引用 android.util.Log，driver classpath 前置 stubout）。

[Project Knowledge Summary]
- Date: 2026-09-06
- Context: Discovered by Agent while diagnosing v9.10.4 后加固产物仍闪退（onBackPressed instance field access on non-reference type Undefined）
- Category: Troubleshooting & Debugging
- Instructions:
  - Dalvik 调用约定：参数寄存器锚定在寄存器区最高位（指向受 instruction 索引，offset 不迁移）。因此「提升方法 registerCount 以容纳临时寄存器」时，参数寄存器整体上移，但方法内指令（iget/iput/invoke 等）对参数寄存器的原始编号引用不会跟随迁移 —— ART verifier 在入口把最高位寄存器标为参数类型，指令却读取原编号位置（现为无定义 local），报 `instance field access on object that has non-reference type Undefined`（onBackPressed 首条 iget 即 [0x0]）。
  - 修复范式：需要在既有方法内新增临时寄存器时，除用 `MutableMethodImplementation(MethodImplementation)` 复制（自动 label 化+fixInstructions 重算偏移，解决跳转目标错位）外，还必须在方法头部插入「参数搬移」指令——从提升后的新参数区（regCount-totalSlots 起）逐槽位搬回原参数区（baseRegs-totalSlots 起），this/引用用 MOVE_OBJECT_16、宽类型 MOVE_WIDE_16、其余 MOVE_16（32x 格式 BuilderInstruction32x(dst,src)）；临时寄存器放在参数区之上的新增空间（不与既有引用冲突）；头部新增指令数会让后续 target 索引整体偏移需 +headShift。上限检查 newRegCount = baseRegs + 参数槽数 + 额外寄存器数 ≤ 0xFFFF。
  - dex 级双校验（等价 ART verifier）：① offset 指令目标（goto/if 相对=指令起始+offset，switch/payload 绝对=offset）落在指令起始边界；② 寄存器类型流——entry 参数区各槽位按参数类型标记（this/对象=引用），逐指令传播 MOVE/CONST String/invoke 结果/move-result，检查 iget/iput 对象寄存器与 AGET 数组寄存器必须为引用类型。用 javac+d8 构造 onBackPressed 分支形状、packed-switch、多参数（long+int+String+Object）样例验证。
  - gh CLI 凭据再次失效时（HTTP 401），用 `echo -e "protocol=https\nhost=github.com\n" | git credential fill` 取 token 后 `gh auth login --with-token` 恢复（本环境 helper=/app/agent/bin/agent git-credential-helper，username=Forinxy）。
