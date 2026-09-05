package com.adfxcbnm.frostshell.util

import com.adfxcbnm.frostshell.config.FrostProtectRules
import com.adfxcbnm.frostshell.config.FrostShellConfig
import com.adfxcbnm.frostshell.dex.FrostReflectionClinitInjector
import com.adfxcbnm.frostshell.dex.RewrittenDexFile
import com.adfxcbnm.frostshell.model.Instruction
import com.android.dex.ClassData
import com.android.dex.ClassDef
import com.android.dex.Code
import com.android.dex.Dex
import com.android.dex.DexException
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.DexFile
import com.android.tools.smali.dexlib2.immutable.ImmutableDexFile
import com.android.tools.smali.dexlib2.rewriter.DexRewriter
import com.android.tools.smali.dexlib2.rewriter.Rewriter
import com.android.tools.smali.dexlib2.rewriter.RewriterModule
import com.android.tools.smali.dexlib2.rewriter.Rewriters
import com.android.tools.smali.dexlib2.rewriter.TypeRewriter
import org.apache.commons.lang3.tuple.ImmutablePair
import org.apache.commons.lang3.tuple.Pair
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern

object FrostDexUtils {
    private val codeOffAppearMap = ConcurrentHashMap<String, Int>()
    private val KEEP_IN_PLACE_PREFIXES = arrayOf("Landroidx/compose/")

    private fun keepInPlace(type: String?): Boolean {
        if (type == null) return false
        for (prefix in KEEP_IN_PLACE_PREFIXES) {
            if (!type.startsWith(prefix)) continue
            return true
        }
        return false
    }

    fun dexContainsKeepInPlace(dexFile: File): Boolean {
        return try {
            val dex = DexFileFactory.loadDexFile(dexFile, Opcodes.getDefault())
            for (classDef in dex.classes) {
                if (!keepInPlace(classDef.type)) continue
                return true
            }
            false
        } catch (e: Exception) {
            FrostLogUtils.warn("check keep-in-place failed for %s, will skip split to be safe: %s", dexFile.name, e)
            true
        }
    }

    @Throws(IOException::class)
    fun injectInvokeMethod(inputDex: String, outputDex: String, jniClass: String) {
        FrostReflectionClinitInjector.inject(inputDex, outputDex, jniClass)
    }

    @Throws(IOException::class)
    fun splitDex(originDex: File, keepDex: File, splitDex: File): Pair<Int, Int> {
        val totalClassesCount = AtomicInteger()
        val keepClassesCount = AtomicInteger()
        val protectRules = FrostProtectRules.getInstance()
        val dexBackedDexFile = DexFileFactory.loadDexFile(originDex, Opcodes.getDefault())
        val keepRewriter = DexRewriter(object : RewriterModule() {
            override fun getDexFileRewriter(rewriters: Rewriters): Rewriter<DexFile> {
                return Rewriter { value ->
                    val classes: Set<com.android.tools.smali.dexlib2.iface.ClassDef> = value.classes
                    totalClassesCount.set(classes.size)
                    val newClasses = HashSet<com.android.tools.smali.dexlib2.iface.ClassDef>()
                    for (classDef in classes) {
                        if (!protectRules.matchRules(classDef.type)) continue
                        newClasses.add(classDef)
                        keepClassesCount.incrementAndGet()
                    }
                    RewrittenDexFile(value.opcodes, ArrayList(newClasses))
                }
            }
        })
        val keepDexFile = keepRewriter.getDexFileRewriter().rewrite(dexBackedDexFile)
        DexFileFactory.writeDexFile(keepDex.absolutePath, keepDexFile)
        val splitRewriter = DexRewriter(object : RewriterModule() {
            override fun getDexFileRewriter(rewriters: Rewriters): Rewriter<DexFile> {
                return Rewriter { value ->
                    val classes: Set<com.android.tools.smali.dexlib2.iface.ClassDef> = value.classes
                    val newClasses = HashSet<com.android.tools.smali.dexlib2.iface.ClassDef>()
                    for (classDef in classes) {
                        if (protectRules.matchRules(classDef.type)) continue
                        newClasses.add(classDef)
                    }
                    RewrittenDexFile(value.opcodes, ArrayList(newClasses))
                }
            }
        })
        val splitDexFile = splitRewriter.getDexFileRewriter().rewrite(dexBackedDexFile)
        DexFileFactory.writeDexFile(splitDex.absolutePath, splitDexFile)
        return ImmutablePair(keepClassesCount.get(), totalClassesCount.get())
    }

    private fun saveCodeOffAppear(dex: Dex, dexIndex: Int) {
        codeOffAppearMap.clear()
        val classDefs = dex.classDefs()
        for (classDef in classDefs) {
            val classDataOffset = classDef.classDataOffset
            if (classDataOffset == 0) continue
            val classData = dex.readClassData(classDef)
            for (method in classData.allMethods()) {
                if (method.codeOffset == 0) continue
                codeOffAppearMap.merge(dexIndex.toString() + "_" + method.codeOffset, 1) { a, b -> a + b }
            }
        }
    }

    @Throws(IOException::class)
    fun renamePackageName(dexFilePath: File, newDexFilePath: File, slashShellPackageName: String) {
        val dexBackedDexFile = DexFileFactory.loadDexFile(dexFilePath, Opcodes.getDefault())
        FrostLogUtils.debug("Rename shell package name to: " + slashShellPackageName)
        val dexMethodRewriter = DexRewriter(object : RewriterModule() {
            override fun getTypeRewriter(rewriters: Rewriters): Rewriter<String> {
                return object : TypeRewriter() {
                    override fun rewrite(value: String): String {
                        val index = value.lastIndexOf("/")
                        val className = value.substring(index + 1, value.length - 1)
                        if (value.startsWith("Lcom/ironshell")) {
                            return String.format(Locale.US, "L%s/%s;", slashShellPackageName, className)
                        }
                        return value
                    }
                }
            }
        })
        val dexFile = dexMethodRewriter.getDexFileRewriter().rewrite(dexBackedDexFile)
        DexFileFactory.writeDexFile(newDexFilePath.absolutePath, dexFile)
    }

    private fun getCodeOffAppearCount(dexIndex: Int, codeOff: Int): Int {
        return try {
            val appearCount = codeOffAppearMap[dexIndex.toString() + "_" + codeOff]
            appearCount ?: 0
        } catch (e: Exception) {
            e.printStackTrace()
            0
        }
    }

    fun getDexNumber(dexName: String): Int {
        val pattern = Pattern.compile("classes(\\d*)\\.dex$")
        val matcher = pattern.matcher(dexName)
        if (matcher.find()) {
            val dexNo = matcher.group(1)
            return if (dexNo == null || dexNo.isEmpty()) 0 else Integer.parseInt(dexNo) - 1
        }
        return -1
    }

    fun extractAllMethods(dexFile: File, outDexFile: File, packageName: String, dumpCode: Boolean, smaller: Boolean): List<Instruction> {
        val instructionList = ArrayList<Instruction>()
        var dex: Dex? = null
        var randomAccessFile: RandomAccessFile? = null
        val dexData = FrostIoUtils.readFile(dexFile.absolutePath)
        FrostIoUtils.writeFile(outDexFile.absolutePath, dexData)
        val dumpJSON = if (dumpCode) JSONArray() else null
        try {
            dex = Dex(dexFile)
            val dexNumber = getDexNumber(dexFile.name)
            randomAccessFile = RandomAccessFile(outDexFile, "rw")
            val classDefs = dex.classDefs()
            saveCodeOffAppear(dex, dexNumber)
            for (classDef in classDefs) {
                if (FrostProtectRules.getInstance().matchRules(classDef.toString())) continue
                if (classDef.classDataOffset == 0) {
                    FrostLogUtils.noisy("class '%s' data offset is zero", classDef.toString())
                    continue
                }
                val classJSONObject = if (dumpCode) JSONObject() else null
                val classJSONArray = if (dumpCode) JSONArray() else null
                val classData = dex.readClassData(classDef)
                val className = dex.typeNames()[classDef.typeIndex]
                val humanizeTypeName = FrostTypeUtils.getHumanizeTypeName(className)
                for (method in classData.allMethods()) {
                    if (getCodeOffAppearCount(dexNumber, method.codeOffset) > 1) {
                        FrostLogUtils.noisy("codeoff 0x%x appear many times", method.codeOffset)
                        continue
                    }
                    if (!FrostProtectRules.getInstance().shouldExtractMethod(className, dex.strings()[dex.methodIds()[method.methodIndex].nameIndex])) {
                        FrostLogUtils.noisy(
                            "method not matched, name = %s.%s (按规则保留原始指令)",
                            FrostTypeUtils.getHumanizeTypeName(className),
                            dex.strings()[dex.methodIds()[method.methodIndex].nameIndex]
                        )
                        continue
                    }
                    val instruction = extractMethod(dex, randomAccessFile, classDef, method, smaller)
                    if (instruction == null) continue
                    instructionList.add(instruction)
                    if (dumpCode && classJSONArray != null) {
                        putToJSON(classJSONArray, instruction)
                    }
                }
                if (dumpCode && classJSONObject != null && dumpJSON != null) {
                    classJSONObject.put(humanizeTypeName, classJSONArray)
                    dumpJSON.put(classJSONObject)
                }
            }
        } catch (e: Exception) {
            FrostIoUtils.close(randomAccessFile)
            if (dumpCode && dumpJSON != null) {
                dumpJSON(packageName, dexFile, dumpJSON)
            }
            e.printStackTrace()
        } finally {
            FrostIoUtils.close(randomAccessFile)
        }
        if (dumpCode && dumpJSON != null) {
            dumpJSON(packageName, dexFile, dumpJSON)
        }
        return instructionList
    }

    private fun dumpJSON(packageName: String, originFile: File, array: JSONArray) {
        val pkg = File(packageName)
        if (!pkg.exists()) {
            pkg.mkdirs()
        }
        val writePath = File(pkg.absolutePath, originFile.name + ".json")
        FrostLogUtils.info("dump json to path: %s", writePath.parentFile.name + File.separator + writePath.name)
        FrostIoUtils.writeFile(writePath.absolutePath, array.toString(1).toByteArray())
    }

    private fun putToJSON(array: JSONArray, instruction: Instruction) {
        val jsonObject = JSONObject()
        val hex = FrostHexUtils.toHexArray(instruction.instructionsData)
        jsonObject.put("methodId", instruction.methodIndex)
        jsonObject.put("code", hex)
        array.put(jsonObject)
    }

    @Throws(Exception::class)
    private fun extractMethod(
        dex: Dex,
        outRandomAccessFile: RandomAccessFile,
        classDef: ClassDef,
        method: ClassData.Method,
        obfuscateIns: Boolean
    ): Instruction? {
        val returnTypeName = dex.typeNames()[dex.protoIds()[dex.methodIds()[method.methodIndex].protoIndex].returnTypeIndex]
        val methodName = dex.strings()[dex.methodIds()[method.methodIndex].nameIndex]
        val className = dex.typeNames()[classDef.typeIndex]
        if (method.codeOffset == 0) {
            FrostLogUtils.noisy(
                "method code offset is zero,name =  %s.%s , returnType = %s",
                FrostTypeUtils.getHumanizeTypeName(className), methodName, FrostTypeUtils.getHumanizeTypeName(returnTypeName)
            )
            return null
        }
        val instruction = Instruction()
        val insnsOffset = method.codeOffset + 16
        val code = dex.readCode(method)
        if (code.instructions.size == 0) {
            FrostLogUtils.noisy(
                "method has no code,name =  %s.%s , returnType = %s",
                FrostTypeUtils.getHumanizeTypeName(className), methodName, FrostTypeUtils.getHumanizeTypeName(returnTypeName)
            )
            return null
        }
        val insnsCapacity = code.instructions.size
        val returnByteCodes = getReturnByteCodes(returnTypeName)
        if (insnsCapacity * 2 < returnByteCodes.size) {
            FrostLogUtils.noisy(
                "The capacity of insns is not enough to store the return statement. %s.%s() ClassIndex = %d -> %s insnsCapacity = %d byte(s) but returnByteCodes = %d byte(s)",
                FrostTypeUtils.getHumanizeTypeName(className), methodName, classDef.typeIndex,
                FrostTypeUtils.getHumanizeTypeName(returnTypeName), insnsCapacity * 2, returnByteCodes.size
            )
            return null
        }
        instruction.methodIndex = method.methodIndex
        instruction.instructionDataSize = insnsCapacity * 2
        val byteCode = ByteArray(insnsCapacity * 2)
        val insRandom = SecureRandom()
        for (i in 0 until insnsCapacity) {
            outRandomAccessFile.seek((insnsOffset + i * 2).toLong())
            byteCode[i * 2] = outRandomAccessFile.readByte()
            byteCode[i * 2 + 1] = outRandomAccessFile.readByte()
            outRandomAccessFile.seek((insnsOffset + i * 2).toLong())
            if (obfuscateIns) {
                outRandomAccessFile.writeShort(insRandom.nextInt())
            } else {
                outRandomAccessFile.writeShort(14)
            }
        }
        val aesKey = FrostShellConfig.getInstance().getInsnsCryptKey()!!
        val rc4Key = FrostCryptoUtils.buildInsnsRc4Key(aesKey, method.methodIndex)
        val encrypted = FrostCryptoUtils.rc4Crypt(rc4Key, byteCode)
        if (encrypted == null || encrypted.size != byteCode.size) {
            throw IllegalStateException("rc4 encrypt insns failed")
        }
        instruction.instructionsData = encrypted
        outRandomAccessFile.seek(insnsOffset.toLong())
        return instruction
    }

    fun getReturnByteCodes(typeName: String): ByteArray {
        val returnVoidCodes = byteArrayOf(14, 0)
        val returnCodes = byteArrayOf(18, 0, 15, 0)
        val returnWideCodes = byteArrayOf(22, 0, 0, 0, 16, 0)
        val returnObjectCodes = byteArrayOf(18, 0, 17, 0)
        return when (typeName) {
            "V" -> returnVoidCodes
            "B", "C", "F", "I", "S", "Z" -> returnCodes
            "D", "J" -> returnWideCodes
            else -> returnObjectCodes
        }
    }

    fun writeHashes(oldDexFile: File, newDexFile: File) {
        val dexData = FrostIoUtils.readFile(oldDexFile.absolutePath)
        try {
            val dex = Dex(dexData)
            dex.writeHashes()
            dex.writeTo(newDexFile)
        } catch (e: Exception) {
            throw DexException("Cannot write dex hashes")
        }
    }

    fun getDexSignature(dexFile: File): String? {
        val dexData = FrostIoUtils.readFile(dexFile.absolutePath)
        if (dexData.size <= 29) {
            return null
        }
        val signature = ByteArray(20)
        val buffer = ByteBuffer.wrap(dexData)
        buffer.position(9)
        buffer.get(signature)
        return FrostHexUtils.toHexString(signature)
    }

    @Throws(IOException::class)
    fun restoreInstructions(dexFile: File, instructions: List<Instruction>) {
        val dex = Dex(dexFile)
        RandomAccessFile(dexFile, "rw").use { randomAccessFile ->
            val classDefs = dex.classDefs()
            var listIndex = 0
            for (classDef in classDefs) {
                val methods = dex.readClassData(classDef).allMethods()
                for (i in methods.indices) {
                    val method = methods[i]
                    val offsetInstructions = method.codeOffset + 16
                    val instruction = instructions[listIndex++]
                    if (instruction.methodIndex != method.methodIndex) continue
                    val byteCode = Base64.getDecoder().decode(instruction.instructionsData)
                    randomAccessFile.seek(offsetInstructions.toLong())
                    randomAccessFile.write(byteCode, 0, byteCode.size)
                }
            }
        }
    }
}
