package com.adfxcbnm.hardeningtool

import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableDexFile
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction31c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import java.io.File
import java.util.regex.Pattern

/**
 * 伪装加固（真正改名版）：
 * 将壳 dex 中 System.load 引用的壳库名（lib8012d9ae47c7f010.so 等）替换为用户指定厂商名，
 * 并把 shell-files/libs 下对应 so 文件同步改名，让引擎 copyNativeLibs 打包时以新名输出。
 */
object SoNameDisguiser {

    private val LIB_REF_PATTERN = Pattern.compile("^/?lib([A-Za-z0-9_\\-]+)\\.so$")

    data class Result(val oldName: String?, val renamed: List<String>, val errors: List<String>)

    fun disguise(shellFilesDir: File, baseName: String): Result {
        val newSoName = "lib$baseName.so"
        val errors = mutableListOf<String>()
        val renamed = mutableListOf<String>()
        val dexFile = File(shellFilesDir, "dex/classes.dex")
        if (!dexFile.isFile) {
            errors.add("壳dex不存在: ${dexFile.absolutePath}")
            return Result(null, renamed, errors)
        }

        val currentRefs = scanSoRefs(dexFile)
        if (currentRefs.isEmpty()) {
            errors.add("壳dex中未发现引用的壳库名: ${dexFile.absolutePath}")
            return Result(null, renamed, errors)
        }
        val primaryRef = currentRefs.first()

        // 先核实壳库文件是否存在且可改名：dex 引用名必须能落到 libs/<abi>/ 下，
        // 否则改了 dex 引用却没有对应 so 打包，应用运行时将加载不存在的库（伪装失败）。
        val libsRoot = File(shellFilesDir, "libs")
        val abiDirs = libsRoot.listFiles()?.filter { it.isDirectory } ?: emptyList()
        val matched = linkedMapOf<File, File>() // abi上级目录 -> matched so
        for (abi in abiDirs) {
            val so = abi.listFiles()?.firstOrNull { it.isFile && it.name == primaryRef }
            if (so != null) matched[abi] = so
        }
        if (matched.isEmpty()) {
            errors.add("未找到与壳库引用对应的 so 文件(需要: $primaryRef)，伪装已中止以保留原库名")
            return Result(primaryRef, renamed, errors)
        }

        try {
            rewriteDex(dexFile, primaryRef, newSoName)
        } catch (e: Exception) {
            errors.add("壳dex重写失败: ${e.message}")
        }

        if (errors.isNotEmpty()) {
            // dex 未改写成功则保持原状，直接失败返回
            return Result(primaryRef, emptyList(), errors)
        }

        val done = mutableListOf<Pair<File, File>>() // old so -> new so
        for ((abi, so) in matched) {
            val target = File(so.parentFile, newSoName)
            try {
                if (target.exists()) {
                    if (!target.delete()) {
                        errors.add("${abi.name}/$target 旧文件清理失败")
                        continue
                    }
                }
                if (!so.renameTo(target)) {
                    errors.add("${abi.name}/${so.name} 改名失败")
                    continue
                }
                done.add(so to target)
                renamed.add("${abi.name}/$newSoName")
            } catch (e: Exception) {
                errors.add("${abi.name}/${so.name} 改名异常: ${e.message}")
            }
        }

        if (errors.isNotEmpty()) {
            // 有 abi 改名失败：回滚已成功改名的 so，避免 dex 引用新名但缺失对应库
            for ((old, new) in done) {
                try { new.renameTo(old) } catch (e: Exception) { }
            }
            return Result(primaryRef, emptyList(), errors)
        }
        return Result(primaryRef, renamed, errors)
    }

    private fun scanSoRefs(dexFile: File): List<String> {
        val refs = linkedSetOf<String>()
        try {
            val dex = DexFileFactory.loadDexFile(dexFile, Opcodes.getDefault())
            for (classDef in dex.classes) {
                for (method in classDef.methods) {
                    val impl = method.implementation ?: continue
                    for (instruction in impl.instructions) {
                        if (instruction !is ReferenceInstruction) continue
                        val reference = instruction.reference
                        if (reference !is StringReference) continue
                        val value = reference.string
                        val m = LIB_REF_PATTERN.matcher(value)
                        if (!m.matches()) continue
                        val stem = m.group(1)
                        if (stem.contains('/')) continue
                        val full = "lib$stem.so"
                        if (full == value.replace("//", "/") || full == value) {
                            refs.add(full)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            return emptyList()
        }
        return refs.toList()
    }

    private fun rewriteDex(dexFile: File, oldSoName: String, newSoName: String) {
        val oldPlain = oldSoName
        val oldSlash = "/$oldSoName"
        val newPlain = newSoName
        val newSlash = "/$newSoName"
        val opcodes = Opcodes.getDefault()
        val dex = DexFileFactory.loadDexFile(dexFile, opcodes)
        val newClasses = ArrayList<ClassDef>()
        for (classDef in dex.classes) {
            var classDirty = false
            val newMethods = ArrayList<Method>()
            for (method in classDef.methods) {
                val impl = method.implementation
                if (impl == null) {
                    newMethods.add(method)
                    continue
                }
                val newInstructions = ArrayList<Instruction>()
                var methodDirty = false
                for (instruction in impl.instructions) {
                    if (instruction !is ReferenceInstruction || instruction !is OneRegisterInstruction) {
                        newInstructions.add(instruction)
                        continue
                    }
                    val reference = instruction.reference
                    if (reference !is StringReference) {
                        newInstructions.add(instruction)
                        continue
                    }
                    val value = reference.string
                    val newValue = when (value) {
                        oldPlain -> newPlain
                        oldSlash -> newSlash
                        else -> null
                    }
                    if (newValue == null) {
                        newInstructions.add(instruction)
                        continue
                    }
                    methodDirty = true
                    classDirty = true
                    val insnOpcode = (instruction as Instruction).opcode
                    val replacement: Instruction = when (insnOpcode) {
                        com.android.tools.smali.dexlib2.Opcode.CONST_STRING_JUMBO ->
                            ImmutableInstruction31c(
                                com.android.tools.smali.dexlib2.Opcode.CONST_STRING_JUMBO,
                                instruction.registerA,
                                ImmutableStringReference(newValue)
                            )
                        else ->
                            ImmutableInstruction21c(
                                com.android.tools.smali.dexlib2.Opcode.CONST_STRING,
                                instruction.registerA,
                                ImmutableStringReference(newValue)
                            )
                    }
                    newInstructions.add(replacement)
                }
                if (methodDirty) {
                    val newImpl = ImmutableMethodImplementation(
                        impl.registerCount,
                        newInstructions,
                        impl.tryBlocks,
                        impl.debugItems
                    )
                    newMethods.add(
                        ImmutableMethod(
                            method.definingClass, method.name, method.parameters, method.returnType,
                            method.accessFlags, method.annotations, method.hiddenApiRestrictions, newImpl
                        )
                    )
                } else {
                    newMethods.add(method)
                }
            }
            val newClass = if (classDirty) {
                ImmutableClassDef(
                    classDef.type, classDef.accessFlags, classDef.superclass, classDef.interfaces,
                    classDef.sourceFile, classDef.annotations, classDef.fields, newMethods
                )
            } else {
                classDef
            }
            newClasses.add(newClass)
        }
        val immutableDex = ImmutableDexFile(dex.opcodes, newClasses)
        DexFileFactory.writeDexFile(dexFile.absolutePath, immutableDex)
    }
}