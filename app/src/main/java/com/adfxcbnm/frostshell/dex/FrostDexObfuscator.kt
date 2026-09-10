package com.adfxcbnm.frostshell.dex

import com.adfxcbnm.frostshell.util.FrostLogUtils
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.Annotation
import com.android.tools.smali.dexlib2.iface.AnnotationElement
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.ExceptionHandler
import com.android.tools.smali.dexlib2.iface.Field
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.TryBlock
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.value.AnnotationEncodedValue
import com.android.tools.smali.dexlib2.iface.value.ArrayEncodedValue
import com.android.tools.smali.dexlib2.iface.value.EncodedValue
import com.android.tools.smali.dexlib2.iface.value.EnumEncodedValue
import com.android.tools.smali.dexlib2.immutable.ImmutableAnnotation
import com.android.tools.smali.dexlib2.immutable.ImmutableAnnotationElement
import com.android.tools.smali.dexlib2.immutable.ImmutableExceptionHandler
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableTryBlock
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21s
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction23x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction31i
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction32x
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.value.ImmutableAnnotationEncodedValue
import com.android.tools.smali.dexlib2.immutable.value.ImmutableArrayEncodedValue
import com.android.tools.smali.dexlib2.immutable.value.ImmutableEnumEncodedValue
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstructionFactory
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import java.util.LinkedHashSet
import java.util.zip.Adler32

/**
 * 移植自 ArkProtector DexObfuscator 的 DEX 层混淆 pass（逐个移植，修复原版崩溃点）。
 *
 * 与原版关键差异：
 *  - 全部走 dexlib2 builder/delegate 重写，写入后立即用 loadDexFile 自检，失败自动回滚原文件
 *  - 不使用 ImmutableClassDef 整类深拷贝（大 dex OOM），沿用 RewrittenClassDef 委托式替换
 *  - 类顺序打乱用 RewrittenDexFile 而非 DexPool（避免 MappedByteBuffer + DexPool 双倍内存）
 */
object FrostDexObfuscator {

    private val random = SecureRandom()
    private val NAME_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private val NAME_CHARS_EXT = NAME_CHARS + "0123456789"

    // ===== 1. DEX 头部混淆 =====
    // 向 header 与首个数据段之间的 padding 区域填充随机字节，并重算 signature(SHA1) 与 checksum(Adler32)。
    // 不修改 magic / file_size / header_size / endian_tag / map_off，ART 可正常加载。
    fun obfuscateDexHeader(dexFile: File): Boolean {
        val data: ByteArray = try {
            dexFile.readBytes()
        } catch (e: Exception) {
            FrostLogUtils.warn("dex header obfuscation: read failed for %s: %s", dexFile.name, e.message)
            return false
        }
        if (data.size < 0x70) return false
        // 校验 magic "dex\n"
        if (data[0] != 'd'.code.toByte() || data[1] != 'e'.code.toByte() ||
            data[2] != 'x'.code.toByte() || data[3] != '\n'.code.toByte()
        ) return false
        val headerSize = readLeInt(data, 0x24)
        if (headerSize != 0x70) return false
        val fileSize = readLeInt(data, 0x20).let { if (it < 0x70 || it > data.size) data.size else it }
        // firstSectionOff = 所有 >= headerSize 的段偏移中的最小值
        val sectionOffsets = intArrayOf(
            readLeInt(data, 0x30), // link_off
            readLeInt(data, 0x34), // map_off
            readLeInt(data, 0x3C), // string_ids_off
            readLeInt(data, 0x44), // type_ids_off
            readLeInt(data, 0x4C), // proto_ids_off
            readLeInt(data, 0x54), // field_ids_off
            readLeInt(data, 0x5C), // method_ids_off
            readLeInt(data, 0x64), // class_defs_off
            readLeInt(data, 0x6C)  // data_off
        )
        var firstSectionOff = Int.MAX_VALUE
        for (off in sectionOffsets) {
            if (off >= headerSize && off < firstSectionOff) firstSectionOff = off
        }
        if (firstSectionOff == Int.MAX_VALUE) firstSectionOff = headerSize
        if (firstSectionOff > headerSize) {
            val padLen = firstSectionOff - headerSize
            val noise = ByteArray(padLen)
            random.nextBytes(noise)
            System.arraycopy(noise, 0, data, headerSize, padLen)
            FrostLogUtils.info("dex header obfuscation: %s filled %d padding bytes", dexFile.name, padLen)
        }
        return try {
            // SHA1 over [0x20, fileSize)
            val sha1 = MessageDigest.getInstance("SHA-1")
            sha1.update(data, 0x20, fileSize - 0x20)
            val sig = sha1.digest()
            System.arraycopy(sig, 0, data, 0x0C, 20)
            // Adler32 over [0x0C, fileSize)
            val adler = Adler32()
            adler.update(data, 0x0C, fileSize - 0x0C)
            writeLeInt(data, 0x08, adler.value.toInt())
            dexFile.writeBytes(data.copyOf(fileSize))
            FrostLogUtils.info("dex header obfuscation done: %s", dexFile.name)
            true
        } catch (e: Exception) {
            FrostLogUtils.warn("dex header obfuscation failed for %s: %s", dexFile.name, e.message)
            false
        }
    }

    // ===== 2. 类顺序打乱 =====
    // 仅打乱 class_def 排列顺序，类内部方法顺序不变。写入后自检，失败自动回滚。
    fun shuffleDexClasses(dexFile: File): Boolean {
        val dex = try {
            com.adfxcbnm.frostshell.util.FrostDexUtils.loadDexPreservingVersion(dexFile) as com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
        } catch (e: Exception) {
            FrostLogUtils.warn("dex class shuffle: load failed for %s: %s", dexFile.name, e.message)
            return false
        }
        val classList = ArrayList<ClassDef>(dex.classes)
        if (classList.size <= 1) return false
        Collections.shuffle(classList, random)
        val backup = File(dexFile.absolutePath + ".shuf_backup")
        return try {
            backup.writeBytes(dexFile.readBytes())
            DexFileFactory.writeDexFile(dexFile.absolutePath, RewrittenDexFile(dex.opcodes, classList))
            // 写入后自检
            try {
                DexFileFactory.loadDexFile(dexFile, Opcodes.getDefault())
                FrostLogUtils.info("dex class shuffle done: %s (%d classes)", dexFile.name, classList.size)
                true
            } catch (v: Exception) {
                dexFile.writeBytes(backup.readBytes())
                FrostLogUtils.warn("dex class shuffle verify failed, rolled back: %s (%s)", dexFile.name, v.message)
                false
            }
        } catch (e: Exception) {
            if (backup.exists()) {
                try {
                    dexFile.writeBytes(backup.readBytes())
                } catch (r: Throwable) {
                    // 回滚失败交由上层兜底
                }
            }
            FrostLogUtils.warn("dex class shuffle failed for %s: %s", dexFile.name, e.message)
            false
        } finally {
            backup.delete()
        }
    }

    // ===== 3. Debug 信息移除 =====
    // 移除方法实现的 debug items（行号/局部变量名/prologue 等），保留 try-block 与指令。
    fun stripDebugInfo(dexFile: File): Boolean {
        val dex = try {
            com.adfxcbnm.frostshell.util.FrostDexUtils.loadDexPreservingVersion(dexFile)
        } catch (e: Exception) {
            FrostLogUtils.warn("debug strip: load failed for %s: %s", dexFile.name, e.message)
            return false
        }
        // 说明：原 0xFFFF string pool 预检经实测（palm classes.dex=123031 写回通过）为过度保守，已移除。
        var stripped = 0
        val newClasses = ArrayList<ClassDef>(dex.classes.size)
        for (classDef in dex.classes) {
            if (com.adfxcbnm.frostshell.config.FrostProtectRules.matchRules(classDef.type)) {
                newClasses.add(classDef)
                continue
            }
            var classDirty = false
            val newDirect = ArrayList<Method>()
            for (m in classDef.directMethods) {
                val nm = stripMethodDebug(m)
                if (nm != null) {
                    newDirect.add(nm)
                    classDirty = true
                    stripped++
                } else {
                    newDirect.add(m)
                }
            }
            val newVirtual = ArrayList<Method>()
            for (m in classDef.virtualMethods) {
                val nm = stripMethodDebug(m)
                if (nm != null) {
                    newVirtual.add(nm)
                    classDirty = true
                    stripped++
                } else {
                    newVirtual.add(m)
                }
            }
            newClasses.add(if (classDirty) RewrittenClassDef(classDef, newDirect, newVirtual) else classDef)
        }
        if (stripped == 0) return false
        val backup = File(dexFile.absolutePath + ".debug_backup")
        return try {
            backup.writeBytes(dexFile.readBytes())
            DexFileFactory.writeDexFile(dexFile.absolutePath, RewrittenDexFile(dex.opcodes, newClasses))
            try {
                DexFileFactory.loadDexFile(dexFile, Opcodes.getDefault())
                FrostLogUtils.info("debug strip done: %s (%d methods)", dexFile.name, stripped)
                true
            } catch (v: Exception) {
                dexFile.writeBytes(backup.readBytes())
                FrostLogUtils.warn("debug strip verify failed, rolled back: %s (%s)", dexFile.name, v.message)
                false
            }
        } catch (e: Exception) {
            if (backup.exists()) {
                try {
                    dexFile.writeBytes(backup.readBytes())
                } catch (r: Throwable) {
                    // 忽略回滚失败
                }
            }
            FrostLogUtils.warn("debug strip failed for %s: %s", dexFile.name, e.message)
            false
        } finally {
            backup.delete()
        }
    }

    private fun stripMethodDebug(method: Method): Method? {
        val impl = method.implementation ?: return null
        if (impl.debugItems.none()) return null
        val insns = ArrayList<com.android.tools.smali.dexlib2.iface.instruction.Instruction>(impl.instructions.count())
        for (insn in impl.instructions) insns.add(ImmutableInstruction.of(insn))
        val newImpl = ImmutableMethodImplementation(impl.registerCount, insns, impl.tryBlocks, null)
        return ImmutableMethod(
            method.definingClass, method.name, method.parameters, method.returnType,
            method.accessFlags, method.annotations, method.hiddenApiRestrictions, newImpl
        )
    }

    // ===== 4. Goto 插入混淆 =====
    // 方法头插入 "goto +2, nop, nop"（共 3 code unit），原指令整体后移，分支相对偏移不变。
    // 原版 Ex 版错误使用 goto +3（会跳过原第 1 条指令），此处修正为 +2 保持语义不变。
    // 跳过：含 switch payload、abstract/native、保护类的方法。
    fun applyGotoInsertion(dexFile: File): Boolean {
        val dex = try {
            com.adfxcbnm.frostshell.util.FrostDexUtils.loadDexPreservingVersion(dexFile)
        } catch (e: Exception) {
            FrostLogUtils.warn("goto insertion: load failed for %s: %s", dexFile.name, e.message)
            return false
        }
        // 说明：原 0xFFFF string pool 预检经实测为过度保守，已移除；写回后 loadDexFile 校验兜底。
        var modified = 0
        val newClasses = ArrayList<ClassDef>(dex.classes.size)
        for (classDef in dex.classes) {
            if (com.adfxcbnm.frostshell.config.FrostProtectRules.matchRules(classDef.type)) {
                newClasses.add(classDef)
                continue
            }
            var classDirty = false
            val newDirect = ArrayList<Method>()
            for (m in classDef.directMethods) {
                val nm = gotoInsertionMethod(classDef.type, m)
                if (nm != null) {
                    newDirect.add(nm)
                    classDirty = true
                    modified++
                } else {
                    newDirect.add(m)
                }
            }
            val newVirtual = ArrayList<Method>()
            for (m in classDef.virtualMethods) {
                val nm = gotoInsertionMethod(classDef.type, m)
                if (nm != null) {
                    newVirtual.add(nm)
                    classDirty = true
                    modified++
                } else {
                    newVirtual.add(m)
                }
            }
            newClasses.add(if (classDirty) RewrittenClassDef(classDef, newDirect, newVirtual) else classDef)
        }
        if (modified == 0) return false
        val backup = File(dexFile.absolutePath + ".goto_backup")
        return try {
            backup.writeBytes(dexFile.readBytes())
            DexFileFactory.writeDexFile(dexFile.absolutePath, RewrittenDexFile(dex.opcodes, newClasses))
            try {
                DexFileFactory.loadDexFile(dexFile, Opcodes.getDefault())
                FrostLogUtils.info("goto insertion done: %s (%d methods)", dexFile.name, modified)
                true
            } catch (v: Exception) {
                dexFile.writeBytes(backup.readBytes())
                FrostLogUtils.warn("goto insertion verify failed, rolled back: %s (%s)", dexFile.name, v.message)
                false
            }
        } catch (e: Exception) {
            if (backup.exists()) {
                try {
                    dexFile.writeBytes(backup.readBytes())
                } catch (r: Throwable) {
                    // 忽略回滚失败
                }
            }
            FrostLogUtils.warn("goto insertion failed for %s: %s", dexFile.name, e.message)
            false
        } finally {
            backup.delete()
        }
    }

    private fun gotoInsertionMethod(type: String, method: Method): Method? {
        val impl = method.implementation ?: return null
        if (impl.instructions.none()) return null
        if ((method.accessFlags and 0x400) != 0 || (method.accessFlags and 0x100) != 0) return null
        for (insn in impl.instructions) {
            if (insn.opcode == Opcode.PACKED_SWITCH_PAYLOAD || insn.opcode == Opcode.SPARSE_SWITCH_PAYLOAD ||
                insn.opcode == Opcode.ARRAY_PAYLOAD
            ) return null
        }
        val newInsns = ArrayList<Instruction>(impl.instructions.count() + 3)
        newInsns.add(ImmutableInstruction10t(Opcode.GOTO, 2))
        newInsns.add(ImmutableInstruction10x(Opcode.NOP))
        newInsns.add(ImmutableInstruction10x(Opcode.NOP))
        for (insn in impl.instructions) newInsns.add(ImmutableInstruction.of(insn))
        val shiftedTb = shiftTryBlocksSimple(impl.tryBlocks, 3)
        val newImpl = ImmutableMethodImplementation(impl.registerCount, newInsns, shiftedTb, impl.debugItems)
        return ImmutableMethod(
            type, method.name, method.parameters, method.returnType,
            method.accessFlags, method.annotations, method.hiddenApiRestrictions, newImpl
        )
    }

    private fun shiftTryBlocksSimple(tryBlocks: List<TryBlock<out ExceptionHandler>>, shift: Int): List<TryBlock<out ExceptionHandler>> {
        val result = ArrayList<TryBlock<out ExceptionHandler>>(tryBlocks.size)
        for (tb in tryBlocks) {
            val newStart = tb.startCodeAddress + shift
            val newCount = tb.codeUnitCount
            if (newStart < 0 || newCount <= 0) continue
            val shiftedHandlers = ArrayList<ExceptionHandler>(tb.exceptionHandlers.size)
            for (h in tb.exceptionHandlers) {
                val newHandlerAddr = if (h.handlerCodeAddress + shift < 0) 0 else h.handlerCodeAddress + shift
                shiftedHandlers.add(ImmutableExceptionHandler(h.exceptionType, newHandlerAddr))
            }
            result.add(ImmutableTryBlock(newStart, newCount, shiftedHandlers))
        }
        return result
    }

    // ===== 5. 算术混淆 =====
    // 组合两个子 pass（顺序执行，各自独立改写方法体）：
    //  a) ADD_INT 序列替换：帧尾新增 tempReg，将前 15 条中的首个 ADD_INT 换成 5 条算术等价序列。
    //  b) 算术分支插入：帧尾新增 vT，方法头插入 "const vT,0; if-eqz vT; const vT,1; nop" 假分支。
    // 安全策略同原版：跳过含分支/switch payload/abstract/native/static(仅 a)/含 try 的方法。
    fun applyArithmeticObfuscation(dexFile: File, doAddReplace: Boolean = true, doBranchInsert: Boolean = true): Boolean {
        val dex = try {
            com.adfxcbnm.frostshell.util.FrostDexUtils.loadDexPreservingVersion(dexFile)
        } catch (e: Exception) {
            FrostLogUtils.warn("arithmetic obfuscation: load failed for %s: %s", dexFile.name, e.message)
            return false
        }
        // 说明：原 0xFFFF string pool 预检经实测为过度保守，已移除。
        var modified = 0
        val newClasses = ArrayList<ClassDef>(dex.classes.size)
        for (classDef in dex.classes) {
            if (com.adfxcbnm.frostshell.config.FrostProtectRules.matchRules(classDef.type)) {
                newClasses.add(classDef)
                continue
            }
            var classDirty = false
            val newDirect = ArrayList<Method>()
            for (m in classDef.directMethods) {
                val nm = arithmeticMethod(m, doAddReplace, doBranchInsert)
                if (nm != null) {
                    newDirect.add(nm)
                    classDirty = true
                    modified++
                } else {
                    newDirect.add(m)
                }
            }
            val newVirtual = ArrayList<Method>()
            for (m in classDef.virtualMethods) {
                val nm = arithmeticMethod(m, doAddReplace, doBranchInsert)
                if (nm != null) {
                    newVirtual.add(nm)
                    classDirty = true
                    modified++
                } else {
                    newVirtual.add(m)
                }
            }
            newClasses.add(if (classDirty) RewrittenClassDef(classDef, newDirect, newVirtual) else classDef)
        }
        if (modified == 0) return false
        val backup = File(dexFile.absolutePath + ".arith_backup")
        return try {
            backup.writeBytes(dexFile.readBytes())
            DexFileFactory.writeDexFile(dexFile.absolutePath, RewrittenDexFile(dex.opcodes, newClasses))
            try {
                DexFileFactory.loadDexFile(dexFile, Opcodes.getDefault())
                FrostLogUtils.info("arithmetic obfuscation done: %s (%d methods)", dexFile.name, modified)
                true
            } catch (v: Exception) {
                dexFile.writeBytes(backup.readBytes())
                FrostLogUtils.warn("arithmetic obfuscation verify failed, rolled back: %s (%s)", dexFile.name, v.message)
                false
            }
        } catch (e: Exception) {
            if (backup.exists()) {
                try {
                    dexFile.writeBytes(backup.readBytes())
                } catch (r: Throwable) {
                    // 忽略回滚失败
                }
            }
            FrostLogUtils.warn("arithmetic obfuscation failed for %s: %s", dexFile.name, e.message)
            false
        } finally {
            backup.delete()
        }
    }

    private fun arithmeticMethod(method: Method, doAddReplace: Boolean, doBranchInsert: Boolean): Method? {
        val impl = method.implementation ?: return null
        if (impl.instructions.none()) return null
        if ((method.accessFlags and 0x400) != 0 || (method.accessFlags and 0x100) != 0) return null
        if (impl.tryBlocks.isNotEmpty()) return null
        for (insn in impl.instructions) if (insn is OffsetInstruction) return null
        val orig = ArrayList<Instruction>(impl.instructions.count())
        for (insn in impl.instructions) orig.add(ImmutableInstruction.of(insn))

        val regCount = impl.registerCount
        var newInsns = orig
        var curRegCount = regCount

        if (doAddReplace && (method.accessFlags and 0x8) == 0) {
            // 非 static 才做 ADD_INT 替换（原版限制）
            val tempReg = curRegCount
            if (tempReg <= 0xFFFF) {
                val scanLimit = minOf(orig.size, 15)
                for (i in 0 until scanLimit) {
                    val insn = orig[i]
                    if (insn.opcode == Opcode.ADD_INT && insn is ThreeRegisterInstruction) {
                        val a = insn.registerA
                        val b = insn.registerB
                        val c = insn.registerC
                        if (a <= 255 && b <= 255 && c <= 255) {
                            val replacement = ArrayList<Instruction>(5)
                            replacement.add(makeConst(tempReg, 1))
                            replacement.add(ImmutableInstruction23x(Opcode.ADD_INT, tempReg, c, tempReg))
                            replacement.add(ImmutableInstruction23x(Opcode.ADD_INT, a, b, tempReg))
                            replacement.add(makeConst(tempReg, -1))
                            replacement.add(ImmutableInstruction23x(Opcode.ADD_INT, a, a, tempReg))
                            val rebuilt = ArrayList<Instruction>(orig.size + 4)
                            for (j in 0 until orig.size) {
                                if (j == i) rebuilt.addAll(replacement) else rebuilt.add(orig[j])
                            }
                            newInsns = rebuilt
                            curRegCount = tempReg + 1
                            break
                        }
                    }
                }
            }
        }

        if (doBranchInsert) {
            val freeReg = regCount - paramRegisterCount(method)
            if (freeReg >= 1) {
                val vT = curRegCount
                if (vT <= 0xFFFF) {
                    val const0 = makeConst(vT, 0)
                    val const1 = makeConst(vT, 1)
                    val insertLen = const0.codeUnits + 2 + const1.codeUnits + 1
                    val ifOffset = 2 + const1.codeUnits + 1
                    val newRegCount = vT + 1
                    // 寄存器帧顶部新增 vT 后，Dalvik 会按 ins_start = registers_size - ins_size 重新锚定
                    // 参数寄存器：原 body 中对参数寄存器的绝对索引全部错位（参数被推高 delta 槽）。
                    // 必须在头部插入参数搬移（先搬移、后写 vT），把参数从新位置复制回原位置，
                    // 否则运行期取到错误寄存器 → 空指针/垃圾值（v9.10.30 SIGSEGV pc=0 根因）。
                    val moves = buildParamMoves(method, regCount, newRegCount)
                    val head = ArrayList<Instruction>(4 + moves.size)
                    head.addAll(moves)
                    head.add(const0)
                    head.add(ImmutableInstruction21t(Opcode.IF_EQZ, vT, ifOffset))
                    head.add(const1)
                    head.add(ImmutableInstruction10x(Opcode.NOP))
                    val finalInsns = ArrayList<Instruction>(head.size + newInsns.size)
                    finalInsns.addAll(head)
                    for (insn in newInsns) finalInsns.add(ImmutableInstruction.of(insn))
                    val shiftedTb = shiftTryBlocksSimple(impl.tryBlocks, insertLen)
                    return ImmutableMethod(
                        method.definingClass, method.name, method.parameters, method.returnType,
                        method.accessFlags, method.annotations, method.hiddenApiRestrictions,
                        ImmutableMethodImplementation(newRegCount, finalInsns, shiftedTb, impl.debugItems)
                    )
                }
            }
        }
        if (curRegCount != regCount) {
            // 仅 ADD_INT 替换生效：寄存器数 +1，无分支头插入，try 块不变（已排除含 try 的方法）。
            // 与分支路径同理，参数被推高 1 槽，需在头部搬移回原位。
            val moves = buildParamMoves(method, regCount, curRegCount)
            val finalInsns = ArrayList<Instruction>(moves.size + newInsns.size)
            finalInsns.addAll(moves)
            finalInsns.addAll(newInsns)
            return ImmutableMethod(
                method.definingClass, method.name, method.parameters, method.returnType,
                method.accessFlags, method.annotations, method.hiddenApiRestrictions,
                ImmutableMethodImplementation(curRegCount, finalInsns, impl.tryBlocks, impl.debugItems)
            )
        }
        return null
    }

    /**
     * 构建参数搬移指令：寄存器帧顶部新增 delta 个寄存器后，参数寄存器整体上移 delta 槽。
     * 按 Dalvik 布局从低位到高位依次把参数复制回原位置（dst=origRegCount-paramSlots+slot，
     * src=origRegCount+delta-paramSlots+slot），保证后续 body 对参数寄存器的绝对索引引用仍有效。
     * 逐槽升序搬移是安全的：src 严格大于所有已写入的 dst，不会读到被覆盖的值。
     * this 计 1 槽（非 static），J/D 宽参数计 2 槽（move-wide/16）。
     *
     * 指令选择必须区分类型：引用（this、L 前缀、数组前缀参数）用 MOVE_OBJECT_16，int/float 用 MOVE_16，
     * 宽用 MOVE_WIDE_16。ART 的 VerifyCopyCat1 只接受 cat1 int/float/缓存引用，
     * 对精确引用类型（如 this 的具体类）用 move 会直接 VerifyError
     * （报错形如 "Verifier rejected class ... copy-cat1 vX<-vY type=Reference: <class>"），
     * 必须走 VerifyCopyReference 的 move-object 系列。
     */
    private fun buildParamMoves(method: Method, origRegCount: Int, newRegCount: Int): List<Instruction> {
        if (newRegCount <= origRegCount) return emptyList()
        val paramSlots = paramRegisterCount(method)
        if (paramSlots == 0) return emptyList()
        val delta = newRegCount - origRegCount
        val moves = ArrayList<Instruction>()
        var slot = 0
        if ((method.accessFlags and 0x8) == 0) {
            moves.add(ImmutableInstruction32x(Opcode.MOVE_OBJECT_16, origRegCount - paramSlots + slot, origRegCount + delta - paramSlots + slot))
            slot++
        }
        for (paramType in method.parameters) {
            val wide = paramType.isNotEmpty() && (paramType[0] == 'J' || paramType[0] == 'D')
            val ref = paramType.isNotEmpty() && (paramType[0] == 'L' || paramType[0] == '[')
            if (wide) {
                moves.add(ImmutableInstruction32x(Opcode.MOVE_WIDE_16, origRegCount - paramSlots + slot, origRegCount + delta - paramSlots + slot))
                slot += 2
            } else if (ref) {
                moves.add(ImmutableInstruction32x(Opcode.MOVE_OBJECT_16, origRegCount - paramSlots + slot, origRegCount + delta - paramSlots + slot))
                slot++
            } else {
                moves.add(ImmutableInstruction32x(Opcode.MOVE_16, origRegCount - paramSlots + slot, origRegCount + delta - paramSlots + slot))
                slot++
            }
        }
        return moves
    }

    private fun paramRegisterCount(method: Method): Int {
        var count = 0
        if ((method.accessFlags and 0x8) == 0) count++
        for (paramType in method.parameters) {
            count += if (paramType.isNotEmpty() && (paramType[0] == 'J' || paramType[0] == 'D')) 2 else 1
        }
        return count
    }

    private fun makeConst(register: Int, value: Int): Instruction {
        return if (register <= 15 && value >= -8 && value <= 7) {
            ImmutableInstruction11n(Opcode.CONST_4, register, value)
        } else if (register <= 255 && value >= -32768 && value <= 32767) {
            ImmutableInstruction21s(Opcode.CONST_16, register, value)
        } else {
            ImmutableInstruction31i(Opcode.CONST, register, value)
        }
    }

    // ===== 6. 控制流混淆 =====
    // 方法头插入 "goto +2, nop, nop"，打破线性指令流。条件：insnCount>10、无 switch/array payload、
    // 有局部寄存器。原版使用 goto +3 会跳过原第 1 条指令（语义破坏），此处修正为 +2。
    fun applyControlFlow(dexFile: File): Boolean {
        val dex = try {
            com.adfxcbnm.frostshell.util.FrostDexUtils.loadDexPreservingVersion(dexFile)
        } catch (e: Exception) {
            FrostLogUtils.warn("control flow: load failed for %s: %s", dexFile.name, e.message)
            return false
        }
        // 说明：原 0xFFFF string pool 预检经实测为过度保守，已移除。
        var modified = 0
        val newClasses = ArrayList<ClassDef>(dex.classes.size)
        for (classDef in dex.classes) {
            if (com.adfxcbnm.frostshell.config.FrostProtectRules.matchRules(classDef.type)) {
                newClasses.add(classDef)
                continue
            }
            var classDirty = false
            val newDirect = ArrayList<Method>()
            for (m in classDef.directMethods) {
                val nm = controlFlowMethod(classDef.type, m)
                if (nm != null) {
                    newDirect.add(nm)
                    classDirty = true
                    modified++
                } else {
                    newDirect.add(m)
                }
            }
            val newVirtual = ArrayList<Method>()
            for (m in classDef.virtualMethods) {
                val nm = controlFlowMethod(classDef.type, m)
                if (nm != null) {
                    newVirtual.add(nm)
                    classDirty = true
                    modified++
                } else {
                    newVirtual.add(m)
                }
            }
            newClasses.add(if (classDirty) RewrittenClassDef(classDef, newDirect, newVirtual) else classDef)
        }
        if (modified == 0) return false
        val backup = File(dexFile.absolutePath + ".cf_backup")
        return try {
            backup.writeBytes(dexFile.readBytes())
            DexFileFactory.writeDexFile(dexFile.absolutePath, RewrittenDexFile(dex.opcodes, newClasses))
            try {
                DexFileFactory.loadDexFile(dexFile, Opcodes.getDefault())
                FrostLogUtils.info("control flow done: %s (%d methods)", dexFile.name, modified)
                true
            } catch (v: Exception) {
                dexFile.writeBytes(backup.readBytes())
                FrostLogUtils.warn("control flow verify failed, rolled back: %s (%s)", dexFile.name, v.message)
                false
            }
        } catch (e: Exception) {
            if (backup.exists()) {
                try {
                    dexFile.writeBytes(backup.readBytes())
                } catch (r: Throwable) {
                    // 忽略回滚失败
                }
            }
            FrostLogUtils.warn("control flow failed for %s: %s", dexFile.name, e.message)
            false
        } finally {
            backup.delete()
        }
    }

    private fun controlFlowMethod(type: String, method: Method): Method? {
        val impl = method.implementation ?: return null
        var insnCount = 0
        for (insn in impl.instructions) {
            insnCount++
            if (insn.opcode == Opcode.PACKED_SWITCH_PAYLOAD || insn.opcode == Opcode.SPARSE_SWITCH_PAYLOAD ||
                insn.opcode == Opcode.ARRAY_PAYLOAD
            ) return null
        }
        if (insnCount <= 10) return null
        if (insnCount > 0xFFFF) return null
        val numLocalRegs = impl.registerCount - paramRegisterCount(method)
        if (numLocalRegs <= 0) return null

        val newInsns = ArrayList<Instruction>(insnCount + 3)
        newInsns.add(ImmutableInstruction10t(Opcode.GOTO, 2))
        newInsns.add(ImmutableInstruction10x(Opcode.NOP))
        newInsns.add(ImmutableInstruction10x(Opcode.NOP))
        for (insn in impl.instructions) newInsns.add(ImmutableInstruction.of(insn))
        val shiftedTb = shiftTryBlocksSimple(impl.tryBlocks, 3)
        val newImpl = ImmutableMethodImplementation(impl.registerCount, newInsns, shiftedTb, impl.debugItems)
        return ImmutableMethod(
            type, method.name, method.parameters, method.returnType,
            method.accessFlags, method.annotations, method.hiddenApiRestrictions, newImpl
        )
    }

    // ===== 7. 调用间接化 =====
    // 与 controlFlow 相同的方法头 goto 混淆结构，条件放宽为 insnCount>=10（不要求局部寄存器），
    // 与 gotoInsertion 的差异仅在资格判定。原版同样存在 goto +3 偏移 bug，此处修正为 +2。
    fun applyCallIndirection(dexFile: File): Boolean {
        val dex = try {
            com.adfxcbnm.frostshell.util.FrostDexUtils.loadDexPreservingVersion(dexFile)
        } catch (e: Exception) {
            FrostLogUtils.warn("call indirection: load failed for %s: %s", dexFile.name, e.message)
            return false
        }
        // 说明：原 0xFFFF string pool 预检经实测为过度保守，已移除。
        var modified = 0
        val newClasses = ArrayList<ClassDef>(dex.classes.size)
        for (classDef in dex.classes) {
            if (com.adfxcbnm.frostshell.config.FrostProtectRules.matchRules(classDef.type)) {
                newClasses.add(classDef)
                continue
            }
            var classDirty = false
            val newDirect = ArrayList<Method>()
            for (m in classDef.directMethods) {
                val nm = callIndirectionMethod(classDef.type, m)
                if (nm != null) {
                    newDirect.add(nm)
                    classDirty = true
                    modified++
                } else {
                    newDirect.add(m)
                }
            }
            val newVirtual = ArrayList<Method>()
            for (m in classDef.virtualMethods) {
                val nm = callIndirectionMethod(classDef.type, m)
                if (nm != null) {
                    newVirtual.add(nm)
                    classDirty = true
                    modified++
                } else {
                    newVirtual.add(m)
                }
            }
            newClasses.add(if (classDirty) RewrittenClassDef(classDef, newDirect, newVirtual) else classDef)
        }
        if (modified == 0) return false
        val backup = File(dexFile.absolutePath + ".ci_backup")
        return try {
            backup.writeBytes(dexFile.readBytes())
            DexFileFactory.writeDexFile(dexFile.absolutePath, RewrittenDexFile(dex.opcodes, newClasses))
            try {
                DexFileFactory.loadDexFile(dexFile, Opcodes.getDefault())
                FrostLogUtils.info("call indirection done: %s (%d methods)", dexFile.name, modified)
                true
            } catch (v: Exception) {
                dexFile.writeBytes(backup.readBytes())
                FrostLogUtils.warn("call indirection verify failed, rolled back: %s (%s)", dexFile.name, v.message)
                false
            }
        } catch (e: Exception) {
            if (backup.exists()) {
                try {
                    dexFile.writeBytes(backup.readBytes())
                } catch (r: Throwable) {
                    // 忽略回滚失败
                }
            }
            FrostLogUtils.warn("call indirection failed for %s: %s", dexFile.name, e.message)
            false
        } finally {
            backup.delete()
        }
    }

    private fun callIndirectionMethod(type: String, method: Method): Method? {
        val impl = method.implementation ?: return null
        if ((method.accessFlags and 0x400) != 0 || (method.accessFlags and 0x100) != 0) return null
        var insnCount = 0
        for (insn in impl.instructions) {
            insnCount++
            if (insn.opcode == Opcode.PACKED_SWITCH_PAYLOAD || insn.opcode == Opcode.SPARSE_SWITCH_PAYLOAD ||
                insn.opcode == Opcode.ARRAY_PAYLOAD
            ) return null
        }
        if (insnCount < 10 || insnCount > 0xFFFF) return null
        val newInsns = ArrayList<Instruction>(insnCount + 3)
        newInsns.add(ImmutableInstruction10t(Opcode.GOTO, 2))
        newInsns.add(ImmutableInstruction10x(Opcode.NOP))
        newInsns.add(ImmutableInstruction10x(Opcode.NOP))
        for (insn in impl.instructions) newInsns.add(ImmutableInstruction.of(insn))
        val shiftedTb = shiftTryBlocksSimple(impl.tryBlocks, 3)
        val newImpl = ImmutableMethodImplementation(impl.registerCount, newInsns, shiftedTb, impl.debugItems)
        return ImmutableMethod(
            type, method.name, method.parameters, method.returnType,
            method.accessFlags, method.annotations, method.hiddenApiRestrictions, newImpl
        )
    }

    // ===== 8. 方法重载混淆 =====
    // 为每个类添加 1-2 个 dummy 方法：方法名复用已有方法（跳过 <init>/<clinit>），
    // 参数为一个 int，返回 void，方法体 return-void。签名含返回类型避免重复定义冲突。
    fun applyMethodOverload(dexFile: File): Boolean {
        val dex = try {
            com.adfxcbnm.frostshell.util.FrostDexUtils.loadDexPreservingVersion(dexFile)
        } catch (e: Exception) {
            FrostLogUtils.warn("method overload: load failed for %s: %s", dexFile.name, e.message)
            return false
        }
        // 说明：原 0xFFFF string pool 预检经实测为过度保守，已移除。
        var added = 0
        val newClasses = ArrayList<ClassDef>(dex.classes.size)
        for (classDef in dex.classes) {
            if (com.adfxcbnm.frostshell.config.FrostProtectRules.matchRules(classDef.type)) {
                newClasses.add(classDef)
                continue
            }
            val existingSigs = HashSet<String>()
            val candidateNames = ArrayList<String>()
            for (m in classDef.methods) {
                val sb = StringBuilder(m.name).append("(")
                for (p in m.parameters) sb.append(p)
                sb.append(")").append(m.returnType)
                existingSigs.add(sb.toString())
                if (m.name != "<init>" && m.name != "<clinit>" && !candidateNames.contains(m.name)) {
                    candidateNames.add(m.name)
                }
            }
            if (candidateNames.isEmpty()) {
                newClasses.add(classDef)
                continue
            }
            val numToAdd = 1 + random.nextInt(2)
            var classAdded = 0
            var attempts = 0
            val dummyList = ArrayList<Method>()
            while (classAdded < numToAdd && attempts < candidateNames.size * 3) {
                attempts++
                val name = candidateNames[random.nextInt(candidateNames.size)]
                val sig = name + "(I)V"
                if (existingSigs.contains(sig)) continue
                existingSigs.add(sig)
                val dummyImpl = ImmutableMethodImplementation(
                    1,
                    listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)),
                    emptyList(),
                    emptyList()
                )
                dummyList.add(
                    ImmutableMethod(
                        classDef.type, name,
                        listOf(com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter("I", emptySet(), null)),
                        "V",
                        0x0001 or 0x0008,
                        emptySet(), emptySet(), dummyImpl
                    )
                )
                classAdded++
                added++
            }
            if (classAdded > 0) {
                val newDirect = ArrayList<Method>()
                for (m in classDef.directMethods) newDirect.add(m)
                newDirect.addAll(dummyList)
                val newVirtual = ArrayList<Method>()
                for (m in classDef.virtualMethods) newVirtual.add(m)
                newClasses.add(RewrittenClassDef(classDef, newDirect, newVirtual))
            } else {
                newClasses.add(classDef)
            }
        }
        if (added == 0) return false
        val backup = File(dexFile.absolutePath + ".overload_backup")
        return try {
            backup.writeBytes(dexFile.readBytes())
            DexFileFactory.writeDexFile(dexFile.absolutePath, RewrittenDexFile(dex.opcodes, newClasses))
            try {
                DexFileFactory.loadDexFile(dexFile, Opcodes.getDefault())
                FrostLogUtils.info("method overload done: %s (%d dummy methods)", dexFile.name, added)
                true
            } catch (v: Exception) {
                dexFile.writeBytes(backup.readBytes())
                FrostLogUtils.warn("method overload verify failed, rolled back: %s (%s)", dexFile.name, v.message)
                false
            }
        } catch (e: Exception) {
            if (backup.exists()) {
                try {
                    dexFile.writeBytes(backup.readBytes())
                } catch (r: Throwable) {
                    // 忽略回滚失败
                }
            }
            FrostLogUtils.warn("method overload failed for %s: %s", dexFile.name, e.message)
            false
        } finally {
            backup.delete()
        }
    }

    // ===== 9. 字段重命名 =====
    // 精确重写字段定义 + 所有指令中的 FieldReference + annotation 中的枚举引用。
    // 相对原版二进制字符串替换方案：不受字符串跨 table 共享影响（字段名/方法名共用 string 条目
    // 会被连带改名的缺陷），引用定位精确；且 DexPool 自动重算 SHA-1/checksum。
    // 跳过：受保护类、含 native 方法的类、serialVersionUID、带 annotation 的字段、synthetic、enum。
    private class FieldKey(val cls: String, val name: String, val type: String) {
        override fun hashCode(): Int = cls.hashCode() * 31 * 31 + name.hashCode() * 31 + type.hashCode()
        override fun equals(other: Any?): Boolean =
            other is FieldKey && other.cls == cls && other.name == name && other.type == type
    }

    fun applyFieldRename(dexFile: File, protectedClasses: Set<String> = emptySet()): Boolean {
        val dex = try {
            com.adfxcbnm.frostshell.util.FrostDexUtils.loadDexPreservingVersion(dexFile)
        } catch (e: Exception) {
            FrostLogUtils.warn("field rename: load failed for %s: %s", dexFile.name, e.message)
            return false
        }
        // 说明：原 0xFFFF string pool 预检经实测为过度保守，已移除。
        val nativeClasses = HashSet<String>()
        for (cd in dex.classes) {
            for (m in cd.methods) {
                if ((m.accessFlags and 0x100) != 0) {
                    nativeClasses.add(cd.type)
                    break
                }
            }
        }
        val fieldMap = HashMap<FieldKey, String>()
        val usedNames = HashSet<String>()
        for (cd in dex.classes) {
            if (com.adfxcbnm.frostshell.config.FrostProtectRules.matchRules(cd.type)) continue
            if (cd.type in nativeClasses) continue
            if (isProtectedClass(cd.type, protectedClasses)) continue
            for (f in cd.fields) {
                if (f.name == "serialVersionUID") continue
                if (f.annotations.any()) continue
                if ((f.accessFlags and 0x1000) != 0) continue
                if ((f.accessFlags and 0x4000) != 0) continue
                var newName = com.adfxcbnm.frostshell.config.FrostNameDictionary.genFieldIdentifier(usedNames)
                    ?: genRandomName(3 + random.nextInt(5))
                var guard = 0
                while (usedNames.contains(newName) && guard++ < 8) {
                    newName = com.adfxcbnm.frostshell.config.FrostNameDictionary.genFieldIdentifier(usedNames)
                        ?: genRandomName(3 + random.nextInt(5))
                }
                usedNames.add(newName)
                fieldMap[FieldKey(cd.type, f.name, f.type)] = newName
            }
        }
        if (fieldMap.isEmpty()) return false

        var renamedFields = 0
        var touchedMethods = 0
        val newClasses = ArrayList<ClassDef>(dex.classes.size)
        for (cd in dex.classes) {
            var fieldsDirty = false
            val newStatic = ArrayList<Field>()
            for (f in cd.staticFields) {
                val n = fieldMap[FieldKey(cd.type, f.name, f.type)]
                if (n != null) {
                    newStatic.add(ImmutableField(cd.type, n, f.type, f.accessFlags, f.initialValue, f.annotations, f.hiddenApiRestrictions))
                    fieldsDirty = true
                    renamedFields++
                } else {
                    newStatic.add(f)
                }
            }
            val newInstance = ArrayList<Field>()
            for (f in cd.instanceFields) {
                val n = fieldMap[FieldKey(cd.type, f.name, f.type)]
                if (n != null) {
                    newInstance.add(ImmutableField(cd.type, n, f.type, f.accessFlags, f.initialValue, f.annotations, f.hiddenApiRestrictions))
                    fieldsDirty = true
                    renamedFields++
                } else {
                    newInstance.add(f)
                }
            }
            var methodsDirty = false
            val newDirect = ArrayList<Method>()
            for (m in cd.directMethods) {
                val nm = rewriteFieldRefMethod(m, fieldMap)
                if (nm != null) {
                    newDirect.add(nm)
                    methodsDirty = true
                    touchedMethods++
                } else {
                    newDirect.add(m)
                }
            }
            val newVirtual = ArrayList<Method>()
            for (m in cd.virtualMethods) {
                val nm = rewriteFieldRefMethod(m, fieldMap)
                if (nm != null) {
                    newVirtual.add(nm)
                    methodsDirty = true
                    touchedMethods++
                } else {
                    newVirtual.add(m)
                }
            }
            val newClassAnno = remapAnnotationSet(cd.annotations, fieldMap)
            val annoDirty = newClassAnno !== cd.annotations
            if (fieldsDirty || methodsDirty || annoDirty) {
                newClasses.add(
                    RewrittenClassDef(
                        cd, newDirect, newVirtual,
                        if (fieldsDirty) newStatic else null,
                        if (fieldsDirty) newInstance else null,
                        if (annoDirty) newClassAnno else null
                    )
                )
            } else {
                newClasses.add(cd)
            }
        }
        if (renamedFields == 0 && touchedMethods == 0) return false
        val backup = File(dexFile.absolutePath + ".field_backup")
        return try {
            backup.writeBytes(dexFile.readBytes())
            DexFileFactory.writeDexFile(dexFile.absolutePath, RewrittenDexFile(dex.opcodes, newClasses))
            try {
                DexFileFactory.loadDexFile(dexFile, Opcodes.getDefault())
                FrostLogUtils.info(
                    "field rename done: %s (%d fields, %d methods touched)", dexFile.name,
                    renamedFields, touchedMethods
                )
                true
            } catch (v: Exception) {
                dexFile.writeBytes(backup.readBytes())
                FrostLogUtils.warn("field rename verify failed, rolled back: %s (%s)", dexFile.name, v.message)
                false
            }
        } catch (e: Exception) {
            if (backup.exists()) {
                try {
                    dexFile.writeBytes(backup.readBytes())
                } catch (r: Throwable) {
                    // 忽略回滚失败
                }
            }
            FrostLogUtils.warn("field rename failed for %s: %s", dexFile.name, e.message)
            false
        } finally {
            backup.delete()
        }
    }

    private fun rewriteFieldRefMethod(method: Method, fieldMap: Map<FieldKey, String>): Method? {
        var dirty = false
        val newAnno = remapAnnotationSet(method.annotations, fieldMap)
        if (newAnno !== method.annotations) dirty = true
        var newImpl: com.android.tools.smali.dexlib2.iface.MethodImplementation? = null
        val impl = method.implementation
        if (impl != null) {
            var implDirty = false
            val newInsns = ArrayList<Instruction>(impl.instructions.count())
            for (insn in impl.instructions) {
                if (insn is ReferenceInstruction) {
                    val ref = insn.reference
                    if (ref is FieldReference) {
                        val n = fieldMap[FieldKey(ref.definingClass, ref.name, ref.type)]
                        if (n != null) {
                            val newRef = ImmutableFieldReference(ref.definingClass, n, ref.type)
                            newInsns.add(replaceFieldReferenceInstruction(insn, newRef))
                            implDirty = true
                            continue
                        }
                    }
                }
                newInsns.add(ImmutableInstruction.of(insn))
            }
            if (implDirty) {
                newImpl = ImmutableMethodImplementation(impl.registerCount, newInsns, impl.tryBlocks, impl.debugItems)
                dirty = true
            }
        }
        if (!dirty) return null
        return ImmutableMethod(
            method.definingClass, method.name, method.parameters, method.returnType,
            method.accessFlags, newAnno, method.hiddenApiRestrictions, newImpl ?: impl
        )
    }

    private fun replaceFieldReferenceInstruction(insn: Instruction, newRef: FieldReference): Instruction {
        val factory = ImmutableInstructionFactory.INSTANCE
        return when {
            insn is TwoRegisterInstruction ->
                factory.makeInstruction22c(insn.opcode, insn.registerA, insn.registerB, newRef)
            insn is OneRegisterInstruction ->
                factory.makeInstruction21c(insn.opcode, insn.registerA, newRef)
            else -> ImmutableInstruction.of(insn)
        }
    }

    private fun remapAnnotationSet(annotations: Set<Annotation>, fieldMap: Map<FieldKey, String>): Set<Annotation> {
        var dirty = false
        val out = LinkedHashSet<Annotation>(annotations.size)
        for (a in annotations) {
            var elDirty = false
            val newElements = ArrayList<AnnotationElement>(a.elements.size)
            for (el in a.elements) {
                val nv = remapEncodedValue(el.value, fieldMap)
                if (nv !== el.value) {
                    newElements.add(ImmutableAnnotationElement(el.name, nv))
                    elDirty = true
                } else {
                    newElements.add(el)
                }
            }
            if (elDirty) {
                out.add(ImmutableAnnotation(a.visibility, a.type, newElements))
                dirty = true
            } else {
                out.add(a)
            }
        }
        return if (dirty) out else annotations
    }

    private fun remapEncodedValue(v: EncodedValue, fieldMap: Map<FieldKey, String>): EncodedValue {
        return when (v) {
            is EnumEncodedValue -> {
                val ref = v.value
                val n = fieldMap[FieldKey(ref.definingClass, ref.name, ref.type)]
                if (n != null) ImmutableEnumEncodedValue(ImmutableFieldReference(ref.definingClass, n, ref.type)) else v
            }
            is AnnotationEncodedValue -> {
                var dirty = false
                val newElements = ArrayList<AnnotationElement>(v.elements.size)
                for (el in v.elements) {
                    val nv = remapEncodedValue(el.value, fieldMap)
                    if (nv !== el.value) {
                        newElements.add(ImmutableAnnotationElement(el.name, nv))
                        dirty = true
                    } else {
                        newElements.add(el)
                    }
                }
                if (dirty) ImmutableAnnotationEncodedValue(v.type, newElements) else v
            }
            is ArrayEncodedValue -> {
                var dirty = false
                val newValues = ArrayList<EncodedValue>(v.value.size)
                for (item in v.value) {
                    val nv = remapEncodedValue(item, fieldMap)
                    if (nv !== item) dirty = true
                    newValues.add(nv)
                }
                if (dirty) ImmutableArrayEncodedValue(newValues) else v
            }
            else -> v
        }
    }

    private fun genRandomName(length: Int): String {
        val sb = StringBuilder(length)
        sb.append(NAME_CHARS[random.nextInt(NAME_CHARS.length)])
        for (i in 1 until length) {
            sb.append(NAME_CHARS_EXT[random.nextInt(NAME_CHARS_EXT.length)])
        }
        return sb.toString()
    }

    fun isProtectedClass(type: String, protectedClasses: Set<String>): Boolean {
        for (p in protectedClasses) {
            if (p.endsWith("/")) {
                if (type.startsWith(p)) return true
            } else if (type == p) {
                return true
            }
        }
        return false
    }

    // ===== 工具 =====
    private fun readLeInt(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun writeLeInt(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value and 0xFF).toByte()
        data[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        data[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        data[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }
}
