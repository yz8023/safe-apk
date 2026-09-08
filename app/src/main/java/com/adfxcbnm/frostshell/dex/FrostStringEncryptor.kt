package com.adfxcbnm.frostshell.dex

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

    data class Result(
        val encryptedCount: Int,
        val helperCount: Int
    )

    /**
     * 处理单个 dex 文件，就地改写命中敏感词的 const-string。
     */
    @Throws(IOException::class)
    fun process(
        dexFile: File,
        keywords: Set<String>,
        minLen: Int = DEFAULT_MIN_LEN
    ): Result {
        val dex = DexFileFactory.loadDexFile(dexFile, Opcodes.getDefault())
        // DexPool 写入时将 16 位 const-string 引用重排到新索引，string_ids 表超 0xFFFF 时
        // 必然触发 "Unsigned short value out of range"（见 createVectorImageBuilder 实测 67224），
        // 该 dex 结构性无法完成重写，跳过可避免失败回退的无效开销与潜在崩溃风险。
        if ((dex as com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile).stringSection.size > MAX_VALUE) {
            return Result(0, 0)
        }
        var encryptedCount = 0
        var helperCount = 0
        val newClasses = ArrayList<ClassDef>(dex.classes.size)
        for (classDef in dex.classes) {
            // 尊重排除规则：androidx 等框架类保持不动，仅跳过字符串加密改写
            // 必须原样加入 newClasses，否则重写后该类会从 dex 中整体消失，
            // 运行时引用其类型（如 okio.internal.-ByteString）将直接 ClassNotFoundException
            if (com.adfxcbnm.frostshell.config.FrostProtectRules.matchRules(classDef.type)) {
                newClasses.add(classDef)
                continue
            }
            val replacer = CallReplacer(classDef, keywords, minLen)
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
                newDirectMethods.add(buildHelperMethod(classDef.type, replacer.helperName))
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
            // 委托式 DexFile：交由 DexPool 原样写入，避免 ImmutableDexFile 对全部类再做 immutable 化
            val backup = java.io.File(dexFile.absolutePath + ".stringenc_backup")
            val wroteBackup = try {
                backup.writeBytes(dexFile.readBytes())
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
                        dexFile.writeBytes(backup.readBytes())
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
                try {
                    FrostLogUtils.error(
                        "string encrypt: DexPool overflow on %s (%s), restored=%s, skipping this dex",
                        dexFile.name, t.message, restored
                    )
                } catch (l: Throwable) {
                    // 日志失败不阻断异常传递
                }
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

    /** 每个类一次扫描 + 改写 pass，helper 名在类内唯一 */
    private class CallReplacer(
        classDef: ClassDef,
        private val keywords: Set<String>,
        private val minLen: Int
    ) {
        private val hostClass: String = classDef.type
        val helperName: String
        var replacements = 0

        init {
            val existing = HashSet<String>()
            for (method in classDef.methods) {
                existing.add(method.name)
            }
            var candidate: String
            val random = SecureRandom()
            do {
                val sb = StringBuilder("s$")
                for (i in 0 until 8) {
                    sb.append("0123456789abcdef"[random.nextInt(16)])
                }
                candidate = sb.toString()
            } while (existing.contains(candidate))
            helperName = candidate
        }

        private val helperRef: ImmutableMethodReference = ImmutableMethodReference(
            hostClass, helperName, listOf("Ljava/lang/String;", "I"), "Ljava/lang/String;"
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
            if (method.name == "<init>" || method.name == "<clinit>") return null
            val original = impl.instructions
            val targetIndexes = ArrayList<Int>()
            for ((index, instruction) in original.withIndex()) {
                if (instruction !is ReferenceInstruction) continue
                if (instruction !is OneRegisterInstruction) continue
                val reference = (instruction as ReferenceInstruction).reference
                if (reference !is StringReference) continue
                if (isSensitive(reference.string)) targetIndexes.add(index)
            }
            if (targetIndexes.isEmpty()) return null
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
            if (newRegCount > MAX_VALUE) return null
            // 31c/21c 等指令的寄存器仅支持 byte（v0-v255），baseRegs+2 超出 255 时
            // BuilderInstruction31c 会抛 IllegalArgumentException: Invalid register。
            // 无法用临时寄存器承载密文调用的方法整体跳过加密（保持原始指令），
            // 避免整 dex 加密被单个高寄存器方法中断（此前表现为整 dex 加密必失败）。
            if (tmpReg > 0xFF) return null

            val mutable = MutableMethodImplementation(impl)

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
            val headShift = slotTypes.size

            for (i in targetIndexes.size - 1 downTo 0) {
                val index = targetIndexes[i] + headShift
                val instr = mutable.instructions[index]
                val value = (instr as ReferenceInstruction).reference as StringReference? ?: return null
                val origReg = (instr as OneRegisterInstruction).registerA
                val key = FrostStringXorCipher.randomKey()
                val cipher = FrostStringXorCipher.encrypt(value.string, key)
                val reference = ImmutableStringReference(cipher)
                // 替换 const-string 为密文常量
                if (tmpReg <= 0xFF) {
                    mutable.replaceInstruction(index, BuilderInstruction21c(Opcode.CONST_STRING, tmpReg, reference))
                } else {
                    mutable.replaceInstruction(index, BuilderInstruction31c(Opcode.CONST_STRING_JUMBO, tmpReg, reference))
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
            }

            // Mutable 的 registerCount 是 private final，用 facade 提升寄存器数以容纳临时寄存器
            val regBumped = object : MethodImplementation {
                override fun getRegisterCount(): Int = newRegCount
                override fun getInstructions(): Iterable<Instruction> = mutable.instructions
                override fun getTryBlocks(): List<TryBlock<out ExceptionHandler>> = mutable.tryBlocks
                override fun getDebugItems(): Iterable<DebugItem> = mutable.debugItems
            }
            return ImmutableMethod(
                method.definingClass, method.name, method.parameters, method.returnType,
                method.accessFlags, method.annotations, method.hiddenApiRestrictions, regBumped
            )
        }

        private fun isSensitive(value: String): Boolean {
            if (value.isEmpty()) return false
            // 关键词命中时无条件加密：token/apiKey 等短敏感串不受 minLen 拦截
            if (keywords.isNotEmpty() && keywords.any { value.contains(it, ignoreCase = true) }) return true
            if (value.all { it.isDigit() || it.isWhitespace() }) return false
            val cps = value.codePointCount(0, value.length)
            // 含 CJK（中文）的串按语义单元计长，2 字即加密：覆盖"还没有任务"等短中文 UI 文案
            val hasCjk = value.codePoints().toArray().any { cp ->
                (cp in 0x3400..0x4DBF) || (cp in 0x4E00..0x9FFF) || (cp in 0xF900..0xFAFF) || (cp in 0x20000..0x2FA1F)
            }
            // 全量兜底：达到长度阈值即加密，防止中文提示语/业务文案明文落池
            return cps >= (if (hasCjk) 2 else minLen)
        }
    }

    /**
     * 静态解密 helper：String <name>(String cipher, int key)
     * 寄存器规划（registerCount=8，参数 String 与 int 固定在最高位 reg6/reg7）：
     * 0=arr, 1=idx, 2=len, 3=tmp, 4=out, 5=scratch, 6=cipher(param), 7=key(param)
     * 算法：char[] a = cipher.toCharArray();
     *       for (int i = 0; i < a.length; i++) a[i] ^= key;
     *       return new String(a);
     */
    private fun buildHelperMethod(hostClass: String, methodName: String): Method {
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
            hostClass, methodName, listOf<MethodParameter>(
                ImmutableMethodParameter("Ljava/lang/String;", null, null),
                ImmutableMethodParameter("I", null, null)
            ), "Ljava/lang/String;",
            AccessFlags.PRIVATE.value or AccessFlags.STATIC.value or AccessFlags.SYNTHETIC.value,
            null, null, impl
        )
    }
}