package com.adfxcbnm.frostshell.dex

import com.adfxcbnm.frostshell.util.FrostLogUtils
import com.adfxcbnm.frostshell.util.FrostStringUtils
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.base.reference.BaseTypeReference
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction10x
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction11n
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction11x
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21s
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction22c
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.Annotation
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.DexFile
import com.android.tools.smali.dexlib2.iface.Field
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.MethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.LinkedHashSet

object FrostReflectionClinitInjector {
    private const val MAX_METHODS_PER_DEX = 65535
    private const val CLASS_FOR_NAME = "Ljava/lang/Class;"
    private const val METHOD_TYPE = "Ljava/lang/reflect/Method;"
    private const val ACCESSIBLE_OBJECT = "Ljava/lang/reflect/AccessibleObject;"
    private const val CLASS_ARRAY = "[Ljava/lang/Class;"
    private const val OBJECT_ARRAY = "[Ljava/lang/Object;"
    private const val CLINIT_METHOD_NAME = "clinit"
    private const val REG_CLASS_NAME = 0
    private const val REG_METHOD_NAME = 1
    private const val REG_ARRAY = 2
    private const val REG_INDEX = 3
    private const val REG_KEY = 4
    private const val REG_LENGTH = 5
    private const val REG_TEMP = 6
    private val SKIPPED_CLASS_TYPES = setOf(
        "Landroidx/multidex/MultiDex;",
        "Lcom/android/support/multidex/MultiDex;"
    )

    @Throws(IOException::class)
    fun inject(inputDex: String, outputDex: String, jniClassSig: String) {
        val inputFile = File(inputDex)
        val dexFile = com.adfxcbnm.frostshell.util.FrostDexUtils.loadDexPreservingVersion(inputFile)
        val jniDotClassName = sigToDotName(jniClassSig)
        val allClasses = ArrayList<ClassDef>()
        var totalMethodCount = 0
        for (classDef in dexFile.classes) {
            allClasses.add(classDef)
            for (method in classDef.methods) {
                ++totalMethodCount
            }
        }
        val state = InjectState(totalMethodCount, allClasses)
        val hashMap = HashMap<String, MutableList<Method>>()
        val clinitClassToHelperRef = HashMap<String, ImmutableMethodReference>()
        for (classDef in allClasses) {
            if (isSkippedClass(classDef.type)) continue
            for (method in classDef.methods) {
                if (!isEligibleClinit(method)) continue
                val helperRef = state.resolveHelper(classDef.type, jniDotClassName, hashMap)
                if (helperRef == null) continue
                clinitClassToHelperRef[classDef.type] = helperRef
                ++state.clinitIndex
            }
        }
        val arrayList = ArrayList<ClassDef>()
        for (classDef in allClasses) {
            val helperRef = clinitClassToHelperRef[classDef.type]
            val helpers = hashMap[classDef.type]
            if (helperRef == null && helpers == null) {
                arrayList.add(classDef)
                continue
            }
            val directMethods = ArrayList<Method>()
            for (method in classDef.directMethods) {
                if (helperRef != null && "<clinit>" == method.name) {
                    directMethods.add(injectHelperCall(method, helperRef))
                } else {
                    directMethods.add(peelDebugInfo(method))
                }
            }
            if (helpers != null) {
                directMethods.addAll(helpers)
            }
            // 委托式 ClassDef：不重建方法集合（ImmutableClassDef 会对整类方法 TreeSet 排序引发 OOM）
            arrayList.add(RewrittenClassDef(classDef, directMethods, classDef.virtualMethods))
        }
        // 委托式 DexFile：保持 class 顺序，写入交给 DexPool，避免 ImmutableDexFile 内部再次排序
        DexFileFactory.writeDexFile(outputDex, RewrittenDexFile(dexFile.opcodes, arrayList))
        FrostLogUtils.debug(
            "reflection clinit inject: helpers=%d, clinits=%d, methods=%d",
            state.helperRefs.size, state.clinitIndex, state.totalMethodCount
        )
    }

    private fun isSkippedClass(classType: String): Boolean = SKIPPED_CLASS_TYPES.contains(classType)

    private fun sigToDotName(jniClassSig: String): String {
        var name = jniClassSig
        if (name.startsWith("L") && name.endsWith(";")) {
            name = name.substring(1, name.length - 1)
        }
        return name.replace('/', '.')
    }

    private fun isEligibleClinit(method: Method): Boolean {
        if ("<clinit>" != method.name) return false
        val implementation = method.implementation ?: return false
        var hasFillArrayData = false
        for (instruction in implementation.instructions) {
            if (instruction.opcode != Opcode.FILL_ARRAY_DATA) continue
            hasFillArrayData = true
            break
        }
        if (hasFillArrayData || !implementation.tryBlocks.isEmpty()) return false
        val instructions = toInstructionList(implementation.instructions)
        return instructions.isNotEmpty() && instructions[instructions.size - 1].opcode == Opcode.RETURN_VOID
    }

    private fun toInstructionList(instructions: Iterable<out Instruction>): List<Instruction> {
        val list = ArrayList<Instruction>()
        for (instruction in instructions) {
            list.add(instruction)
        }
        return list
    }

    private fun injectHelperCall(method: Method, helperRef: ImmutableMethodReference): Method {
        val implementation = method.implementation ?: return method
        // MutableMethodImplementation 复制会将 offset 指令转为 label 式 builder 指令，
        // 插入 invoke 后自动重算全部跳转偏移，避免 backed 指令偏移错位（ART VerifyError）。
        val mutable = MutableMethodImplementation(implementation)
        mutable.addInstruction(
            mutable.instructions.size - 1,
            BuilderInstruction35c(Opcode.INVOKE_STATIC, 0, 0, 0, 0, 0, 0, helperRef)
        )
        return ImmutableMethod(
            method.definingClass, method.name, method.parameters, method.returnType, method.accessFlags,
            method.annotations, method.hiddenApiRestrictions, mutable
        )
    }

    private fun buildReflectionImplementation(jniDotClassName: String): MethodImplementation {
        val key = FrostStringXorCipher.randomKey()
        val encryptedClassName = FrostStringXorCipher.encrypt(jniDotClassName, key)
        val encryptedMethodName = FrostStringXorCipher.encrypt(CLINIT_METHOD_NAME, key)
        val impl = MutableMethodImplementation(7)
        impl.addInstruction(BuilderInstruction21s(Opcode.CONST_16, 4, key))
        FrostDexStringDecryptBuilder.emitDecrypt(impl, 0, encryptedClassName, 2, 3, 4, 5, 6)
        FrostDexStringDecryptBuilder.emitDecrypt(impl, 1, encryptedMethodName, 2, 3, 4, 5, 6)
        impl.addInstruction(
            BuilderInstruction35c(
                Opcode.INVOKE_STATIC, 1, 0, 0, 0, 0, 0,
                ImmutableMethodReference(CLASS_FOR_NAME, "forName", Collections.singletonList("Ljava/lang/String;"), CLASS_FOR_NAME)
            )
        )
        impl.addInstruction(BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0))
        impl.addInstruction(BuilderInstruction11n(Opcode.CONST_4, 2, 0))
        impl.addInstruction(BuilderInstruction22c(Opcode.NEW_ARRAY, 2, 2, ImmutableTypeReference(CLASS_ARRAY)))
        impl.addInstruction(
            BuilderInstruction35c(
                Opcode.INVOKE_VIRTUAL, 3, 0, 1, 2, 0, 0,
                ImmutableMethodReference(CLASS_FOR_NAME, "getDeclaredMethod", listOf("Ljava/lang/String;", CLASS_ARRAY), METHOD_TYPE)
            )
        )
        impl.addInstruction(BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0))
        impl.addInstruction(BuilderInstruction11n(Opcode.CONST_4, 1, 1))
        impl.addInstruction(
            BuilderInstruction35c(
                Opcode.INVOKE_VIRTUAL, 2, 0, 1, 0, 0, 0,
                ImmutableMethodReference(ACCESSIBLE_OBJECT, "setAccessible", Collections.singletonList("Z"), "V")
            )
        )
        impl.addInstruction(BuilderInstruction11n(Opcode.CONST_4, 1, 0))
        impl.addInstruction(BuilderInstruction11n(Opcode.CONST_4, 2, 0))
        impl.addInstruction(BuilderInstruction22c(Opcode.NEW_ARRAY, 2, 2, ImmutableTypeReference(OBJECT_ARRAY)))
        impl.addInstruction(
            BuilderInstruction35c(
                Opcode.INVOKE_VIRTUAL, 3, 0, 1, 2, 0, 0,
                ImmutableMethodReference(METHOD_TYPE, "invoke", listOf("Ljava/lang/Object;", OBJECT_ARRAY), "Ljava/lang/Object;")
            )
        )
        impl.addInstruction(BuilderInstruction10x(Opcode.RETURN_VOID))
        return impl
    }

    private fun createHelperMethod(hostClassType: String, methodName: String, jniDotClassName: String): Method {
        return ImmutableMethod(
            hostClassType, methodName, Collections.emptyList(), "V",
            AccessFlags.PRIVATE.value or AccessFlags.STATIC.value or AccessFlags.SYNTHETIC.value,
            null, null, buildReflectionImplementation(jniDotClassName)
        )
    }

    private class InjectState(
        var totalMethodCount: Int,
        allClasses: List<ClassDef>
    ) {
        val helperRefs = ArrayList<ImmutableMethodReference>()
        val methodNamesByClass = HashMap<String, MutableSet<String>>()
        var clinitIndex: Int = 0

        init {
            for (classDef in allClasses) {
                val names = HashSet<String>()
                for (method in classDef.methods) {
                    names.add(method.name)
                }
                this.methodNamesByClass[classDef.type] = names
            }
        }

        fun resolveHelper(clinitClassType: String, jniDotClassName: String, helpersByHostClass: MutableMap<String, MutableList<Method>>): ImmutableMethodReference? {
            if (this.totalMethodCount >= MAX_METHODS_PER_DEX) {
                for (ref in this.helperRefs) {
                    if (ref.definingClass != clinitClassType) continue
                    return ref
                }
                return null
            }
            val hostClass = clinitClassType
            val methodName = generateUniqueMethodName(hostClass)
            val helper = createHelperMethod(hostClass, methodName, jniDotClassName)
            helpersByHostClass.computeIfAbsent(hostClass) { ArrayList() }.add(helper)
            val ref = ImmutableMethodReference(hostClass, methodName, Collections.emptyList(), "V")
            this.helperRefs.add(ref)
            ++this.totalMethodCount
            return ref
        }

        private fun generateUniqueMethodName(hostClass: String): String {
            val names = this.methodNamesByClass.computeIfAbsent(hostClass) { HashSet() }
            var name: String
            do {
                name = FrostStringUtils.generateIdentifier(4)
            } while (names.contains(name))
            names.add(name)
            return name
        }
    }

    /**
     * 剥离方法 debug 信息（不依赖 ImmutableClassDef 整体重建，避免大 dex OOM）。
     */
    private fun peelDebugInfo(method: Method): Method {
        val impl = method.implementation ?: return method
        if (impl.debugItems.any()) {
            val stripped = ImmutableMethodImplementation(
                impl.registerCount, impl.instructions, impl.tryBlocks, Collections.emptyList()
            )
            return ImmutableMethod(
                method.definingClass, method.name, method.parameters, method.returnType,
                method.accessFlags, method.annotations, method.hiddenApiRestrictions, stripped
            )
        }
        return method
    }

    }
