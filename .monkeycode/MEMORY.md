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
  - 本环境（Debian 12 bookworm）默认无 Java，apt 源为清华镜像（mirrors.tuna.tsinghua.edu.cn），用 `apt-get install -y openjdk-17-jdk-headless` 安装 JDK 17 比 Temurin 直链下载快且可靠（Temurin github 下载常被截断；FrostShell `protect.py --auto-setup` 内置的 Temurin JDK 21 下载同样易中断）。
  - FrostShell 引擎（ironshell.jar）在 zipalign 后内部 apksig 签名可能失败，导致 `*_signed.apk` 为 0 字节，但 `*_unsign.apk` 有效；合并流程已回退使用 unsign 产物（merge 阶段会重新签名）。
  - apksig 验证报 "JAR signature ... not supported on API Level(s) 9-17" 是无 minSdk 的测试 APK 的误报，需加 `--min-sdk-version 21` 再验证。

[Project Knowledge Summary]
- Date: 2026-08-30 / 2026-09-05
- Context: Discovered by Agent while implementing merge_protection.py ZipBuilder 手动重打包与 v9.10.0 assembleDebug 编译验证
- Category: Build Methods
- Instructions:
  - 手写 ZIP 时，DEFLATED 条目必须用裸 deflate 流（`zlib.compressobj(6, zlib.DEFLATED, -15)`），不能用 `zlib.compress()`（带 zlib 头 0x789c 会导致 apksig/zipfile 解压失败）。ZipBuilder 写中央目录时必须递增 offset，否则 EOCD 的 cd_size 为 0 导致中央目录损坏。
  - 自定义 `ClassDef`/`DexFile` 实现必须遵循 jar 内 dexlib2 接口约束：`ClassDef` 经 `TypeReference` 间接实现 `CharSequence`，Kotlin 编译器会要求实现 `get(index: Int): Char`，因此应继承 `com.android.tools.smali.dexlib2.base.reference.BaseTypeReference` 而非裸实现接口；`getOpcodes()` 等在 Kotlin 侧为动态解析属性，需用显式 `override fun getOpcodes()`。`Iterable.debugItems` 无 `isNotEmpty()`，用 `.any()` 判空。
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
- Date: 2026-09-05 / 2026-09-06
- Context: Discovered by Agent while diagnosing 加固产物启动闪退（VerifyError onBackPressed + Undefined 两类，v9.10.4/v9.10.15）
- Category: Troubleshooting & Debugging
- Instructions:
  - 根因①：dex 改写 pass 用 `ImmutableMethodImplementation` 重建方法体时把 DexBacked 指令（保留原始 codeOffset）与新指令混拼，插入指令后指令流右移但 goto/if/switch 仍引用原始偏移 → 跳转目标落在指令中间，ART 报 `VerifyError: target dex pc 0x28 is not at instruction start`。dexlib2 的 `getCodeOffset()` 对 goto/if 是相对当前指令的偏移（target=指令起始+offset），对 switch/payload 是方法内绝对偏移。
  - 根因②：参数寄存器锚定在寄存器区最高位（不受 instruction 索引影响）。「提升 registerCount 以容纳临时寄存器」时参数寄存器整体上移，但方法内指令（iget/iput/invoke）对参数寄存器的原始编号引用不跟随迁移 → ART 报 `instance field access on object that has non-reference type Undefined`。
  - 修复范式：凡需在既有方法体内插入/替换指令，必须用 `MutableMethodImplementation(MethodImplementation)` 复制方法体（构造时把全部 offset 指令经 codeAddress→index 映射转 label 式 builder 指令，插入/替换后 `fixInstructions` 自动重算偏移；保留 tryBlocks/debugItems）。`registerCount` 是 private final 无 setter，需增寄存器时用匿名 `MethodImplementation` facade 覆盖 `getRegisterCount()` 透传 mutable 的 instructions/tryBlocks/debugItems。
  - 增寄存器的参数搬移：从提升后的新参数区（regCount-totalSlots 起）逐槽位移回原参数区（baseRegs-totalSlots 起），this/引用用 MOVE_OBJECT_16、宽类型 MOVE_WIDE_16、其余 MOVE_16（BuilderInstruction32x(dst,src)）；临时寄存器放参数区之上，头部新增指令数需 +headShift；newRegCount ≤ 0xFFFF。
  - **参数搬移槽位顺序陷阱（v9.10.15 根因）**：搬移必须按 Dalvik 参数真实位序——实例方法 this 最低位、显式参数按声明顺序。`slotTypes` 应直接按位序构造（`if(!isStatic) add("Lthis;")` 再按参数顺序 add），正向迭代累加 pos（pos+=槽宽）算 dst=low+pos/src=baseRegs+4+pos，再倒序插入头部。用 `reversed()` 反序会让多参数显式参数错位（如 `bar(String s, long l)` 把 s 当 long 读），仅无参/单参方法恰巧正确故早期未暴露。
  - dex 级双校验（等价 ART verifier）：① offset 指令目标（goto/if 相对=指令起始+offset，switch/payload 绝对=offset）落在指令起始边界；② 寄存器类型流——entry 参数区各槽位按参数类型标记，逐指令传播 MOVE/CONST String/invoke 结果/move-result，检查 iget/iput 对象寄存器与 AGET 数组寄存器必须为引用类型。用 javac+d8 构造无参/2参/3参/宽首参/5参及分支/switch 样例验证；运行时需 `android.util.Log` stub（driver classpath 前置 stubout）。
  - gh CLI 凭据再次失效时（HTTP 401），用 `echo -e "protocol=https\nhost=github.com\n" | git credential fill` 取 token 后 `gh auth login --with-token` 恢复（本环境 helper=/app/agent/bin/agent git-credential-helper，username=Forinxy）。

[Project Knowledge Summary]
- Date: 2026-09-06
- Context: Discovered by Agent while diagnosing palmPC 全选加固后启动闪退（tombstone 仅 pc=0 lr=0 fault addr 0x0 单帧）
- Category: Troubleshooting & Debugging
- Instructions:
  - 加固产物 native 空指针崩溃（tombstone `signal 11 SIGSEGV fault addr 0x0 / pc 0 lr 0 / #00 pc 0 <unknown>`）发生在保护 native 层，Java `Thread.setDefaultUncaughtExceptionHandler` 抓不到。诊断手段：注入崩溃日志采集——`libsecurity_check.so` 增加 `nativeInstallCrashHandler(String path)`（注册 SIGSEGV/SIGABRT/SIGBUS/SIGILL/SIGFPE/SIGTRAP 的 SA_SIGINFO handler），handler 内用 async-signal-safe（open/write/close，绝不可用 std::ofstream/malloc）记录时间戳/pid/tid/signal/pc/lr/sp/fault（ucontext：arm64 用 uc_mcontext.pc/regs[30]/sp，arm32 用 arm_pc/arm_lr/arm_sp）+ `_Unwind_Backtrace`（<unwind.h>，最多 32 帧）至日志文件并输出 logcat（tag ADFXCBNM_CRASH），记录后 `signal(sig,SIG_DFL); raise(sig)` 重放信号保留系统 tombstone。
  - Java 层配合：`onCreate` 在 `System.loadLibrary` 成功后立即调用 install；日志优先写 `getExternalFilesDir`（`/sdcard/Android/data/<pkg>/files/`，免权限可提取）再写内部 filesDir 副本，`writeCrashLog` 带 pkg/pid/tid/nativeOk/features 上下文，检测循环加 checks:start/checks:done 阶段 marker 定位崩溃阶段。日志提取：文件管理器读外部目录或 logcat 过滤 ADFXCBNM_CRASH。
  - 重新编译注入资源：4 ABI so 用 NDK clang++ 直编（`aarch64-linux-android26-clang++ -std=c++17 -O2 -fPIC -shared protection.cpp -o <out> -llog -landroid`）；dex 需先 javac（`-source 1.8 -target 1.8 -bootclasspath android.jar`）再 d8（`d8 --release --lib android.jar --output <dir> classes`）；注入回归用 zipfile 注入 classes2.dex+so+features.cfg 后 apksigner 签名，校验资源与资产 SHA-256 一致。

[Project Knowledge Summary]
- Date: 2026-09-06
- Context: Discovered by Agent while diagnosing palmPC 二次加固产物闪退（用户上传两个 APK 分析）
- Category: Troubleshooting & Debugging
- Instructions:
  - 二次加固（在已带 ADFXCBNM 保护的 APK 上再次叠加注入）会触发两个工具缺陷：① libsecurity_check.so 已存在时被跳过注入（旧代码 `if (!existingEntries.contains(targetName))`），导致新版 classes2.dex（含 nativeInstallCrashHandler 声明）配旧版 so（无该导出）→ crash handler 静默失效；② AndroidManifestModifier 注入 SecurityCheckProvider 前不查重，二次叠加产生两个相同 authority 的 provider。
  - 修复范式：SO 注入前把 `soTargets` 全部加入 `entriesToAdd`（写入循环会跳过它们），随后无条件 putNextEntry 覆盖写入最新版 so（记录 replacedAbis 而非仅 injectedAbis）——注意 entriesToAdd 必须在写入循环前构造，否则旧 so 先被原样复制再加新 so 造成 zip 重复条目。
  - manifest 去重范式：解析 AXML 后先查 `elements.any { name=="provider" && isStart && attrs 含 name 属性值为 com.adfxcbnm.protect.SecurityCheckProvider }`，已存在则找出所有重复 provider 的 start+对应 end 索引对（providerStartIdxs + 深度匹配）一并移除只留第一个；验证用 aapt dump xmltree 数 provider 出现次数。
  - 诊断用户上传 APK 是否被本工具处理过：查 `assets/features.cfg`（含 `# ADFXCBNM Feature Configuration` 头与 version=）、`assets/protection_config.dat`（magic `ADFXCBNM_CFG_V9x`）、`assets/protection_manifest.json`（apk_size/apk_hash）、签名者 DN 是否 `CN=ADFXCBNM Debug`；features.cfg 内 `signature_sha256` 必然等于该 APK 实际签名 digest（apksigner verify --print-certs）。两个 APK 签名 digest 不同会导致升级安装 INSTALL_FAILED_UPDATE_INCOMPATIBLE。
  - 验证工具修改的离线方法：编译产物在 `app/build/tmp/kotlin-classes/debug/`，用 javac 写调用 `AndroidManifestModifier.INSTANCE.modifyManifest(byte[],Context,List<String>,String,Function1,List<ApplicationAttrPatch>)` 的 driver（classpath 加 kotlin-stdlib 与 android.jar），对真实提取的 AndroidManifest.xml 二进制跑改前改后对比 + aapt dump xmltree 校验。

[Project Knowledge Summary]
- Date: 2026-09-07
- Context: Discovered by Agent while diagnosing and fixing FrostShell 壳与 ADFXCBNM 叠加层的共存冲突（勾选 FrostShell 必闪退且无注入崩溃日志）
- Category: Troubleshooting & Debugging
- Instructions:
  - 壳共存冲突根因：FrostShell 壳 native 库 libvenSec.so 内置 ByteHook，运行时对 open/read/write/mmap/mprotect/dlopen/dlsym/kill 做 GOT/PLT hook；叠加层 SecurityCheckProvider 的 anti_hook/anti_inject/runtime_protect/code_inject 检测到 libc 函数头被改写/进程被 hook，命中 CRITICAL → enforce→triggerKill→nativeKill→SIGKILL。SIGKILL 无法被 crash handler 捕获（只注册了 SIGSEGV/ABRT/BUS/ILL/FPE/TRAP），故"闪退无日志、无 tombstone"基本指向此路径（区别于 SIGSEGV 写坏 dex）。
  - 壳共存修复范式（区分"壳自身防护"与"攻击者 hook"，不简单关闭检测）：① native isFunctionHooked 解码 ARM64 B/BL imm26 得跳转目标（target=pc+imm<<2，符号扩展），用 /proc/self/maps 判断目标所在库——落在 libc/libart/libvenSec/libsecurity_check 等可信库内放行，落在 frida/xposed/sandhook 等攻击库或匿名映射则判定攻击；② Java 层 SecurityCheckProvider 加 isShellCoexist()（读 maps 找 libvenSec/libbytehook），enforce 与 monitor 对 hook/inject/code_inject/runtime_protect 的 CRITICAL kill 豁免，但 frida/xposed/root/magisk/debugger 独立检测保留。
  - 保护模块字符串全在 S_obfuscated.java 混淆（XOR K {0x12..0xF0}），新增字符串必须同样转成字节数组（python XOR 生成），Java 源码内禁止裸字符串。
  - CMake POST_BUILD 只在 Gradle 编译对应 ABI 时更新 assets/lib so；assembleDebug 增量构建可能用缓存 APK 不含新 assets。手动替换 assets so 后必须强制重新构建（--offline assembleDebug），并校验 APK 内资源 sha256 与源码 assets 一致。native 修改同步两份 protection.cpp：FrostShell-CLI/engine/protect-module/src/ 与 app/src/main/cpp/。

[Project Knowledge Summary]
- Date: 2026-09-06
- Context: Discovered by Agent while diagnosing FrostShell 字符串加密写坏主 dex 导致 SIGSEGV 闪退
- Category: Troubleshooting & Debugging
- Instructions:
  - FrostShell L1 字符串加密对 method_ids 逼近 0xFFFF 的大 dex 重写时，DexPool 重建方法池索引重排溢出，writeDexFile 抛 `Exception occurred while writing code_item ... Unsigned short value out of range`（如 65698=0x100A2）。错误方法往往是未加密的 `<init>`（引用池末尾方法），与是否有敏感字符串无关。加 100 个 helper 必触发、加 1 个不触发；classes3~9 成功仅主 classes.dex 失败，因主 dex method_ids 最接近上限（本例 65441）。
  - DexFileFactory.writeDexFile 非原子写：失败后原 dex 被截断写坏（44MB→18MB），损坏 dex 被打包进产物 → ART 执行损坏 code_item 空指针 SIGSEGV（pc=0）。这是"加固后启动闪退"的新一类根因（区别于壳冲突 SIGKILL）。
  - 修复范式：writeDexFile 前先备份原文件（readBytes/writeBytes 而非 Kotlin copyTo，避免 JVM 上 android Log stub 干扰），异常时恢复备份并重新抛出，上层已捕获（打印 WARNING 继续后续 dex）。恢复/清理/日志全部 try 保护，日志失败不阻断异常传递。验证：恢复后 md5 与备份一致、备份删除干净。
  - 大 dex 用 RewrittenClassDef/RewrittenDexFile 委托式透传（避免 ImmutableDexFile 对全类 TreeSet 排序 OOM）本身正确，DexPool 直接重写原始 backed 类也能成功（对照实验 ReproWrite 43MB 通过）；溢出诱因是新增 helper 后 method 池索引重排越过 0xFFFF。

[Project Knowledge Summary]
- Date: 2026-09-10
- Context: Discovered by Agent while fixing v9.10.31 两个运行期 SIGSEGV 闪退（仅字符串加密；算术混淆开启）
- Category: Troubleshooting & Debugging
- Instructions:
  - class_data_item 分区规范：static/private/构造函数必须进 direct_methods，其余实例方法（含接口 abstract/default）进 virtual_methods；DexPool 写回严格按 `getDirectMethods()/getVirtualMethods()` 分区输出，绝不按 access_flags 自动纠正。用 `ImmutableClassDef` 构造注入 helper 类时，把 static 方法传进 virtualMethods 参数位会让 ART 按 invoke-static 查 direct 方法表失败 → 跳空地址 SIGSEGV（pc=0/lr=0 单帧）。修复=把 helper 移回 directMethods 参数位；校验=写回后按类型/方法名定位 helper，确认 directMethods=1、virtualMethods=0。
  - 算术混淆同根因：寄存器帧顶部新增 vT 并把 registers_size+1 后，Dalvik 按 `ins_start=registers_size-ins_size` 重新锚定参数，原 body 对参数寄存器的绝对索引全部错位。修复范式=头部先插参数搬移再写 vT：`dst=origRegCount-paramSlots+slot`、`src=origRegCount+delta-paramSlots+slot`，逐槽升序（src 严格大于已写入 dst 故安全），this 计 1 槽、J/D 宽参数 2 槽用 MOVE_WIDE_16，寄存器>255 用 MOVE_16/MOVE_WIDE_16（32x），newRegCount ≤ 0xFFFF。ADD_INT 替换与分支头两条路径都要处理；无参数方法（如 <clinit>）不需要搬移。
  - 离线回归基建：`app/build/tmp/kotlin-classes/debug` + `app/libs/ironshell-deps.jar`（含 dexlib2）+ kotlin-stdlib + 前置 `android/util/Log` stub，javac 写 driver 直接调 FrostDexObfuscator/FrostStringEncryptor 各 pass 对真实 dex（/tmp/opencode/classes3.dex 338类）跑，写回后 loadDexFile 校验可解析，再用 TwoRegisterInstruction.getRegisterA/B 核对搬移寄存器号。

[Project Knowledge Summary]
- Date: 2026-09-06
- Context: Discovered by Agent while fixing FrostShell 大小显示 +0 bug and adding 打开APK feature
- Category: Troubleshooting & Debugging / Operations & Deployment
- Instructions:
  - FrostShell 引擎路径（processFrostShellApk）的 sizeDiff 曾因 `val realSize = sourceInputSize` 误赋源大小而恒为 +0；正确做法是读实际输出文件大小：文件路径用 File.length()，content:// 输出用 ContentResolver 查询 OpenableColumns.SIZE（FileProvider 的 file_paths.xml 已配 root-path 覆盖全部路径，getUriForFile 可直接转任意路径）。
  - 结果对话框成功态含"复制目录/打开APK/关闭"三按钮；打开 APK 用 ACTION_VIEW + application/vnd.android.package-archive + FLAG_GRANT_READ_URI_PERMISSION，manifest 已有 REQUEST_INSTALL_PACKAGES。
  - 发布 release 时 gh CLI 凭据失效（Bad credentials），改用 `git credential fill`（helper 为 /app/agent/bin/agent git-credential-helper，账号 Forinxy）获取 password 作 Authorization: token 调 api.github.com 建 release、uploads.github.com 传 asset。

[Project Knowledge Summary]
- Date: 2026-09-06
- Context: Discovered by Agent while implementing 签名工具(查看/生成keystore)
- Category: Environment Configuration / Troubleshooting & Debugging
- Instructions:
  - 自签名 X.509 证书有效期超过 2049 年时不能再用 UTCTime(tag 0x17, 2位年)，必须按年份切换到 GeneralizedTime(tag 0x18, yyyyMMddHHmmssZ)，否则 X509 解析会把 2126 年错读成 2026 年。
  - Java 调用 Kotlin object 需用 `Object.INSTANCE.method()`，onError 回调要 `Function1<String, Unit>`(返回 Unit.INSTANCE)；Compose 对话框内后台任务用 `scope.launch + withContext(Dispatchers.IO)`，不能直接用 Activity.runOnUiThread。
  - keystore 类型自动检测：扩展名 jks/keystore/ks→JKS、bks→BKS、否则 PKCS12；用 linkedSetOf(extType, PKCS12, JKS, BKS) 逐个尝试，先按别名取 keyStore.getCertificate(alias)，无别名时取第一个 isKeyEntry 的 key 别名。指纹用 cert.encoded 做 SHA-1/SHA-256/MD5。

[Project Knowledge Summary]
- Date: 2026-09-09
- Context: Discovered by Agent while adding BKS keystore support and 多签名保存 to AndroidHardeningTool
- Category: Environment Configuration / Troubleshooting & Debugging
- Instructions:
  - BKS 依赖 BouncyCastle：bcprov-jdk15on-1.67.jar 已放 app/libs 并在 build.gradle.kts 用 implementation(files(...)) 引入；SigningTool 静态块 Security.addProvider(BouncyCastleProvider())。JKS 在 Android/JVM 的 JSSE 无 provider，只能走自实现 JksParser；PKCS12/BKS 走 KeyStore API。
  - generateKeystore 用 spec.keyPass 加密 key entry，而 loadSignatureKey 默认把 storePass 兼作 keyPass；密码分离（storePass≠keyPass）时读取/转换必须显式传 keyPass/srcKeyPass，否则报"无法读取密钥"。转换通用入口 convertKeystoreFormat(file, storePass, aliasHint, outFile, targetType, newStorePass, newKeyPass, srcKeyPass=null, onError)，onError 是最后一个参数；convertToP12 委托它。
  - 探针验证 classpath：kotlin-classes-debug + ironshell-deps.jar + bcprov-jdk15on-1.67.jar + kotlin-stdlib；JVM 直驱需 /tmp/mockstub 提供 android.* 与 org.json Stub（SharedPreferences/Context 环境没有真实实现，UI 层 JSON 序列化用 SignProfileProbe 验证）。

[Project Knowledge Summary]
- Date: 2026-09-10
- Context: Discovered by Agent while fixing v9.10.32 Flutter 应用（cn.ikaile.ruoshui.client）全功能加固启动即崩
- Category: Troubleshooting & Debugging
- Instructions:
  - Flutter 加固必须无条件保护 `io.flutter.*`：libflutter.so 引擎 native 与 libapp.so Dart AOT 按**类名字符串** FindClass/lookupClass 定位引擎类与插件注册类（GeneratedPluginRegistrant 等），DEX 类重命名/字段改名/方法抽取都会让 FindClass 返回 null 或导致 JNI 状态异常 → 启动即崩（本例为 libdartjni.so FindClassUnchecked x0=0 + "JNI is not initialized...during Dart plugin class registration"）。类重命名 PROTECTED_PREFIXES 加 `Lio/flutter/` 是硬性防线；FrostProtectRules.excludeRules 加 `Lio/flutter/.*` 覆盖字段重命名/方法抽取/L2 混淆各 pass；setExcludeRules 被用户规则文件整体覆盖时必须强制合并该条，否则配置能绕过保护。
  - Flutter 场景下字符串加密/算术混淆等字节码等价变换是安全的，唯一高危点是任何**按名解析**的 native/Dart 桥：除 io.flutter.* 外，package:jni 等 FFI 插件的 Java 类若被重命名，其 Dart 端 JNI.lookupClass 也会失败——需要用户在规则文件里给具体插件包前缀补 keep。
  - 回归法：javac --release 8 + com.android.dx.command.Main（ironshell-deps.jar 内含）把含 io.flutter.* 与应用类的源码转成 classes.dex（文件名必须匹配 classes(\d*)\.dex，否则 getDexNumber=-1 被过滤），再驱动 buildClassRenameMap 断言 io.flutter 不进 map、应用类进 map、处理后 FlutterJNI 类名原样保留且 dex 可加载。

[Project Knowledge Summary]
- Date: 2026-09-10
- Context: Discovered by Agent while fixing v9.10.33 算术混淆产物 ART VerifyError（cc.sylu.palmpc 仅开基础28+字符串加密+算法混淆启动即崩）
- Category: Troubleshooting & Debugging
- Instructions:
  - ART 指令格式语义（method_verifier.cc 源码级确认）：move/move-from16/move-16 一律走 `VerifyCopyCat1`——只接受 Conflict 或 Category1（int/float/缓存引用）；**精确引用类型（PreciseReference/ExactReference，如 this 的具体类）的 RegTypeId 超出 RegTypeCache::NumberOfRegKindCacheIds() → 直接 FailForCopyCat1 报 `copy-cat1 vX<-vY type=Reference: <类名>`**。引用搬移必须走 VerifyCopyReference 的 move-object 系列（MOVE_OBJECT/MOVE_OBJECT_FROM16/MOVE_OBJECT_16）；J/D 用 move-wide 系列。
  - 触发形态：kotlinx.coroutines.internal.Symbol.toString() 等极简方法（.registers 3、ins_size=1、body 第一条 `iget-object v2,v2` 复用 this 槽）在算术混淆（参数搬移 buildParamMoves）后产出 `MOVE_16 v2,v3` 搬运 this 引用 → 类验证即崩，早于任何方法调用，故崩在 androidx.startup 初始化链（ProcessLifecycleInitializer→ProcessLifecycleOwner→StateFlow→Symbol）。AndroidX 几乎必带 kotlinx.coroutines，属高概率复现面。
  - 修复范式（算术混淆 buildParamMoves 与字符串加密器一致）：参数搬移按类型选 opcode——this/对象（L 前缀）数组（[ 前缀）用 `MOVE_OBJECT_16`，J/D 用 `MOVE_WIDE_16`，I/F/S/B/C/Z 用 `MOVE_16`；判定用 MethodParameter.getType() 首字符。同源错误还有 FrostDexObfuscator 的算术混淆入口（applyArithmeticObfuscation 第446行 regCount、514-517行 curRegCount!=regCount 路径）。
  - 离线复现：dexlib2 builder 直接构造与 Symbol.toString() 等价的 ImmutableClassDef（寄存器数3、ins_size1、iget-object 复用 this），驱动 applyArithmeticObfuscation 后 dump TwoRegisterInstruction 断言搬移 opcode 与参数类型匹配；混合参数方法（I/J/Object/D/[I）逐一核对分流；真实 classes7.dex 全量跑验证 applied=true 且搬移计数正确（MOVE_OBJECT_16 占主体、MOVE_16 仅 int 参数）。回归 classpath 与其余探针一致，ArithSymbol/GenSymbolDex 位于 /tmp/opencode/regress/。


[Project Knowledge Summary]
- Date: 2026-09-10
- Context: Discovered by Agent while fixing v9.10.35 DrawScope IncompatibleClassChangeError（cc.sylu.palmpc 仅开字符串加密+算法混淆时 drawRect-n-J9OG0$default 内 "Found interface DrawScope, but class was expected"）
- Category: Troubleshooting & Debugging
  - 根因是 dex 版本降级，非方法表分区：`DexFileFactory.loadDexFile(file, Opcodes.getDefault())` 会把加载的 dex 归一化为 api=20/dex-version 035，即使文件头是 038。各 pass 用 `RewrittenDexFile(dex.opcodes, ...)` 写回时沿用被降级的 opcodes → 产物 dex 版本从 038 掉到 035。ART 的 SupportsDefaultMethods() 需要 dex version >= 038（0x38）；035 下 method_verifier.cc:4523-4535 拒绝接口类（klass->IsInterface()）上的 METHOD_DIRECT（invoke-direct 接口方法），DrawScope 的 Kotlin $default 方法体 `invoke-direct DrawScope->offsetSize` 即触发 ICCE。palm 全系 dex 除 classes9.dex 外都是 038，8 个 dex 全受影响。
  - 修复范式：新增 `FrostDexUtils.readDexVersion(File)`（读文件头字节4-6，"038"三字节，索引7为NUL——若读4字节会把 \0 带进 toIntOrNull 返回 null→默认35 的隐蔽 bug）+ `loadDexPreservingVersion(File)`（用 Opcodes.forDexVersion(ver) 加载），替换全部 11 个主加载点（shuffle/debug strip/goto/arithmetic/control flow/call indirection/method overload/field rename/string encrypt/class renamer/reflection injector）。校验性 load（写回后的自检）不需要改。
  - 关联 bug：字符串加密对排除类（androidx 等）的 forceJumbo 分支 `CallReplacer(classDef, emptySet(), 0, ...)` 传 minLen=0 会让 isSensitive() 对所有非空串恒真 → 排除类被错误加密（regs 膨胀+密文+helper 调用）。必须传 `Int.MAX_VALUE` 使 isSensitive 恒 false，排除类只做 const-string(21c)->const-string/jumbo(31c) 纯升级。
  - 离线验证：classes7.dex（stringPool=79691>0xFF00 触发 forceJumbo，methodPool=65266 未超 0xFFF0）跑 StrEncArithVer 断言 dexVersion 保持 38；DrawScopeFull dump 断言 drawRect-n-J9OG0$default regs=26 且无密文（修复前 regs=43+头部10条参数搬移+[62]密文）；FullPipeVer 全 pass 断言版本全程 38；JumboCheck 断言 DrawScope "Super calls..." 串保留原文仅升 jumbo。

[Project Knowledge Summary]
- Date: 2026-09-10
- Context: Discovered by Agent while adding LSPosed injection detection + fixing history dialog layout (v9.10.36)
- Category: Troubleshooting & Debugging
  - LSPosed 检测不能只靠 /proc/self/maps 的 so 库名特征：LSPosed 在 Zygisk/KernelSU 模式注入时会隐藏/匿名映射注入的 so，maps 里看不到 XposedBridge/lspd 等明文库名。强信号是类加载检测——LSPosed 完整实现 Xposed API，hook 生效时进程 classloader 必能加载 de.robv.android.xposed.XposedBridge 与 XposedHelpers。用 cl.loadClass(name)（initialize=false，多 loader 兜底：自身 classloader / context classloader / system classloader）判 true。
  - maps 特征只匹配进程级精确信号：XposedBridge/edxp/sandhook/libxposed/lspd/libxposed_art/libxposed_lite。**严禁把 zygisk/riru 加入 hook 判定**——它们是注入框架本身的全局特征（注入 zygote 后所有 app 进程 maps 均含，即使该 app 完全没被 LSPosed 勾选），用作 hook 证据会在装了 LSPosed/Riru 的设备上误杀所有加固应用（v9.10.36 即因此必闪退，且 triggerKill 是主动杀进程非 Java 异常，无崩溃日志可抓）。Magisk 检测（detectMagisk/magiskCheckZygisk）里 zygisk 语义是"检测设备装有 Magisk"，可以保留。Java S.java 与 native protection.cpp 的 hookCheckXposed/scanMemoryForHookPatterns 同步遵循此原则。
  - S.java 加密串生成法：新串用 python `''.join(chr(ord(c)^K[i%8]) for i,c in enumerate(s))`，K={0x12,0x34,0x56,0x78,0x9A,0xBC,0xDE,0xF0}；写回时统一小写 hex+必要时 (byte) 前缀。改完必须用解密脚本回验一遍防手抄错字节（本次 XposedHelpers 手写错 index28 起 8 字节，脚本回验发现）。
  - Compose AlertDialog 内容超高会裁切 text 区，底部按钮不可见。修复范式：text 的 Column 加 `Modifier.heightIn(max = 430.dp)`，内部列表/详情用 weight 分配各自高度，详情 Text 用 `verticalScroll(rememberScrollState())`，按钮行放最后不设 weight 固定可见。重构此类嵌套布局时注意括号归属——把块移出 Surface 后要同步删除原闭合括号，否则多闭导致后续代码脱离 Composable 作用域（本次即踩坑：按钮 Row 移到 Surface 外后遗留 2 层多余 `}`，Kotlin 报后续方法块 unresolved reference）。

[Project Knowledge Summary]
- Date: 2026-09-10
- Context: Discovered by Agent while fixing v9.10.37 签名校验不生效 + 转换输出名
- Category: Troubleshooting & Debugging
  - 签名校验（重打包检测）存在两个叠加 bug：(1) `signature` 这个 result key 不在 SecurityCheckProvider 的 CRITICAL 集合里，而 enforce() 只遍历 CRITICAL 触发 triggerKill，导致 verifySignatureMatches 即使检测到重签名也不杀进程；(2) 解析 features.cfg 时 `signature_sha256=` 前缀解密明文实际是 17 字符（含 `=`），原代码 `line.substring(18)` 会截掉哈希首字符，应改用 `line.substring(S.t(S.signature_sha256_).length())`。修复范式：CRITICAL 补 `S.t(S.signature)` 与 `S.t(S.integrity)`；monitor 线程也要周期检测签名/完整性（不能只在启动 runAllChecks 一次），判断条件须与 runAllChecks 一致用 `enabled.contains(sig_verify)||enabled.contains(app_sig)`（enabled 存的是 feature key 不是 result key）。
  - 签名转换输出文件名：加固签名配置（SigningProfile）入口的格式转换，`base` 取 `profile.name`（显示名称，清洗 `[\\/:*?"<>|]` 后 fallback 源文件名），替换原来的 `src.name.substringBeforeLast('.')`；签名工具（signTool*，文件选择导入入口）无 profile 名称，保留用源文件名。
  - 探针验证法：SigVerifyProbe 复制 S.t() 解密 + substring 前缀长度解析 + CRITICAL 判定逻辑，JVM 直接跑断言（前缀长度17/12、解析 len=64、重签名 mismatch→kill=true），无需 Android 环境。
