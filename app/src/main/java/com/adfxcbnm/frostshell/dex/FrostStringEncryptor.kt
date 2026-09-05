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
        var encryptedCount = 0
        var helperCount = 0
        val newClasses = ArrayList<ClassDef>(dex.classes.size)
        for (classDef in dex.classes) {
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
            DexFileFactory.writeDexFile(dexFile.absolutePath, RewrittenDexFile(dex.opcodes, newClasses))
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
         */
        fun rewriteMethod(method: Method): Method? {
            val impl = method.implementation ?: return null
            if (method.name == "<init>" || method.name == "<clinit>") return null
            if (impl.tryBlocks.isNotEmpty()) return null
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
            if (baseRegs + 2 > MAX_VALUE) return null
            val tmpReg = baseRegs
            val keyReg = baseRegs + 1
            val newRegCount = baseRegs + 2

            val mutable = MutableMethodImplementation(impl)
            for (i in targetIndexes.size - 1 downTo 0) {
                val index = targetIndexes[i]
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
            if (value.codePointCount(0, value.length) < minLen) return false
            if (value.all { it.isDigit() || it.isWhitespace() }) return false
            if (keywords.isEmpty()) return value.length >= minLen + 4
            return keywords.any { value.contains(it, ignoreCase = true) }
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