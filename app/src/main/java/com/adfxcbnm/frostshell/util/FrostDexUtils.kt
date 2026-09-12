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
            randomAccessFile = RandomAccessFile(outDexFile, "rw")
            val classDefs = dex.classDefs()
            // 关键修复（v9.10.46）：壳 so 还原时按「方法记录的 method_index」直接索引池条目
            // vector（见 so 0xa0868 vector[methodIndex]），并非按池文件顺序消费。因此：
            //  1. 池条目必须按 method_index 升序排列（由 FrostMultiDexCodeUtils 排序保证）；
            //  2. method_ids 中的空洞（abstract/native 无 code、insns 为空的方法）必须用
            //     size=0 的占位条目补齐，否则 vector 紧实排列后 method_index 索引错位，
            //     非 abstract 方法会拿到相邻方法的条目，还原出的指令与 outs_size 不匹配，
            //     类加载抛 VerifyError「invalid argument count exceeds outsSize」。
            //     实测 palm classes.dex(61234 有 code 方法)：未排序池按 method_index 索引
            //     仅 9/61234 命中，错位率 99.985%。
            //  3. 共享 code_item 的后续方法：第一次出现时抽取并 stub，之后的同名 codeOff
            //     必须用缓存的原始指令入池（读取已被 stub 的区会拿到 return 序列）。
            //  4. 极小方法（insns 连对应 return 序列都放不下）：不 stub、保持原始指令，
            //     仍入池；so 还原写回原始指令，行为等价无操作。
            val originalInsnsCache = HashMap<Int, ByteArray>()
            // v9.10.46 两遍结构：
            //  第一遍按 class_data 顺序遍历所有方法，抽取指令、stub 写回、加密，按
            //  method_index 存入 map（abstract/native/空指令方法不建条目，第二遍统一补占位）。
            //  第二遍按 method_ids 全表 0..methodCount 依次出池条目：map 命中的用已抽取
            //  条目，未命中的用 size=0 占位。这样池条目数恒等于 method_ids 总数，且按
            //  method_index 天然升序、完全连续。之所以不能只依赖 class_data 遍历：method_ids
            //  中存在未在任何 class_data 声明的方法索引（实测 palm classes.dex 65441 个
            //  method_ids 中 4207 个索引在 class_data 中缺失），若只遍历 class_data 生成条目，
            //  vector 紧实排列后索引仍会错位。
            val entriesByIndex = HashMap<Int, Instruction>()
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
                val className = dex.typeNames()[classDef.typeIndex]
                val classJSONObject = if (dumpCode) JSONObject() else null
                val classJSONArray = if (dumpCode) JSONArray() else null
                val classData = dex.readClassData(classDef)
                val humanizeTypeName = FrostTypeUtils.getHumanizeTypeName(className)
                for (method in classData.allMethods()) {
                    val methodIndex = method.methodIndex
                    if (method.codeOffset == 0) {
                        // abstract/native 方法无 code：不建条目，第二遍按 method_ids 补占位
                        continue
                    }
                    val code = dex.readCode(method)
                    val insnsCapacity = code.instructions.size
                    if (insnsCapacity == 0) {
                        // 无指令序列：不建条目，第二遍补占位
                        continue
                    }
                    val insnsOffset = method.codeOffset + 16
                    val insnsSize = insnsCapacity * 2
                    val byteCode = ByteArray(insnsSize)
                    val cached = originalInsnsCache[method.codeOffset]
                    if (cached != null) {
                        // 共享 code_item：第一次出现时已抽取并 stub，此处用缓存原始指令入池
                        System.arraycopy(cached, 0, byteCode, 0, cached.size)
                    } else {
                        randomAccessFile.seek(insnsOffset.toLong())
                        randomAccessFile.readFully(byteCode)
                        originalInsnsCache[method.codeOffset] = byteCode.clone()
                        val returnTypeName = dex.typeNames()[dex.protoIds()[dex.methodIds()[methodIndex].protoIndex].returnTypeIndex]
                        val returnByteCodes = getReturnByteCodes(returnTypeName)
                        if (insnsSize >= returnByteCodes.size) {
                            // stub：return 指令 + NOP 填充（obfuscate 时 return 前掺入随机 NOP，
                            // 破坏"清一色 return"模式）
                            val stub = ByteArray(insnsSize)
                            var cursor = 0
                            if (!smaller) {
                                val leadNops = SecureRandom().nextInt((insnsSize - returnByteCodes.size) / 2 + 1)
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
                            randomAccessFile.seek(insnsOffset.toLong())
                            randomAccessFile.write(stub, 0, stub.size)
                        }
                        // insnsSize < returnByteCodes.size 的极小方法：不 stub，保持原始指令
                    }
                    val aesKey = FrostShellConfig.getInstance().getInsnsCryptKey()!!
                    val rc4Key = FrostCryptoUtils.buildInsnsRc4Key(aesKey, methodIndex)
                    val encrypted = FrostCryptoUtils.rc4Crypt(rc4Key, byteCode)
                    if (encrypted == null || encrypted.size != byteCode.size) {
                        throw IllegalStateException("rc4 encrypt insns failed")
                    }
                    val instruction = Instruction()
                    instruction.methodIndex = methodIndex
                    instruction.instructionDataSize = insnsSize
                    instruction.instructionsData = encrypted
                    entriesByIndex[methodIndex] = instruction
                    if (dumpCode && classJSONArray != null) {
                        putToJSON(classJSONArray, instruction)
                    }
                }
                if (dumpCode && classJSONObject != null && dumpJSON != null) {
                    classJSONObject.put(humanizeTypeName, classJSONArray)
                    dumpJSON.put(classJSONObject)
                }
            }
            // 第二遍：按 method_ids 全表顺序出池条目，method_index 天然升序且完全连续。
            // 未在 class_data 中声明的 method_index（抽象/本地方法、孤立索引）补 size=0 占位，
            // 使壳 so 的 vector[methodIndex] 索引永不越界、永不取错相邻方法的条目。
            val methodCount = dex.methodIds().size
            for (methodIndex in 0 until methodCount) {
                instructionList.add(entriesByIndex[methodIndex] ?: makePlaceholder(methodIndex))
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

    /**
     * 占位条目：method_ids 中无 code 的方法（abstract/native 或空指令序列）生成的池条目。
     * size=0 的数据区让壳 so 以 method_index 直接索引池条目 vector 时保持对齐。
     */
    private fun makePlaceholder(methodIndex: Int): Instruction {
        val instruction = Instruction()
        instruction.methodIndex = methodIndex
        instruction.instructionDataSize = 0
        instruction.instructionsData = ByteArray(0)
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
