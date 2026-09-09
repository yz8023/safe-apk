package com.adfxcbnm.frostshell.dex

import com.adfxcbnm.frostshell.util.FrostLogUtils
import com.adfxcbnm.frostshell.util.FrostDexUtils
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.Annotation
import com.android.tools.smali.dexlib2.iface.AnnotationElement
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.ExceptionHandler
import com.android.tools.smali.dexlib2.iface.Field
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.TryBlock
import com.android.tools.smali.dexlib2.iface.reference.CallSiteReference
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodHandleReference
import com.android.tools.smali.dexlib2.iface.reference.MethodProtoReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.iface.value.AnnotationEncodedValue
import com.android.tools.smali.dexlib2.iface.value.ArrayEncodedValue
import com.android.tools.smali.dexlib2.iface.value.EncodedValue
import com.android.tools.smali.dexlib2.iface.value.EnumEncodedValue
import com.android.tools.smali.dexlib2.iface.value.FieldEncodedValue
import com.android.tools.smali.dexlib2.iface.value.MethodEncodedValue
import com.android.tools.smali.dexlib2.iface.value.TypeEncodedValue
import com.android.tools.smali.dexlib2.immutable.ImmutableAnnotation
import com.android.tools.smali.dexlib2.immutable.ImmutableAnnotationElement
import com.android.tools.smali.dexlib2.immutable.ImmutableExceptionHandler
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.ImmutableTryBlock
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction31c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction3rc
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction45cc
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction4rcc
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableCallSiteReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodHandleReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodProtoReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import com.android.tools.smali.dexlib2.immutable.value.ImmutableAnnotationEncodedValue
import com.android.tools.smali.dexlib2.immutable.value.ImmutableArrayEncodedValue
import com.android.tools.smali.dexlib2.immutable.value.ImmutableEnumEncodedValue
import com.android.tools.smali.dexlib2.immutable.value.ImmutableFieldEncodedValue
import com.android.tools.smali.dexlib2.immutable.value.ImmutableMethodEncodedValue
import com.android.tools.smali.dexlib2.immutable.value.ImmutableTypeEncodedValue
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import java.io.File
import java.security.SecureRandom
import java.util.LinkedHashSet

/**
 * 类名混淆（跨 dex 全局重命名，移植自 ArkProtector DexObfuscator.applyClassRename）。
 *
 * 核心思路：
 *  - 先扫描全量 dex 收集候选类名，构建全局 classRenameMap（旧类名 -> 新类名）；
 *  - 跳过系统类、受保护前缀、FrostProtectRules 命中类、含字符串常量引用的类
 *    （Class.forName 等反射路径不能用 dexlib2 引用重写覆盖）；
 *  - 对每个 dex 重写：被重命名的类改自身类名，其余引用了被重命名类的类同步更新
 *    superclass/interfaces/字段类型与方法 owner/参数/返回类型/注解/指令引用。
 *
 * 写入后立即 loadDexFile 自检，失败自动回滚，与原文件级 pass 行为一致。
 * 不使用 ImmutableClassDef 整类深拷贝（大 dex OOM），沿用 RewrittenClassDef 委托。
 */
object FrostClassRenamer {

    private val random = SecureRandom()

    // 与 ArkProtector 保持一致的系统/框架前缀，任何 prefix 命中即不重命名
    private val PROTECTED_PREFIXES = arrayOf(
        "Landroid/", "Landroidx/", "Ljava/", "Lkotlin/",
        "Lcom/google/", "Lcom/android/", "Lorg/", "Lcom/ark/",
        "Lcom/squareup/", "Lretrofit2/", "Lokhttp3/", "Lcom/bumptech/glide/",
        "Lcom/facebook/", "Lcom/baseflow/", "Ldev/fluttercommunity/"
    )

    private val NAME_CHARS = "abcdefghijklmnopqrstuvwxyz"
    private val NAME_CHARS_EXT = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_"

    private fun shouldRename(type: String, protectedSet: Set<String>): Boolean {
        for (p in PROTECTED_PREFIXES) if (type.startsWith(p)) return false
        for (p in protectedSet) {
            if (p.endsWith("/")) {
                if (type.startsWith(p)) return false
            } else {
                if (type == p) return false
            }
        }
        if (com.adfxcbnm.frostshell.config.FrostProtectRules.matchRules(type)) return false
        return true
    }

    private fun tryConvertToDescriptor(str: String): String? {
        if (str.isEmpty()) return null
        if (str.contains(" ") || str.contains("/") || str.contains("\\")) return null
        val dotIndex = str.indexOf('.')
        if (dotIndex <= 0) return null
        val parts = str.split(".")
        if (parts.size < 2) return null
        for (part in parts) {
            if (part.isEmpty()) return null
            val first = part[0]
            if (!Character.isJavaIdentifierStart(first)) return null
            for (i in 1 until part.length) {
                if (!Character.isJavaIdentifierPart(part[i])) return null
            }
        }
        return "L" + str.replace('.', '/') + ";"
    }

    private fun genClassName(used: MutableSet<String>): String {
        val segs = 2 + random.nextInt(3)
        val sb = StringBuilder("L")
        for (s in 0 until segs) {
            if (s > 0) sb.append("/")
            val len = 3 + random.nextInt(8)
            sb.append(NAME_CHARS[random.nextInt(NAME_CHARS.length)])
            for (i in 1 until len) {
                sb.append(NAME_CHARS_EXT[random.nextInt(NAME_CHARS_EXT.length)])
            }
        }
        sb.append(";")
        val name = sb.toString()
        if (used.contains(name)) return genClassName(used)
        used.add(name)
        return name
    }

    private fun remapType(type: String?, classRenameMap: Map<String, String>): String? {
        if (type == null) return null
        if (type.startsWith("[")) return "[" + remapType(type.substring(1), classRenameMap)
        return classRenameMap[type] ?: type
    }

    private fun remapFieldRef(fr: FieldReference, classRenameMap: Map<String, String>): FieldReference {
        val (newDef, newType) = remapType(fr.definingClass, classRenameMap) to remapType(fr.type, classRenameMap)
        if (newDef == fr.definingClass && newType == fr.type) return fr
        return ImmutableFieldReference(newDef!!, fr.name, newType!!)
    }

    private fun remapMethodRef(mr: MethodReference, classRenameMap: Map<String, String>): MethodReference {
        val newDef = remapType(mr.definingClass, classRenameMap)
        val params = ArrayList<String>(mr.parameterTypes.size)
        var changed = newDef != mr.definingClass
        for (p in mr.parameterTypes) {
            val mp = remapType(p.toString(), classRenameMap)
            if (mp != p.toString()) changed = true
            params.add(mp!!)
        }
        val newRet = remapType(mr.returnType, classRenameMap)
        if (newRet != mr.returnType) changed = true
        if (!changed) return mr
        return ImmutableMethodReference(newDef!!, mr.name, params, newRet!!)
    }

    private fun remapMethodHandleRef(mhr: MethodHandleReference, classRenameMap: Map<String, String>): MethodHandleReference? {
        val member = mhr.memberReference
        var newMember: com.android.tools.smali.dexlib2.iface.reference.Reference? = null
        var needs = false
        when (member) {
            is FieldReference -> {
                val nf = remapFieldRef(member, classRenameMap)
                if (nf !== member) {
                    needs = true
                    newMember = nf
                }
            }
            is MethodReference -> {
                val nm = remapMethodRef(member, classRenameMap)
                if (nm !== member) {
                    needs = true
                    newMember = nm
                }
            }
        }
        if (!needs || newMember == null) return null
        return ImmutableMethodHandleReference(mhr.methodHandleType, newMember)
    }

    private fun remapMethodProtoRef(proto: MethodProtoReference, classRenameMap: Map<String, String>): MethodProtoReference? {
        val newRet = remapType(proto.returnType, classRenameMap)
        var changed = newRet != proto.returnType
        val newParams = ArrayList<CharSequence>(proto.parameterTypes.size)
        for (p in proto.parameterTypes) {
            val mp = remapType(p.toString(), classRenameMap)
            if (mp != p.toString()) changed = true
            newParams.add(mp!!)
        }
        if (!changed) return null
        return ImmutableMethodProtoReference(newParams, newRet!!)
    }

    private fun remapCallSiteRef(csr: CallSiteReference, classRenameMap: Map<String, String>): CallSiteReference? {
        var newHandle = remapMethodHandleRef(csr.methodHandle, classRenameMap)
        if (newHandle == null) newHandle = csr.methodHandle
        var newProto = remapMethodProtoRef(csr.methodProto, classRenameMap)
        if (newProto == null) newProto = csr.methodProto
        var needs = newHandle !== csr.methodHandle || newProto !== csr.methodProto
        val newValues = ArrayList<EncodedValue>(csr.extraArguments.size)
        for (ev in csr.extraArguments) {
            val nv = remapEncodedValue(ev, classRenameMap)
            if (nv !== ev) needs = true
            newValues.add(nv)
        }
        if (!needs) return null
        return ImmutableCallSiteReference(csr.name, newHandle, csr.methodName, newProto, newValues)
    }

    private fun remapEncodedValue(v: EncodedValue, classRenameMap: Map<String, String>): EncodedValue {
        return when (v) {
            is TypeEncodedValue -> {
                val nt = remapType(v.value, classRenameMap)
                if (nt == v.value) v else ImmutableTypeEncodedValue(nt!!)
            }
            is FieldEncodedValue -> {
                val nf = remapFieldRef(v.value, classRenameMap)
                if (nf === v.value) v else ImmutableFieldEncodedValue(nf as ImmutableFieldReference)
            }
            is MethodEncodedValue -> {
                val nm = remapMethodRef(v.value, classRenameMap)
                if (nm === v.value) v else ImmutableMethodEncodedValue(nm as ImmutableMethodReference)
            }
            is EnumEncodedValue -> {
                val nf = remapFieldRef(v.value, classRenameMap)
                if (nf === v.value) v else ImmutableEnumEncodedValue(nf as ImmutableFieldReference)
            }
            is ArrayEncodedValue -> {
                var dirty = false
                val newValues = ArrayList<EncodedValue>(v.value.size)
                for (item in v.value) {
                    val nv = remapEncodedValue(item, classRenameMap)
                    if (nv !== item) dirty = true
                    newValues.add(nv)
                }
                if (dirty) ImmutableArrayEncodedValue(newValues) else v
            }
            is AnnotationEncodedValue -> {
                val nt = remapType(v.type, classRenameMap)
                var dirty = nt != v.type
                val newElements = ArrayList<AnnotationElement>(v.elements.size)
                for (el in v.elements) {
                    val nv = remapEncodedValue(el.value, classRenameMap)
                    if (nv !== el.value) dirty = true
                    newElements.add(ImmutableAnnotationElement(el.name, nv))
                }
                if (dirty) ImmutableAnnotationEncodedValue(nt!!, newElements) else v
            }
            else -> v
        }
    }

    private fun remapAnnotations(annotations: Set<Annotation>, classRenameMap: Map<String, String>): Set<Annotation> {
        var dirty = false
        val out = LinkedHashSet<Annotation>(annotations.size)
        for (a in annotations) {
            val nt = remapType(a.type, classRenameMap)
            var elDirty = nt != a.type
            val newElements = ArrayList<AnnotationElement>(a.elements.size)
            for (el in a.elements) {
                val nv = remapEncodedValue(el.value, classRenameMap)
                if (nv !== el.value) elDirty = true
                newElements.add(ImmutableAnnotationElement(el.name, nv))
            }
            if (elDirty) {
                out.add(ImmutableAnnotation(a.visibility, nt!!, newElements))
                dirty = true
            } else {
                out.add(a)
            }
        }
        return if (dirty) out else annotations
    }

    private fun remapTryBlocks(
        tryBlocks: List<TryBlock<out ExceptionHandler>>,
        classRenameMap: Map<String, String>
    ): List<TryBlock<out ExceptionHandler>> {
        if (tryBlocks.isEmpty()) return tryBlocks
        val result = ArrayList<TryBlock<out ExceptionHandler>>(tryBlocks.size)
        var changed = false
        for (tb in tryBlocks) {
            val newHandlers = ArrayList<ExceptionHandler>(tb.exceptionHandlers.size)
            for (h in tb.exceptionHandlers) {
                val newType = h.exceptionType?.let { remapType(it, classRenameMap) }
                if (newType != h.exceptionType) changed = true
                newHandlers.add(ImmutableExceptionHandler(newType, h.handlerCodeAddress))
            }
            result.add(ImmutableTryBlock(tb.startCodeAddress, tb.codeUnitCount, newHandlers))
        }
        return if (changed) result else tryBlocks
    }

    private fun remapInstruction(insn: Instruction, classRenameMap: Map<String, String>): Instruction {
        if (insn !is ReferenceInstruction) return insn
        return try {
            val ref = insn.reference
            when (ref) {
                is TypeReference -> {
                    val nt = remapType(ref.type, classRenameMap)
                    if (nt == ref.type) return insn
                    val newRef = ImmutableTypeReference(nt!!)
                    rebuildReferenceInsn(insn, newRef)
                }
                is FieldReference -> {
                    val nf = remapFieldRef(ref, classRenameMap)
                    if (nf === ref) insn else rebuildReferenceInsn(insn, nf)
                }
                is MethodReference -> {
                    val nm = remapMethodRef(ref, classRenameMap)
                    if (nm === ref) insn else rebuildReferenceInsn(insn, nm, protoRefOf(insn, classRenameMap))
                }
                is MethodHandleReference -> {
                    val nm = remapMethodHandleRef(ref, classRenameMap)
                    if (nm == null) insn else rebuildReferenceInsn(insn, nm)
                }
                is MethodProtoReference -> {
                    val np = remapMethodProtoRef(ref, classRenameMap)
                    if (np == null) insn else rebuildReferenceInsn(insn, np)
                }
                is CallSiteReference -> {
                    val nc = remapCallSiteRef(ref, classRenameMap)
                    if (nc == null) insn else rebuildReferenceInsn(insn, nc)
                }
                else -> insn
            }
        } catch (e: Exception) {
            insn
        }
    }

    private fun protoRefOf(
        insn: Instruction,
        classRenameMap: Map<String, String>
    ): MethodProtoReference? {
        val imm = ImmutableInstruction.of(insn)
        return when (imm) {
            is com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction45cc -> {
                val p = imm.reference2 as? MethodProtoReference ?: return null
                remapMethodProtoRef(p, classRenameMap) ?: p
            }
            is com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction4rcc -> {
                val p = imm.reference2 as? MethodProtoReference ?: return null
                remapMethodProtoRef(p, classRenameMap) ?: p
            }
            else -> null
        }
    }

    private fun rebuildReferenceInsn(
        insn: Instruction,
        ref: com.android.tools.smali.dexlib2.iface.reference.Reference,
        proto: MethodProtoReference? = null
    ): Instruction {
        val imm = ImmutableInstruction.of(insn)
        val op = imm.opcode
        return when {
            imm is com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction45cc && proto != null -> {
                ImmutableInstruction45cc(op, imm.registerCount,
                    imm.registerC, imm.registerD, imm.registerE, imm.registerF, imm.registerG, ref, proto)
            }
            imm is com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction4rcc && proto != null -> {
                ImmutableInstruction4rcc(op, imm.startRegister, imm.registerCount, ref, proto)
            }
            imm is com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c -> {
                ImmutableInstruction35c(op, imm.registerCount,
                    imm.registerC, imm.registerD, imm.registerE, imm.registerF, imm.registerG, ref)
            }
            imm is com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction3rc -> {
                ImmutableInstruction3rc(op, imm.registerCount, imm.startRegister, ref)
            }
            imm is com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction22c -> {
                ImmutableInstruction22c(op, imm.registerA, imm.registerB, ref)
            }
            imm is com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction21c -> {
                ImmutableInstruction21c(op, imm.registerA, ref)
            }
            imm is com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction31c -> {
                ImmutableInstruction31c(op, imm.registerA, ref)
            }
            else -> ImmutableInstruction.of(insn)
        }
    }

    private fun ClassDef.buildClassRenameRefContracts(): ClassDef = this

    private fun rewriteMethod(method: Method, classRenameMap: Map<String, String>): Method? {
        var dirty = false
        val newAnno = remapAnnotations(method.annotations, classRenameMap)
        if (newAnno !== method.annotations) dirty = true
        var newImpl: com.android.tools.smali.dexlib2.iface.MethodImplementation? = null
        val impl = method.implementation
        if (impl != null) {
            var implDirty = false
            val newInsns = ArrayList<Instruction>(impl.instructions.count())
            for (insn in impl.instructions) {
                val ni = remapInstruction(insn, classRenameMap)
                if (ni !== insn) implDirty = true
                newInsns.add(ni)
            }
            val newTry = remapTryBlocks(impl.tryBlocks, classRenameMap)
            if (newTry !== impl.tryBlocks) implDirty = true
            if (implDirty) {
                newImpl = ImmutableMethodImplementation(impl.registerCount, newInsns, newTry, impl.debugItems)
                dirty = true
            }
        }
        if (!dirty) return null
        return ImmutableMethod(
            method.definingClass, method.name, method.parameters, method.returnType,
            method.accessFlags, newAnno, method.hiddenApiRestrictions, newImpl ?: impl
        )
    }

    private fun classReferencesRenamedClass(cd: ClassDef, classRenameMap: Map<String, String>): Boolean {
        if (classRenameMap.isEmpty()) return false
        if (cd.superclass?.let { classRenameMap.containsKey(it) } == true) return true
        for (iface in cd.interfaces) if (classRenameMap.containsKey(iface)) return true
        for (f in cd.staticFields) if (classRenameMap.containsKey(f.type)) return true
        for (f in cd.instanceFields) if (classRenameMap.containsKey(f.type)) return true
        for (m in cd.methods) {
            if (classRenameMap.containsKey(m.returnType)) return true
            for (p in m.parameters) if (classRenameMap.containsKey(p.type)) return true
            val impl = m.implementation ?: continue
            for (insn in impl.instructions) {
                if (insn !is ReferenceInstruction) continue
                when (val ref = insn.reference) {
                    is TypeReference -> if (classRenameMap.containsKey(ref.type)) return true
                    is FieldReference -> {
                        if (classRenameMap.containsKey(ref.definingClass) || classRenameMap.containsKey(ref.type)) return true
                    }
                    is MethodReference -> {
                        if (classRenameMap.containsKey(ref.definingClass) || classRenameMap.containsKey(ref.returnType)) return true
                        for (pt in ref.parameterTypes) {
                            if (classRenameMap.containsKey(pt.toString())) return true
                        }
                    }
                }
            }
        }
        return false
    }

    /**
     * 生成全局类名重命名映射（跨所有 dex 一次性构建）。
     * 返回 Map<原始类名, 新类名>，为空表示没有可重命名类。
     * protectedStrings 类名（如经字符串常量引用的类）将被排除。
     */
    fun buildClassRenameMap(
        dexFiles: List<File>,
        extraProtectedPrefixes: Set<String> = emptySet()
    ): Map<String, String> {
        val loadable = dexFiles.filter { it.exists() && FrostDexUtils.getDexNumber(it.name) >= 0 }

        // step1: 收集全量类类型，用于字符串引用保护判断
        var protectedSet = HashSet(extraProtectedPrefixes)
        var allTypes: Set<String>? = null
        if (true) {
            val types = LinkedHashSet<String>()
            for (df in loadable) {
                try {
                    val dex = DexFileFactory.loadDexFile(df, Opcodes.getDefault())
                    for (cd in dex.classes) types.add(cd.type)
                } catch (e: Exception) {
                    FrostLogUtils.warn("class rename: skip scan %s: %s", df.name, e.message)
                }
            }
            allTypes = types
        }
        // step2: 扫描字符串常量（Class.forName 等反射路径），命中全量类则保护
        for (df in loadable) {
            try {
                val dex = DexFileFactory.loadDexFile(df, Opcodes.getDefault())
                for (cd in dex.classes) {
                    for (m in cd.methods) {
                        val impl = m.implementation ?: continue
                        for (insn in impl.instructions) {
                            if (insn !is ReferenceInstruction) continue
                            val ref = insn.reference
                            if (ref is StringReference) {
                                val d = tryConvertToDescriptor(ref.string)
                                if (d != null && allTypes!!.contains(d)) protectedSet.add(d)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                FrostLogUtils.warn("class rename: skip string scan %s: %s", df.name, e.message)
            }
        }

        // step3: 全局分配新类名（跨 dex 唯一）
        val used = HashSet<String>()
        val classRenameMap = LinkedHashMap<String, String>()
        var totalRenamed = 0
        for (df in loadable) {
            try {
                val dex = DexFileFactory.loadDexFile(df, Opcodes.getDefault())
                for (cd in dex.classes) {
                    val type = cd.type
                    if (classRenameMap.containsKey(type)) continue
                    if (!shouldRename(type, protectedSet)) continue
                    val newName = genClassName(used)
                    classRenameMap[type] = newName
                    totalRenamed++
                }
            } catch (e: Exception) {
                FrostLogUtils.warn("class rename: skip map %s: %s", df.name, e.message)
            }
        }
        if (totalRenamed > 0) {
            FrostLogUtils.info("class rename map built: %d classes", totalRenamed)
        }
        return classRenameMap
    }

    /**
     * 对单个 dex 应用类重命名。返回 (重命名类数, 引用更新方法数)。
     */
    fun applyClassRenameDex(dexFile: File, classRenameMap: Map<String, String>): Pair<Int, Int> {
        if (classRenameMap.isEmpty()) return 0 to 0
        val dex = try {
            DexFileFactory.loadDexFile(dexFile, Opcodes.getDefault())
        } catch (e: Exception) {
            FrostLogUtils.warn("class rename: load failed for %s: %s", dexFile.name, e.message)
            return 0 to 0
        }
        // 说明：此处原本有 stringSection.size > 0xFFFF - map.size 的预检；实测 DexPool 可正常写回
        // string_ids 超 65535 的 dex（完整 palm classes.dex=123031 写回通过），故移除该保守跳过。
        // 写回后仍有 loadDexFile 重载校验，失败自动回滚备份，不会污染产物。
        var renamed = 0
        var touched = 0
        val nativeClasses = HashSet<String>()
        for (cd in dex.classes) {
            for (m in cd.methods) {
                if ((m.accessFlags and 0x100) != 0) {
                    nativeClasses.add(cd.type)
                    break
                }
            }
        }
        val newClasses = ArrayList<ClassDef>(dex.classes.size)
        for (cd in dex.classes) {
            val newType = classRenameMap[cd.type]
            if (newType != null) {
                if (cd.type in nativeClasses) {
                    newClasses.add(cd)
                    continue
                }
                val rewritten = renameSingleClass(cd, newType, classRenameMap)
                renamed++
                var touchedMethods = 0
                for (m in rewritten.virtualMethods) {
                    if (m.implementation != null) touchedMethods++
                }
                for (m in rewritten.directMethods) {
                    if (m.implementation != null) touchedMethods++
                }
                touched += touchedMethods
                newClasses.add(rewritten)
                continue
            }
            if (classReferencesRenamedClass(cd, classRenameMap)) {
                val rewritten = rewriteReferencesOnly(cd, classRenameMap)
                var tn = 0
                for (m in rewritten.methods) if (m.implementation != null) tn++
                touched += tn
                newClasses.add(rewritten)
            } else {
                newClasses.add(cd)
            }
        }
        if (renamed == 0) return 0 to 0
        val backup = File(dexFile.absolutePath + ".clsrm_backup")
        return try {
            backup.writeBytes(dexFile.readBytes())
            DexFileFactory.writeDexFile(dexFile.absolutePath, RewrittenDexFile(dex.opcodes, newClasses))
            try {
                DexFileFactory.loadDexFile(dexFile, Opcodes.getDefault())
                FrostLogUtils.info(
                    "class rename done: %s (%d classes renamed, %d methods touched)",
                    dexFile.name, renamed, touched
                )
                renamed to touched
            } catch (v: Exception) {
                dexFile.writeBytes(backup.readBytes())
                FrostLogUtils.warn("class rename verify failed, rolled back: %s (%s)", dexFile.name, v.message)
                0 to 0
            }
        } catch (e: Exception) {
            if (backup.exists()) {
                try {
                    dexFile.writeBytes(backup.readBytes())
                } catch (r: Throwable) {
                    // 回滚失败交由上层兜底
                }
            }
            FrostLogUtils.warn("class rename failed for %s: %s", dexFile.name, e.message)
            0 to 0
        } finally {
            backup.delete()
        }
    }

    private fun renameSingleClass(
        cd: ClassDef,
        newType: String,
        classRenameMap: Map<String, String>
    ): ClassDef {
        // 字段 owner 全部改为新类名（同时重映射字段类型）
        val allFields = ArrayList<Field>(cd.staticFields.count() + cd.instanceFields.count())
        for (f in cd.staticFields) {
            val ft = remapType(f.type, classRenameMap)!!
            allFields.add(
                if (ft == f.type) ImmutableField(newType, f.name, f.type, f.accessFlags, f.initialValue, f.annotations, f.hiddenApiRestrictions)
                else ImmutableField(newType, f.name, ft, f.accessFlags, f.initialValue, remapAnnotations(f.annotations, classRenameMap), f.hiddenApiRestrictions)
            )
        }
        for (f in cd.instanceFields) {
            val ft = remapType(f.type, classRenameMap)!!
            allFields.add(
                if (ft == f.type) ImmutableField(newType, f.name, f.type, f.accessFlags, f.initialValue, f.annotations, f.hiddenApiRestrictions)
                else ImmutableField(newType, f.name, ft, f.accessFlags, f.initialValue, remapAnnotations(f.annotations, classRenameMap), f.hiddenApiRestrictions)
            )
        }
        // 方法 owner 改为新类名，重映射参数/返回/注解/指令/异常类型
        val methods = ArrayList<Method>(cd.methods.count())
        for (m in cd.methods) {
            methods.add(rewriteMethodWithOwner(m, newType, classRenameMap))
        }
        val newSuper = remapType(cd.superclass, classRenameMap)
        val newInterfaces = ArrayList<String>(cd.interfaces.size)
        for (i in cd.interfaces) newInterfaces.add(remapType(i, classRenameMap)!!)
        val newAnno = remapAnnotations(cd.annotations, classRenameMap)

        return ClassDefOf(
            newType, cd.accessFlags, newSuper, newInterfaces, cd.sourceFile, newAnno, allFields, methods
        )
    }

    private fun rewriteMethodWithOwner(
        method: Method,
        newOwner: String,
        classRenameMap: Map<String, String>
    ): Method {
        val newParams = ArrayList<com.android.tools.smali.dexlib2.iface.MethodParameter>(method.parameters.size)
        for (p in method.parameters) {
            val pt = remapType(p.type, classRenameMap)!!
            newParams.add(
                if (pt == p.type) p
                else ImmutableMethodParameter(pt, remapAnnotations(p.annotations, classRenameMap), p.name)
            )
        }
        val newRet = remapType(method.returnType, classRenameMap)!!
        var newImpl: com.android.tools.smali.dexlib2.iface.MethodImplementation? = null
        val impl = method.implementation
        if (impl != null) {
            val newInsns = ArrayList<Instruction>(impl.instructions.count())
            var dirty = false
            for (insn in impl.instructions) {
                val ni = remapInstruction(insn, classRenameMap)
                if (ni !== insn) dirty = true
                newInsns.add(ni)
            }
            val newTry = remapTryBlocks(impl.tryBlocks, classRenameMap)
            if (newTry !== impl.tryBlocks) dirty = true
            if (dirty || newOwner != method.definingClass || newParams != method.parameters || newRet != method.returnType) {
                newImpl = ImmutableMethodImplementation(impl.registerCount, newInsns, newTry, impl.debugItems)
            }
        }
        val newAnno = remapAnnotations(method.annotations, classRenameMap)
        return ImmutableMethod(
            newOwner, method.name, newParams, newRet,
            method.accessFlags, newAnno, method.hiddenApiRestrictions, newImpl ?: impl
        )
    }

    private fun rewriteReferencesOnly(cd: ClassDef, classRenameMap: Map<String, String>): ClassDef {
        val newSuper = remapType(cd.superclass, classRenameMap)
        val newInterfaces = ArrayList<String>(cd.interfaces.size)
        for (i in cd.interfaces) newInterfaces.add(remapType(i, classRenameMap)!!)
        val allFields = ArrayList<Field>(cd.staticFields.count() + cd.instanceFields.count())
        for (f in cd.staticFields) {
            val ft = remapType(f.type, classRenameMap)!!
            allFields.add(
                if (ft == f.type) f
                else ImmutableField(f.definingClass, f.name, ft, f.accessFlags, f.initialValue, remapAnnotations(f.annotations, classRenameMap), f.hiddenApiRestrictions)
            )
        }
        for (f in cd.instanceFields) {
            val ft = remapType(f.type, classRenameMap)!!
            allFields.add(
                if (ft == f.type) f
                else ImmutableField(f.definingClass, f.name, ft, f.accessFlags, f.initialValue, remapAnnotations(f.annotations, classRenameMap), f.hiddenApiRestrictions)
            )
        }
        val methods = ArrayList<Method>(cd.methods.count())
        for (m in cd.methods) {
            methods.add(rewriteMethodWithOwner(m, remapType(m.definingClass, classRenameMap)!!, classRenameMap))
        }
        val newAnno = remapAnnotations(cd.annotations, classRenameMap)
        return ClassDefOf(
            cd.type, cd.accessFlags, newSuper, newInterfaces, cd.sourceFile, newAnno, allFields, methods
        )
    }

    private fun ClassDefOf(
        type: String,
        accessFlags: Int,
        superclass: String?,
        interfaces: List<String>,
        sourceFile: String?,
        annotations: Set<Annotation>,
        fields: List<Field>,
        methods: List<Method>
    ): ClassDef {
        // named class 因直接 new TypeReference 不再走 source.validateReference。详见注释。
        return object : com.android.tools.smali.dexlib2.base.reference.BaseTypeReference(), ClassDef {
            override fun validateReference() {
            }

            override fun getType(): String = type

            override fun getAccessFlags(): Int = accessFlags

            override fun getSuperclass(): String? = superclass

            override fun getInterfaces(): List<String> = interfaces

            override fun getSourceFile(): String? = sourceFile

            override fun getAnnotations(): Set<Annotation> = annotations

            override fun getStaticFields(): Iterable<Field> = fields.filter { (it.accessFlags and 0x8) != 0 }

            override fun getInstanceFields(): Iterable<Field> = fields.filter { (it.accessFlags and 0x8) == 0 }

            override fun getFields(): Iterable<Field> = fields

            override fun getDirectMethods(): Iterable<Method> = methods.filter { (it.accessFlags and 0x2) == 0 }

            override fun getVirtualMethods(): Iterable<Method> = methods.filter { (it.accessFlags and 0x2) != 0 }

            override fun getMethods(): Iterable<Method> = methods
        }
    }
}