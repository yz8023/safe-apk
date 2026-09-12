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
    // 按 dex 分桶的 code_offset 出现次数统计。原先使用单一共享 map 并在每次
    // saveCodeOffAppear 中 clear()：阶段3 并行抽取时，各 dex 线程的 clear 会互相
    // 冲掉对方刚写入的计数，使共享 code_off 的判定在 0/1/2 间随机抖动。被误判为
    // unique 的共享方法会被抽取入池，而壳 so 按「跳过共享 code 方法」的顺序消费
    // 池条目，池内多出的条目会让后续方法的还原位全部向后错位，形成 VerifyError。
    // 改为 inner 桶后各 dex 独立统计，且不再在抽取过程中清空其它 dex 的数据。
    private val codeOffAppearMap = ConcurrentHashMap<Int, ConcurrentHashMap<Int, Int>>()
    private val KEEP_IN_PLACE_PREFIXES = arrayOf("Landroidx/compose/")

    /**
     * 读取 dex 文件头的版本号（第 4-7 字节，如 038 / 035）。
     * 用于按原始版本构造 Opcodes，避免写回时 dexlib2 把高版本 dex 降级。
     */
    fun readDexVersion(dexFile: File): Int {
        return try {
            RandomAccessFile(dexFile, "r").use { raf ->
                val header = ByteArray(8)
                raf.seek(0)
                raf.read(header)
                // dex 头 "dex\n038\0"，版本号占据字节 4-6，"038" 三字节，索引 7 为 NUL
                val verStr = String(header, 4, 3, Charsets.US_ASCII).trim()
                verStr.toIntOrNull() ?: 35
            }
        } catch (e: Exception) {
            35
        }
    }

    /**
     * 按文件头版本加载 dex 文件，保留原始 dex version（Opcodes.getDefault() 会把 038 等
     * 高版本归一化为 api=20/version 035，导致写回时丢失接口 default methods 等新特性）。
     */
    fun loadDexPreservingVersion(dexFile: File): DexBackedDexFile {
        return DexFileFactory.loadDexFile(dexFile, Opcodes.forDexVersion(readDexVersion(dexFile)))
    }

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
            val dex = loadDexPreservingVersion(dexFile)
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
        val dexBackedDexFile = loadDexPreservingVersion(originDex)
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
        val bucket = ConcurrentHashMap<Int, Int>()
        val classDefs = dex.classDefs()
        for (classDef in classDefs) {
            val classDataOffset = classDef.classDataOffset
            if (classDataOffset == 0) continue
            val classData = dex.readClassData(classDef)
            for (method in classData.allMethods()) {
                if (method.codeOffset == 0) continue
                bucket.merge(method.codeOffset, 1) { a, b -> a + b }
            }
        }
        codeOffAppearMap[dexIndex] = bucket
    }

    @Throws(IOException::class)
    fun renamePackageName(dexFilePath: File, newDexFilePath: File, slashShellPackageName: String) {
        val dexBackedDexFile = loadDexPreservingVersion(dexFilePath)
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
            val appearCount = codeOffAppearMap[dexIndex]?.get(codeOff)
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
        // 流式复制原始 dex 到输出文件：原先 readFile(整 dex ByteArray) + writeFile 会让大 dex
        // （30-100MB APK 的 classes.dex 可达 20-40MB+）额外驻留一份完整字节数组，叠加 dexlib2
        // 二次读入与 RandomAccessFile 句柄，内存放大明显。copyFile 走流式，不产生整 dex 副本。
        FrostIoUtils.copyFile(dexFile.absolutePath, outDexFile.absolutePath)
        val dumpJSON = if (dumpCode) JSONArray() else null
        try {
            dex = Dex(dexFile)
            val dexNumber = getDexNumber(dexFile.name)
            randomAccessFile = RandomAccessFile(outDexFile, "rw")
            val classDefs = dex.classDefs()
            saveCodeOffAppear(dex, dexNumber)
            for (classDef in classDefs) {
                if (classDef.classDataOffset == 0) {
                    FrostLogUtils.noisy("class '%s' data offset is zero", classDef.toString())
                    continue
                }
                // 排除判断使用 dex.typeNames() 得到的纯 descriptor（"Lxxx/yyy;"），而非
                // classDef.toString()：dx 库该实现返回 "类型名 extends 父类名" 带后缀，
                // 前缀型规则（Landroidx/.* 等）可被 .* 吞掉尾巴而侥幸命中，但精确类名规则
                // （无 .* 通配）会因 extends 尾巴导致 matches() 失败，保护类被错误抽取。
                // 统一用纯 descriptor 保证两类规则都精确匹配。
                //
                // 注意：此处不按 excludeRules/matchRules 跳过整类。壳 so 在还原时按「所有有
                // code 的方法」顺序盲消费指令池条目（无 stub 检测，仅以池条目自带 methodIndex
                // 作为 RC4 解密密钥）。只要某个 dex 内存在任一有 code 的方法未入池（无论被
                // methodFilter 还是排除类规则跳过），该 dex 的池块即为「非全量」，顺序消费立即
                // 错位：未入池方法会抢走相邻池条目、被覆写为其他方法的指令，还原后的方法头
                // outs_size 与指令需求不匹配，class 加载抛 VerifyError「invalid argument count
                // exceeds outsSize」。真实场景中 Android 应用 dex 几乎必然混排第三方类（kotlin/
                // androidx/gson 等），excludeRules 类级跳过会让主 dex 变成非全量块，与 methodFilter
                // 一样触发错位，故抽取阶段必须全类全量抽取。
                // 关键词/类级排除仍作用于字符串加密、混淆、类字段重命名等其它 pass（它们按类名
                // 独立处理、不影响方法体抽取的顺序对齐）。
                val className = dex.typeNames()[classDef.typeIndex]
                val classJSONObject = if (dumpCode) JSONObject() else null
                val classJSONArray = if (dumpCode) JSONArray() else null
                val classData = dex.readClassData(classDef)
                val humanizeTypeName = FrostTypeUtils.getHumanizeTypeName(className)
                for (method in classData.allMethods()) {
                    if (getCodeOffAppearCount(dexNumber, method.codeOffset) > 1) {
                        FrostLogUtils.noisy("codeoff 0x%x appear many times", method.codeOffset)
                        continue
                    }
                    // 注意：此处不做 methodFilter 跳过。指令池是「被抽取方法」的子集，而壳 so 在还原时按
                    // 「所有有 code 的方法」顺序盲消费池条目（无 stub 检测）。只要任一有 code 的方法未被抽取，
                    // 池消费指针就会错位：未匹配方法会吃到下一条池条目、被覆写为其他方法的指令，还原后的
                    // 方法头 outs_size 与指令需求不匹配，类加载抛 VerifyError「invalid argument count exceeds
                    // outsSize」。基线全量抽取与该契约一致、可正常工作，故 methodFilter 规则在此阶段不生效，
                    // 一律全量抽取 + stub + 入池，确保池与 so 消费顺序一一对齐。
                    // methodFilter 语义如需保留，需要 so 支持按 methodIndex 精确查找（当前 so 不具备）。
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
        }
        // stub 必须为合法指令序列：原实现 obfuscateIns 时填充随机字节，随机字节会被 ART
        // verifier 解析为非法 invoke（参数寄存器数超过方法头 outs_size），类加载即抛
        // VerifyError「invalid argument count exceeds outsSize」。改为 return 指令 + NOP 填充，
        // reproduce obfuscateIns 时为 return 前掺入随机数量 NOP，破坏"清一色 return"模式。
        val stub = ByteArray(insnsCapacity * 2)
        var cursor = 0
        if (obfuscateIns) {
            val leadNops = insRandom.nextInt((insnsCapacity * 2 - returnByteCodes.size) / 2 + 1)
            for (k in 0 until leadNops) {
                stub[cursor++] = 0
                stub[cursor++] = 0
            }
        }
        System.arraycopy(returnByteCodes, 0, stub, cursor, returnByteCodes.size)
        cursor += returnByteCodes.size
        while (cursor < stub.size) {
            stub[cursor++] = 0
            stub[cursor++] = 0
        }
        outRandomAccessFile.seek(insnsOffset.toLong())
        outRandomAccessFile.write(stub, 0, stub.size)
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
