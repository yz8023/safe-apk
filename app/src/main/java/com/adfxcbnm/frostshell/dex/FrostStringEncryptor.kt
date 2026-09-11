package com.adfxcbnm.frostshell.dex

import com.adfxcbnm.frostshell.util.FrostIoUtils
import com.adfxcbnm.frostshell.util.FrostLogUtils
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction10t
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction10x
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction11n
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction11x
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction12x
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21c
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21s
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction22b
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction22t
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction23x
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction31c
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction32x
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction3rc
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.ExceptionHandler
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.MethodImplementation
import com.android.tools.smali.dexlib2.iface.MethodParameter
import com.android.tools.smali.dexlib2.iface.TryBlock
import com.android.tools.smali.dexlib2.iface.debug.DebugItem
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21s
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction31c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction32x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction3rc
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.util.Collections

/**
 * L1 通用字符串加密 pass（对齐 ArkProtector 常量字符串加密语义）。
 *
 * 对 const-string 常量中命中敏感词（vip/login/token/...）或长度超阈值的串做 XOR 加密：
 *  - 原始字符串只以密文形式留在 DEX 字符串池，明文永不落盘
 *  - 每类注入一个静态解密 helper，调用点替换为（高寄存器安全，避免 35c 低寄存器限制）：
 *       const-string/jumbo vTmp, 密文
 *       const/16        vKey, key
 *       invoke-static/range {vTmp, vKey}, helper
 *       move-result-object vTmp
 *       move-object/16 vOrig, vTmp
 *  - key 由调用点作为常量传入（对齐 FrostReflectionClinitInjector 的 CONST_16 key 模式），
 *    helper 内以参数形式读取，无需从密文长度推导，规避零规约与可逆性风险
 *
 * 约束：跳过 <init>/<clinit> 与含 try-block 的方法（避免标签偏移破坏异常表）。
 * 加密必须早于 extractAllMethods（方法体清零）执行。
 */
object FrostStringEncryptor {

    private const val DEFAULT_MIN_LEN = 6
    private const val MAX_VALUE = 0xFFFF

    private val TO_CHAR_ARRAY = ImmutableMethodReference("Ljava/lang/String;", "toCharArray", Collections.emptyList(), "[C")
    private val STRING_INIT = ImmutableMethodReference("Ljava/lang/String;", "<init>", listOf("[C"), "V")

    /**
     * 敏感串判定策略（顶层版）。与 CallReplacer.isSensitive 保持一致，预扫描与改写路径
     * 必须复用同一策略，避免"池判定有敏感串/改写判无"或反之的判定漂移。
     */
    internal fun isSensitivePolicy(
        value: String,
        keywords: Set<String>,
        minLen: Int,
        allCjk: Boolean,
        allUrl: Boolean
    ): Boolean {
        if (value.isEmpty()) return false
        // 中文/URL 全量模式：无条件加密，满足"所有中文字符串与 URL 型字符串不可明文落池"
        if (allCjk && containsCjk(value)) return true
        if (allUrl && isUrlLike(value)) return true
        // 关键词命中时无条件加密：token/apiKey 等短敏感串不受 minLen 拦截
        if (keywords.isNotEmpty() && keywords.any { value.contains(it, ignoreCase = true) }) return true
        if (value.all { it.isDigit() || it.isWhitespace() }) return false
        val cps = value.codePointCount(0, value.length)
        // 含 CJK（中文）的串按语义单元计长，2 字即加密：覆盖"还没有任务"等短中文 UI 文案
        val hasCjk = containsCjk(value)
        // 全量兜底：达到长度阈值即加密，防止中文提示语/业务文案明文落池
        return cps >= (if (hasCjk) 2 else minLen)
    }

    internal fun containsCjk(value: String): Boolean =
        value.codePoints().toArray().any { cp ->
            (cp in 0x3400..0x4DBF) || (cp in 0x4E00..0x9FFF) || (cp in 0xF900..0xFAFF) || (cp in 0x20000..0x2FA1F)
        }

    internal fun isUrlLike(value: String): Boolean {
        val v = value.trim()
        if (v.length < 4) return false
        val lower = v.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://")) return true
        if (lower.startsWith("ftp://") || lower.startsWith("ws://") || lower.startsWith("wss://")) return true
        // 常见内网协议/自定义 scheme 包 const-string 的形式，如 https://p.qlogo.cn/gh/...
        if (lower.contains("://")) return true
        // Android 平台类型 URL 与 schema/xmlns 等也属于信息泄露面
        if (lower.startsWith("android.resource://") || lower.startsWith("content://")) return true
        return false
    }

    /**
     * 轻量预扫描快速路径：仅迭代字符串池判定是否存在敏感串，不触碰任何类/方法指令。
     * 池中无敏感串 ⇒ 全 dex 的 const-string 引用都不可能在改写阶段命中 ⇒ 本轮必然
     * encryptedCount==0 且不进 DexPool 写回，输出与"原样复制"逐字节一致。短路后跳过
     * 全量遍历与对象图重建，对无中文的纯第三方字节码 dex 省去绝大部分开销。
     */
    private fun hasSensitivePoolString(
        dex: com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile,
        keywords: Set<String>,
        minLen: Int,
        allCjk: Boolean,
        allUrl: Boolean
    ): Boolean {
        for (s in dex.stringSection) {
            if (isSensitivePolicy(s, keywords, minLen, allCjk, allUrl)) return true
        }
        return false
    }

    /**
     * 共享解密 helper 的方法名。所有加密点引用同一条 MethodReference（定义在同一共享 helper 类内），
     * 因此 method 池仅 +1，避免每类独立 helper 导致 method_ids 池暴涨击穿 65,535 上限。
     */
    private val HELP_METHOD = "s0x00frozen0x00"

    data class Result(
        val encryptedCount: Int,
        val helperCount: Int
    )

    /**
     * 处理单个 dex 文件，就地改写命中敏感词的 const-string。
     *
     * allCjk：所有含 CJK 中文的 const-string 一律加密（含单字，不再受长度阈值限制）
     * allUrl：所有 URL/网络路径型 const-string 一律加密（http://、https://、含 "://" 等）
     * includeInitClinit：允许改写 <init>/<clinit> 中的字符串（脱壳明文中文大量存在于静态初始化块）
     */
    @Throws(IOException::class)
    fun process(
        dexFile: File,
        keywords: Set<String>,
        minLen: Int = DEFAULT_MIN_LEN,
        allCjk: Boolean = true,
        allUrl: Boolean = true,
        includeInitClinit: Boolean = true
    ): Result {
        // 大 dex 内存守卫：dexlib2 的 DexPool 写回会把全部类/方法体/字符串池化进对象图，
        // 加上本 pass 对所有命中方法做 MutableMethodImplementation 深拷贝，超大 classes.dex
        // （源 APK 30-100MB 时单文件可达 20-40MB+）会让加固工具 App 自身 OOM 闪退。
        // 阈值按当前进程堆上限自适应（大 heap 设备 ~512MB，普通设备 256MB）：
        //   heap 512MB -> 跳过 >42MB 的 dex；heap 256MB -> 跳过 >21MB 的 dex。
        // DexPool 对象图放大经验倍率 8-12x，取 maxHeap/12 保证峰值堆不超 75% 水位。
        // 通过 TMPFS/APK 解压得到的 dex 按实际文件大小估算，避免静默崩溃。
        val maxHeap = Runtime.getRuntime().maxMemory()
        val dexSize = dexFile.length()
        if (maxHeap > 0 && dexSize > maxHeap / 12) {
            FrostLogUtils.warn(
                "string encrypt: skip %s (dex too large: %dMB, heap=%dMB), would OOM the tool",
                dexFile.name, dexSize / (1024 * 1024), maxHeap / (1024 * 1024)
            )
            return Result(0, 0)
        }
        // 动态可用堆守卫（第二道保险）：静态阈值只按文件大小粗判，但 DexPool 写回时对象图
        // 会在短时间内再放大 8-12x。若当前进程已吃进较多内存（前面 dex 处理残留、UI 等），
        // 即使 dex 未超静态阈值也可能 OOM。故在加载前评估当前可用堆，按 10x 预估峰值，
        // 不足即跳过该 dex（保留后续 dex 的加密机会，而非让整个加固进程闪退）。
        if (maxHeap > 0) {
            val usedHeap = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
            val availHeap = maxHeap - usedHeap
            if (availHeap > 0 && dexSize > availHeap / 10) {
                FrostLogUtils.warn(
                    "string encrypt: skip %s (avail heap %dMB too low for dex %dMB x10 estimate)",
                    dexFile.name, availHeap / (1024 * 1024), dexSize / (1024 * 1024)
                )
                return Result(0, 0)
            }
        }
        val dex = com.adfxcbnm.frostshell.util.FrostDexUtils.loadDexPreservingVersion(dexFile)
        // 方法池保护：Dalvik 的 invoke 指令（format 35c/3rc）用 16 位 method 索引，且没有 jumbo 变体，
        // 因此单 dex 的 method_ids 数量必须远小于 65,535。实测完整 palm 的 classes7.dex method_ids=65266、
        // classes.dex method_ids=65441，仅余 269/94 个槽位。字符串加密会新增 helper 方法引用并使
        // DexPool 重排方法池；若某被引用方法的索引被重排到 >0xFFFF，写回即抛
        // "Unsigned short value out of range"（InstructionWriter.write(Instruction35c)），且无法回退为 jumbo。
        // 该 dex 若临近上限只能整体跳过字符串加密（宁可少加密，不能产出损坏 dex）。
        val dexBacked = dex as com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
        // 轻量预扫描快速路径：字符串池中没有任何敏感词/CJK/URL/超长串时，本轮改写必然
        // 0 命中、0 helper、不触发 DexPool 写回，输出与原样复制逐字节一致。此时直接短路，
        // 跳过下方"遍历全部类→全部方法→全部指令"的开销最大的路径。对无中文的纯第三方
        // 字节码 dex（常见于大 APK 的 classes3+ 等），从"全量扫描+白跑一趟"变为"扫描即过"，
        // 是手机端提速与降内存峰值的主要来源。策略与改写阶段复用同一 isSensitivePolicy。
        if (!hasSensitivePoolString(dexBacked, keywords, minLen, allCjk, allUrl)) {
            FrostLogUtils.info(
                "string encrypt: %s no sensitive string, pass-through (fast path)",
                dexFile.name
            )
            return Result(0, 0)
        }
        val methodCount = dexBacked.methodSection.size
        if (methodCount > 0xFFF0) {
            FrostLogUtils.warn(
                "string encrypt: skip %s (method pool too full: %d/%d), no new helper can be added safely",
                dexFile.name, methodCount, 0xFFFF
            )
            return Result(0, 0)
        }
        // 说明：早期 0xFFFF 保护基于 stringSection.size（string_ids 超 65535 即跳过）——
        // 实测完整 palm classes.dex（string_ids=123031）可正常写回，字符串池本身无 65535 硬顶
        // （有 const-string/jumbo 32 位索引），故该判据作废。改写为上面的 method_ids 判据。
        // forceJumbo：字符串池过大（接近或超过 65,535 时），加密新增密文字符串会令 DexPool
        // 重排字符串池，把原本 16 位可表示的 const-string(21c) 引用索引推到 >0xFFFF，
        // 写回时 InstructionWriter 抛 "Unsigned short value out of range"。此时必须把所有
        // const-string(21c) 升级为 const-string/jumbo(31c)，以 32 位索引承载重排后的字符串。
        val jumboThreshold = 0xFF00
        val poolCount = dexBacked.stringSection.size
        val forceJumbo = poolCount > jumboThreshold
        var encryptedCount = 0
        var helperCount = 0
        // 共享解密 helper 类：整个 dex 只注入一个类、一个方法，method 池仅 +1。
        // 名称随机生成以避免与已有类冲突（重名会让 DexPool 合并出错误方法定义）。
        val sharedHelperType = buildSharedHelperType(dex)
        val newClasses = ArrayList<ClassDef>(dex.classes.size)
        for (classDef in dex.classes) {
            // 尊重排除规则：androidx 等框架类保持不动，仅跳过字符串加密改写
            // 必须原样加入 newClasses，否则重写后该类会从 dex 中整体消失，
            // 运行时引用其类型（如 okio.internal.-ByteString）将直接 ClassNotFoundException。
            // 但池超限时，排除类的既有 const-string(21c) 同样会因字符串池重排而索引溢出，
            // 因此 forceJumbo 模式下对排除类也执行纯 jumbo 升级（不加密字符串）。
            if (com.adfxcbnm.frostshell.config.FrostProtectRules.matchRules(classDef.type)) {
                if (!forceJumbo) {
                    newClasses.add(classDef)
                    continue
                }
                // jumboOnly：minLen=Int.MAX_VALUE 让 isSensitive 恒 false，排除类字符串
                // 只做 const-string(21c)->const-string/jumbo(31c) 升级，绝不加密（不插入
                // helper 调用/参数搬移）。此前传 minLen=0 使 isSensitive 对所有非空串恒真，
                // 排除类被错误加密并改写方法体（regs 膨胀+密文），破坏 androidx 等框架类。
                val jumboOnly = CallReplacer(classDef, emptySet(), Int.MAX_VALUE, false, false, false, true, sharedHelperType)
                var dirty = false
                val dirs = ArrayList<Method>()
                for (method in classDef.directMethods) {
                    val r = jumboOnly.rewriteMethod(method)
                    if (r != null) { dirs.add(r); dirty = true } else dirs.add(method)
                }
                val virs = ArrayList<Method>()
                for (method in classDef.virtualMethods) {
                    val r = jumboOnly.rewriteMethod(method)
                    if (r != null) { virs.add(r); dirty = true } else virs.add(method)
                }
                newClasses.add(if (dirty) RewrittenClassDef(classDef, dirs, virs) else classDef)
                continue
            }
            val replacer = CallReplacer(classDef, keywords, minLen, allCjk, allUrl, includeInitClinit, forceJumbo, sharedHelperType)
            var classDirty = false
            val newDirectMethods = ArrayList<Method>()
            for (method in classDef.directMethods) {
                val replaced = replacer.rewriteMethod(method)
                if (replaced != null) {
                    newDirectMethods.add(replaced)
                    classDirty = true
                } else {
                    newDirectMethods.add(method)
                }
            }
            val newVirtualMethods = ArrayList<Method>()
            for (method in classDef.virtualMethods) {
                val replaced = replacer.rewriteMethod(method)
                if (replaced != null) {
                    newVirtualMethods.add(replaced)
                    classDirty = true
                } else {
                    newVirtualMethods.add(method)
                }
            }
            val classReplacementCount = replacer.replacements
            encryptedCount += classReplacementCount
            if (classReplacementCount > 0) {
                helperCount++
                classDirty = true
            }
            newClasses.add(
                if (classDirty) {
                    // 委托式 ClassDef：仅替换方法集合，不做整类 immutable 重建（避免大 dex TreeSet 排序 OOM）
                    RewrittenClassDef(classDef, newDirectMethods, newVirtualMethods)
                } else {
                    classDef
                }
            )
        }
        if (encryptedCount > 0) {
            // 注入全局共享解密 helper 类。池资源核算：共享 helper 使 method 池仅 +1、
            // type 池 +1、class_defs +1、string 池 +2（类名与方法名），对 classes7
            // （method=65266/type=8867/class=7335）等满池 dex 也完全安全。
            newClasses.add(
                ImmutableClassDef(
                    sharedHelperType,
                    AccessFlags.PUBLIC.value or AccessFlags.FINAL.value or AccessFlags.SYNTHETIC.value,
                    "Ljava/lang/Object;",
                    emptyList(),
                    null,
                    emptySet(),
                    emptyList(),
                    emptyList(),
                    listOf(buildHelperMethod(sharedHelperType)),
                    emptyList()
                )
            )
            // 委托式 DexFile：交由 DexPool 原样写入，避免 ImmutableDexFile 对全部类再做 immutable 化
            val backup = java.io.File(dexFile.absolutePath + ".stringenc_backup")
            val wroteBackup = try {
                // 流式复制，避免整 dex 以 ByteArray 形式多驻留一份（超大 dex 下会加剧 OOM）
                FrostIoUtils.copyFile(dexFile.absolutePath, backup.absolutePath)
                true
            } catch (t: Throwable) {
                false
            }
            try {
                DexFileFactory.writeDexFile(dexFile.absolutePath, RewrittenDexFile(dex.opcodes, newClasses))
            } catch (t: Throwable) {
                // DexPool 对 method 池接近 0xFFFF 的大 dex 重排时溢出（Unsigned short out of range），
                // 恢复原始 dex，保证产物不携带损坏方法体（宁可该 dex 跳过字符串加密）。
                // 恢复与清理失败不得阻断流程：dex 损坏会被上层 extractAllMethods 再次兜底，
                // 但尽量在此恢复完整原始文件。
                var restored = false
                if (wroteBackup) {
                    try {
                        FrostIoUtils.copyFile(backup.absolutePath, dexFile.absolutePath)
                        restored = true
                    } catch (r: Throwable) {
                        // 恢复失败：文件可能已损坏，交由上层逻辑兜底
                    }
                }
                try {
                    backup.delete()
                } catch (d: Throwable) {
                    // 忽略清理失败
                }
                FrostLogUtils.error("string encrypt: DexPool overflow on ${dexFile.name} ($t), restored=$restored helper=$helperCount enc=$encryptedCount")
                throw t
            }
            backup.delete()
            FrostLogUtils.info(
                "string encrypt: strings=%d helpers=%d file=%s",
                encryptedCount, helperCount, dexFile.name
            )
        }
        return Result(encryptedCount, helperCount)
    }

    /**
     * 每个类一次扫描 + 改写 pass。所有加密点共享同一个解密 helper 类：
     * 每类各自新增 helper 会让大 dex 的 method_ids 池暴涨（完整 palm classes7 加密并集达 722 个新
     * 方法，+2762 索引），而 Dalvik 的 invoke 指令 method 索引仅 16 位且无 jumbo 变体（format 35c/3rc），
     * method_ids 逼近 65,535 的 dex 必然在 DexPool 写回时溢出。共享单个 helper（唯一 class + 唯一方法）
     * 使 method 池仅 +1，其余加密点都引用同一条 MethodReference，避免写回失败。
     */
    private class CallReplacer(
        classDef: ClassDef,
        private val keywords: Set<String>,
        private val minLen: Int,
        private val allCjk: Boolean,
        private val allUrl: Boolean,
        private val includeInitClinit: Boolean,
        private val forceJumbo: Boolean,
        private val sharedHelperType: String
    ) {
        private val hostClass: String = classDef.type
        var replacements = 0

        private val helperRef: ImmutableMethodReference = ImmutableMethodReference(
            sharedHelperType, HELP_METHOD, listOf("Ljava/lang/String;", "I"), "Ljava/lang/String;"
        )

        /**
         * 改写一个方法，返回新方法或 null（无改动）。
         *
         * 使用 MutableMethodImplementation(MethodImplementation) 复制方法体：其内部会把
         * 所有 offset 指令（goto/if/switch 等）经 codeAddress 映射转为 label 式 builder
         * 指令，插入/替换后由 fixInstructions 统一重算跳转偏移。直接拼接 backed 指令会
         * 保留原始 codeOffset，插入新指令后将导致跳转目标错位（ART VerifyError）。
         *
         * 寄存器布局：Dalvik 调用约定中参数寄存器锚定在寄存器区最高位，registerCount
         * 增加后参数寄存器自动整体上移，但指令中对参数寄存器的引用不会跟随迁移（会演化为
         * "instance field access on object that has non-reference type Undefined"）。
         * 因此在方法头部插入参数搬移指令：从新的参数寄存器（src=baseRegs+4+pos）搬回原
         * 参数寄存器（dst=low+pos），保持既有指令引用不变；临时寄存器 tmp/key 放在参数
         * 区之下的新增空间（baseRegs+2/baseRegs+3）。
         */
        fun rewriteMethod(method: Method): Method? {
            val impl = method.implementation ?: return null
            val inInitOrClinit = method.name == "<init>" || method.name == "<clinit>"
            val original = impl.instructions
            val targetIndexes = ArrayList<Int>()
            val jumboIndexes = HashSet<Int>()
            for ((index, instruction) in original.withIndex()) {
                if (instruction !is ReferenceInstruction) continue
                if (instruction !is OneRegisterInstruction) continue
                val reference = (instruction as ReferenceInstruction).reference
                if (reference !is StringReference) continue
                // forceJumbo：字符串池超限时，原只支持 16 位字符串索引的 const-string(21c)
                // 在加密新增密文字符串（DexPool 重排字符串池）后会将引用索引推到 >0xFFFF，
                // 直接写回会抛 "Unsigned short value out of range"。升级为 const-string/jumbo(31c)，
                // 用 32 位索引承载重排后的任意字符串偏移。
                // jumbo 升级与 includeInitClinit 解耦：即便不加密 init/clinit 中的字符串，
                // 只要池超限，其中 21c 也必须升级，否则重排后同样溢出（根因见 createVectorImageBuilder）。
                if (forceJumbo && (instruction as Instruction).opcode == Opcode.CONST_STRING) jumboIndexes.add(index)
                if (!inInitOrClinit || includeInitClinit) {
                    if (isSensitive(reference.string)) targetIndexes.add(index)
                }
            }
            // 无敏感串且无需 jumbo 升级时跳过；forceJumbo 下只要存在任意 21c 就必须重写
            if (targetIndexes.isEmpty() && jumboIndexes.isEmpty()) return null
            val haveEncrypt = targetIndexes.isNotEmpty()
            val baseRegs = impl.registerCount
            val isStatic = method.accessFlags and AccessFlags.STATIC.value != 0

            // 计算参数寄存器槽位。Dalvik 中参数寄存器锚定在寄存器区最高位且按位序排列：
            // 实例方法的 this 占据最低参数位，其后依次为显式参数（按声明顺序）。
            // 因此 slotTypes 必须按此真实位序构造（this 在前），否则搬移指令的 dst/src
            // 与真实参数错位，多参数方法会读错寄存器类型（ART VerifyError 闪退）。
            val slotTypes = ArrayList<String>()
            if (!isStatic) slotTypes.add("Lthis;")
            for (p in method.parameters) slotTypes.add(p.type)
            val slotWidths = slotTypes.map { if (it == "J" || it == "D") 2 else 1 }
            val totalSlots = slotWidths.sum()
            val low = baseRegs - totalSlots

            // 临时寄存器位于参数区（新位置 baseRegs+4 起）之下，参数搬移后不与任何既有引用冲突
            val tmpReg = baseRegs + 2
            val keyReg = baseRegs + 3
            val newRegCount = baseRegs + totalSlots + 4
            if (haveEncrypt && newRegCount > MAX_VALUE) return null
            // 31c/21c 等指令的寄存器仅支持 byte（v0-v255），baseRegs+2 超出 255 时
            // BuilderInstruction31c 会抛 IllegalArgumentException: Invalid register。
            // 无法用临时寄存器承载密文调用的方法整体跳过加密（保持原始指令），
            // 避免整 dex 加密被单个高寄存器方法中断（此前表现为整 dex 加密必失败）。
            if (haveEncrypt && tmpReg > 0xFF) return null

            val mutable = MutableMethodImplementation(impl)

            val headShift: Int
            if (haveEncrypt) {
                // 方法头部插入参数搬移：新参数区（src=baseRegs+4+pos）搬回原参数区（dst=low+pos）
                // 每条 move 对应一个参数类型槽位，头部共插入 slotTypes.size 条指令；
                // 按位序正向计算 dst/src（pos 累加槽宽），再倒序插入以保持头部最终为位序排列。
                var pos = 0
                val paramMoves = ArrayList<BuilderInstruction32x>(slotTypes.size)
                for (i in slotTypes.indices) {
                    val w = slotWidths[i]
                    val dst = low + pos
                    val src = baseRegs + 4 + pos
                    val op = when {
                        w == 2 -> Opcode.MOVE_WIDE_16
                        slotTypes[i] == "Lthis;" || slotTypes[i].startsWith("L") || slotTypes[i].startsWith("[") ->
                            Opcode.MOVE_OBJECT_16
                        else -> Opcode.MOVE_16
                    }
                    paramMoves.add(BuilderInstruction32x(op, dst, src))
                    pos += w
                }
                for (m in paramMoves.asReversed()) {
                    mutable.addInstruction(0, m)
                }
                headShift = slotTypes.size
            } else {
                headShift = 0
            }

            // 合并处理点，从后往前替换保证 index 稳定（加密会插入 4 条，先处理后位）
            val allIndexes = (targetIndexes.map { it }.toHashSet() + jumboIndexes).sortedDescending()
            for (rawIndex in allIndexes) {
                val index = rawIndex + headShift
                val instr = mutable.instructions[index]
                val value = (instr as ReferenceInstruction).reference as StringReference? ?: return null
                val origReg = (instr as OneRegisterInstruction).registerA
                if (rawIndex in targetIndexes) {
                    val key = FrostStringXorCipher.randomKey()
                    val cipher = FrostStringXorCipher.encrypt(value.string, key)
                    val reference = ImmutableStringReference(cipher)
                    // 替换 const-string 为密文常量；forceJumbo 下密文索引同样可能 >0xFFFF，统一用 31c
                    if (forceJumbo || tmpReg > 0xFF) {
                        mutable.replaceInstruction(index, BuilderInstruction31c(Opcode.CONST_STRING_JUMBO, tmpReg, reference))
                    } else {
                        mutable.replaceInstruction(index, BuilderInstruction21c(Opcode.CONST_STRING, tmpReg, reference))
                    }
                    // 剩余 4 条以 (index+1) 为锚点倒序插入，保证最终序列：
                    // ins1 const/16 key, ins2 invoke-static/range, ins3 move-result-object, ins4 move-object/16
                    val rest = listOf(
                        BuilderInstruction21s(Opcode.CONST_16, keyReg, key),
                        BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE, tmpReg, 2, helperRef),
                        BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, tmpReg),
                        BuilderInstruction32x(Opcode.MOVE_OBJECT_16, origReg, tmpReg)
                    )
                    for (ins in rest.asReversed()) {
                        mutable.addInstruction(index + 1, ins)
                    }
                    replacements++
                } else {
                    // 纯 jumbo 升级：保留原字符串引用，仅将 21c 换为 31c 指令格式
                    mutable.replaceInstruction(index, BuilderInstruction31c(Opcode.CONST_STRING_JUMBO, origReg, value))
                }
            }

            // Mutable 的 registerCount 是 private final，用 facade 提升寄存器数以容纳临时寄存器
            val regBumped = object : MethodImplementation {
                override fun getRegisterCount(): Int = if (haveEncrypt) newRegCount else baseRegs
                override fun getInstructions(): Iterable<Instruction> = mutable.instructions
                override fun getTryBlocks(): List<TryBlock<out ExceptionHandler>> = mutable.tryBlocks
                override fun getDebugItems(): Iterable<DebugItem> = mutable.debugItems
            }
            return ImmutableMethod(
                method.definingClass, method.name, method.parameters, method.returnType,
                method.accessFlags, method.annotations, method.hiddenApiRestrictions, regBumped
            )
        }

        private fun isSensitive(value: String): Boolean =
            com.adfxcbnm.frostshell.dex.FrostStringEncryptor.isSensitivePolicy(
                value, keywords, minLen, allCjk, allUrl
            )
    }

    /**
     * 共享解密 helper 类类型名生成。须与 dex 中已有类型不冲突，否则 DexPool 会把两个
     * class_def 合并为同一 type（classIdx）导致方法引用错配。生成后做一次存在性扫描。
     */
    private fun buildSharedHelperType(dex: com.android.tools.smali.dexlib2.iface.DexFile): String {
        val random = SecureRandom()
        val taken = HashSet<String>()
        for (c in dex.classes) taken.add(c.type)
        var candidate: String
        do {
            val sb = StringBuilder("La/")
            for (i in 0 until 6) {
                sb.append("0123456789abcdefg"[random.nextInt(16)])
            }
            candidate = sb.toString() + ";"
        } while (taken.contains(candidate))
        return candidate
    }

    /**
     * 全局共享静态解密 helper：String <name>(String cipher, int key)
     * 寄存器规划（registerCount=8，参数 String 与 int 固定在最高位 reg6/reg7）：
     * 0=arr, 1=idx, 2=len, 3=tmp, 4=out, 5=scratch, 6=cipher(param), 7=key(param)
     * 算法：char[] a = cipher.toCharArray();
     *       for (int i = 0; i < a.length; i++) a[i] ^= key;
     *       return new String(a);
     */
    private fun buildHelperMethod(hostClass: String): Method {
        val impl = MutableMethodImplementation(8)
        val arrReg = 0
        val idxReg = 1
        val lenReg = 2
        val tmpReg = 3
        val outReg = 4
        val scratchReg = 5
        val cipherReg = 6
        val keyReg = 7

        // char[] arr = cipher.toCharArray();
        impl.addInstruction(BuilderInstruction35c(Opcode.INVOKE_VIRTUAL, 1, cipherReg, 0, 0, 0, 0, TO_CHAR_ARRAY))
        impl.addInstruction(BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, arrReg))

        // int len = arr.length;
        impl.addInstruction(BuilderInstruction12x(Opcode.ARRAY_LENGTH, lenReg, arrReg))

        // int idx = 0;
        impl.addInstruction(BuilderInstruction11n(Opcode.CONST_4, idxReg, 0))

        // 循环：for (int i = idx; i < len; i++) { c = arr[i]; c ^= key; arr[i] = c; }
        val loopStartIndex = impl.instructions.size
        impl.addInstruction(BuilderInstruction10x(Opcode.NOP))
        impl.addInstruction(BuilderInstruction23x(Opcode.AGET_CHAR, tmpReg, arrReg, idxReg))
        impl.addInstruction(BuilderInstruction23x(Opcode.XOR_INT, tmpReg, tmpReg, keyReg))
        impl.addInstruction(BuilderInstruction12x(Opcode.INT_TO_CHAR, tmpReg, tmpReg))
        impl.addInstruction(BuilderInstruction23x(Opcode.APUT_CHAR, tmpReg, arrReg, idxReg))
        impl.addInstruction(BuilderInstruction22b(Opcode.ADD_INT_LIT8, idxReg, idxReg, 1))
        impl.addInstruction(BuilderInstruction10t(Opcode.GOTO, impl.newLabelForIndex(loopStartIndex)))
        val doneIndex = impl.instructions.size
        impl.addInstruction(BuilderInstruction10x(Opcode.NOP))
        impl.addInstruction(
            BuilderInstruction21c(Opcode.NEW_INSTANCE, outReg, ImmutableTypeReference("Ljava/lang/String;"))
        )
        impl.addInstruction(BuilderInstruction35c(Opcode.INVOKE_DIRECT, 2, outReg, arrReg, 0, 0, 0, STRING_INIT))
        impl.addInstruction(BuilderInstruction11x(Opcode.RETURN_OBJECT, outReg))
        // 将循环入口的 NOP 替换为分支指令：if (idx >= len) goto done
        val doneLabel = impl.newLabelForIndex(doneIndex)
        impl.replaceInstruction(loopStartIndex, BuilderInstruction22t(Opcode.IF_GE, idxReg, lenReg, doneLabel))
        impl.replaceInstruction(doneIndex, BuilderInstruction10x(Opcode.NOP))

        return ImmutableMethod(
            hostClass, HELP_METHOD, listOf<MethodParameter>(
                ImmutableMethodParameter("Ljava/lang/String;", null, null),
                ImmutableMethodParameter("I", null, null)
            ), "Ljava/lang/String;",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.SYNTHETIC.value,
            null, null, impl
        )
    }
}