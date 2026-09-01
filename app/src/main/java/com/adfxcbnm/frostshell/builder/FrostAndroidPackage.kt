package com.adfxcbnm.frostshell.builder

import com.adfxcbnm.frostshell.config.FrostConst
import com.adfxcbnm.frostshell.config.FrostProtectRules
import com.adfxcbnm.frostshell.config.FrostShellConfig
import com.adfxcbnm.frostshell.dex.FrostJunkCodeGenerator
import com.adfxcbnm.frostshell.elf.FrostReadElf
import com.adfxcbnm.frostshell.model.Instruction
import com.adfxcbnm.frostshell.model.MultiDexCode
import com.adfxcbnm.frostshell.task.FrostThreadPool
import com.adfxcbnm.frostshell.util.FrostCryptoUtils
import com.adfxcbnm.frostshell.util.FrostDexUtils
import com.adfxcbnm.frostshell.util.FrostFileUtils
import com.adfxcbnm.frostshell.util.FrostHexUtils
import com.adfxcbnm.frostshell.util.FrostIoUtils
import com.adfxcbnm.frostshell.util.FrostKeyUtils
import com.adfxcbnm.frostshell.util.FrostLogUtils
import com.adfxcbnm.frostshell.util.FrostMultiDexCodeUtils
import com.adfxcbnm.frostshell.util.FrostStringUtils
import com.adfxcbnm.frostshell.util.FrostZipUtils
import com.alibaba.fastjson2.JSON
import com.google.common.io.Files as GuavaFiles
import com.iyxan23.zipalignjava.ZipAlign
import net.lingala.zip4j.model.enums.CompressionMethod
import org.apache.commons.lang3.tuple.Pair
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.Certificate
import java.util.ArrayList
import java.util.Arrays
import java.util.Comparator
import java.util.HashMap
import java.util.Locale
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.Deflater

abstract class FrostAndroidPackage protected constructor(builder: Builder) {
    private var filePath: String? = null
    private var packageName: String? = null
    private var debuggable = false
    private var sign = true
    private var appComponentFactory = true
    private var dumpCode = false
    private var smaller = false
    private var excludedAbi: List<String>? = null
    private var outputPath: String? = null
    private var rulesFilePath: String? = null
    private var keepClasses = false
    private var protectConfigFile: String? = null
    private var verifySign = false
    private var riskCheckFlags = 0

    init {
        this.filePath = builder.filePath
        this.debuggable = builder.debuggable
        this.appComponentFactory = builder.appComponentFactory
        this.sign = builder.sign
        this.packageName = builder.packageName
        this.dumpCode = builder.dumpCode
        this.excludedAbi = builder.excludedAbi
        this.outputPath = builder.outputPath
        this.rulesFilePath = builder.rulesFilePath
        this.keepClasses = builder.keepClasses
        this.smaller = builder.smaller
        this.protectConfigFile = builder.protectConfigFile
        this.verifySign = builder.verifySign
        this.riskCheckFlags = builder.riskCheckFlags
    }

    fun setProtectConfigFile(protectConfigFile: String?) {
        this.protectConfigFile = protectConfigFile
    }

    fun getProtectConfigFile(): String? = protectConfigFile

    fun setVerifySign(verifySign: Boolean) {
        this.verifySign = verifySign
    }

    fun isVerifySign(): Boolean = verifySign

    fun getRiskCheckFlags(): Int = riskCheckFlags

    fun setRiskCheckFlags(riskCheckFlags: Int) {
        this.riskCheckFlags = riskCheckFlags
    }

    fun isSmaller(): Boolean = smaller

    fun setSmaller(smaller: Boolean) {
        this.smaller = smaller
    }

    private fun setKeepClasses(keepClasses: Boolean) {
        this.keepClasses = keepClasses
    }

    fun isKeepClasses(): Boolean = keepClasses

    private fun setRulesFilePath(rulesFilePath: String?) {
        this.rulesFilePath = rulesFilePath
    }

    fun getRulesFilePath(): String? = rulesFilePath

    fun getOutputPath(): String? = outputPath

    fun setOutputPath(outputPath: String?) {
        this.outputPath = outputPath
    }

    fun setExcludedAbi(excludedAbi: List<String>?) {
        this.excludedAbi = excludedAbi
    }

    fun getFilePath(): String? = filePath

    fun setFilePath(filePath: String?) {
        this.filePath = filePath
    }

    fun isDebuggable(): Boolean = debuggable

    fun setDebuggable(debuggable: Boolean) {
        this.debuggable = debuggable
    }

    fun isSign(): Boolean = sign

    fun setSign(sign: Boolean) {
        this.sign = sign
    }

    fun isAppComponentFactory(): Boolean = appComponentFactory

    fun setAppComponentFactory(appComponentFactory: Boolean) {
        this.appComponentFactory = appComponentFactory
    }

    fun getPackageName(): String? = packageName

    fun setPackageName(packageName: String?) {
        this.packageName = packageName
    }

    fun setDumpCode(dumpCode: Boolean) {
        this.dumpCode = dumpCode
    }

    fun isDumpCode(): Boolean = dumpCode

    protected fun combineDexZipWithShellDex(packageMainProcessPath: String, masterKey: ByteArray) {
        try {
            val shellDexFile = File(getProxyDexPath())
            val renameDexFile = File(getRenameDexPath())
            val shellConfig = FrostShellConfig.getInstance()
            val needRename = !FrostStringUtils.isBlank(shellConfig.getShellPackageName()) &&
                FrostConst.DEFAULT_SHELL_PACKAGE_NAME != shellConfig.getShellPackageName()
            if (needRename) {
                FrostDexUtils.renamePackageName(shellDexFile, renameDexFile, shellConfig.getSlashShellPackageName())
            }
            val originalDexZipFile = File(getOutAssetsDir(packageMainProcessPath).absolutePath + File.separator + FrostConst.KEY_DEXES_STORE_NAME)
            val zipData = FrostIoUtils.readFile(originalDexZipFile.absolutePath)
            val unShellDexArray = FrostIoUtils.readFile((if (!needRename) shellDexFile else renameDexFile).absolutePath)
            val dexZipKey = Arrays.copyOfRange(FrostCryptoUtils.deriveKey(masterKey, FrostConst.LABEL_DEXZIP_KEY), 0, 16)
            val deflated = deflateBytes(zipData)
            val encZip = FrostCryptoUtils.rc4Crypt(dexZipKey, deflated)
            if (encZip == null || encZip.size != deflated.size) {
                throw IllegalStateException("encrypt dex zip failed")
            }
            FrostLogUtils.info("Dexes zip deflate: %d -> %d bytes", zipData.size, deflated.size)
            val zipDataLen = encZip.size
            val tailSalt = FrostKeyUtils.fromHex(shellConfig.getTailSaltHex().orEmpty())
            val tailPad = shellConfig.getTailPad()
            val tailRnd = SecureRandom()
            val unShellDexLen = unShellDexArray.size
            FrostLogUtils.info("Dexes zip file size: %s", zipDataLen)
            FrostLogUtils.info("Proxy dex file size: %s", unShellDexLen)
            val totalLen = zipDataLen + unShellDexLen + 4 + 4 + tailPad
            val newDexBytes = ByteArray(totalLen)
            System.arraycopy(unShellDexArray, 0, newDexBytes, 0, unShellDexLen)
            System.arraycopy(encZip, 0, newDexBytes, unShellDexLen, zipDataLen)
            val padBytes = ByteArray(tailPad)
            tailRnd.nextBytes(padBytes)
            System.arraycopy(padBytes, 0, newDexBytes, unShellDexLen + zipDataLen, tailPad)
            val fakeLen = ByteArray(4)
            tailRnd.nextBytes(fakeLen)
            System.arraycopy(fakeLen, 0, newDexBytes, totalLen - 8, 4)
            val saltInt = (tailSalt[0].toInt() and 0xFF) shl 24 or ((tailSalt[1].toInt() and 0xFF) shl 16) or
                ((tailSalt[2].toInt() and 0xFF) shl 8) or (tailSalt[3].toInt() and 0xFF)
            val obfLen = zipDataLen xor saltInt
            System.arraycopy(FrostFileUtils.intToByte(obfLen), 0, newDexBytes, totalLen - 4, 4)
            FrostFileUtils.fixFileSizeHeader(newDexBytes)
            FrostFileUtils.fixSHA1Header(newDexBytes)
            FrostFileUtils.fixCheckSumHeader(newDexBytes)
            val targetDexFile = getDexDir(packageMainProcessPath) + File.separator + "classes.dex"
            val file = File(targetDexFile)
            if (!file.exists()) {
                file.createNewFile()
            }
            FileOutputStream(targetDexFile).use { localFileOutputStream ->
                localFileOutputStream.write(newDexBytes)
                localFileOutputStream.flush()
            }
            FrostLogUtils.info("New Dex file generated: " + targetDexFile)
            FrostFileUtils.deleteRecurse(originalDexZipFile)
            FrostFileUtils.deleteRecurse(renameDexFile)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun deflateBytes(data: ByteArray): ByteArray {
        val deflater = Deflater(-1)
        deflater.setInput(data)
        deflater.finish()
        val bos = ByteArrayOutputStream(data.size / 2)
        val buf = ByteArray(65536)
        while (!deflater.finished()) {
            val n = deflater.deflate(buf)
            if (n <= 0) continue
            bos.write(buf, 0, n)
        }
        deflater.end()
        return bos.toByteArray()
    }

    private fun getUnsignPackageName(packageFileName: String): String {
        return FrostFileUtils.getNewFileName(packageFileName, "unsign")
    }

    private fun getUnzipalignPackageName(packageFileName: String): String {
        return FrostFileUtils.getNewFileName(packageFileName, "unzipalign")
    }

    private fun getSignedPackageName(packageFileName: String): String {
        return FrostFileUtils.getNewFileName(packageFileName, "signed")
    }

    private fun deriveConfigAesKey(randomKey: ByteArray): ByteArray {
        val packageName = getPackageName()
        if (packageName == null || packageName.isEmpty()) {
            throw IllegalStateException("package name is empty, cannot derive config aes key")
        }
        val buildKey = FrostIronShell.getBuildKey()
        if (buildKey == null || buildKey.isEmpty()) {
            throw IllegalStateException("dpt build key is missing, cannot derive config aes key")
        }
        val keyMaterial = packageName + "_" + buildKey
        return FrostCryptoUtils.hmacSha256(randomKey, keyMaterial)
    }

    fun writeConfig(packageDir: String, key: ByteArray) {
        val configFile = File(getOutAssetsDir(packageDir).absolutePath + File.separator + FrostConst.KEY_SHELL_CONFIG_STORE_NAME)
        val shellConfig = FrostShellConfig.getInstance()
        val json = shellConfig.toJson()
        FrostLogUtils.info("Write config: " + json)
        val bootKey = Arrays.copyOfRange(key, 0, 12)
        val aesKey = deriveConfigAesKey(bootKey)
        val iv = FrostKeyUtils.deriveIV(bootKey, FrostConst.LABEL_CFG_IV)
        val secData = FrostCryptoUtils.aesEncrypt(aesKey, iv, json.toByteArray(StandardCharsets.UTF_8))
        if (secData == null) {
            throw IllegalStateException("encrypt shell config failed")
        }
        FrostIoUtils.writeFile(configFile.absolutePath, secData)
    }

    abstract fun writeProxyAppName(manifestDir: String)

    abstract fun writeProxyComponentFactoryName(manifestDir: String)

    abstract fun setExtractNativeLibs(manifestDir: String)

    abstract fun setDebuggable(manifestDir: String, debuggable: Boolean)

    fun getWorkspaceDir(): File {
        return FrostFileUtils.getDir(FrostConst.ROOT_OF_OUT_DIR, "ironshell-out-" + FrostConst.RANDOM_DIR_NAME)
    }

    fun getLastProcessDir(): File {
        return FrostFileUtils.getDir(FrostConst.ROOT_OF_OUT_DIR, "ironshell-last-" + FrostConst.RANDOM_DIR_NAME)
    }

    protected abstract fun getOutAssetsDir(packageDir: String): File

    abstract fun getLibDir(packageDir: String): String

    abstract fun getDexDir(packageDir: String): String

    fun getKeepDexTempDir(packageDir: String): File {
        return FrostFileUtils.getDir(getDexDir(packageDir), "keep-dex-dir")
    }

    fun getProxyApplicationName(): String {
        return String.format(Locale.US, "%s.%s", FrostShellConfig.getInstance().getShellPackageName(), "ProxyApplication")
    }

    fun getProxyComponentFactory(): String {
        return String.format(Locale.US, "%s.%s", FrostShellConfig.getInstance().getShellPackageName(), "ProxyComponentFactory")
    }

    protected fun getProxyDexPath(): String {
        return FrostFileUtils.getExecutablePath() + File.separator + "shell-files" + File.separator + "dex" + File.separator + "classes.dex"
    }

    protected fun getRenameDexPath(): String {
        return FrostFileUtils.getExecutablePath() + File.separator + "shell-files" + File.separator + "dex" + File.separator + "rename_classes.dex"
    }

    private fun addProxyDex(packageOutDir: String) {
        addDex(getProxyDexPath(), packageOutDir)
    }

    protected fun getJunkCodeDexPath(): String {
        return FrostFileUtils.getExecutablePath() + File.separator + "shell-files" + File.separator + "dex" + File.separator + "junkcode.dex"
    }

    protected fun addJunkCodeDex(packageDir: String) {
        addDex(getJunkCodeDexPath(), getDexDir(packageDir))
    }

    protected fun addKeepDexes(packageDir: String) {
        val keepDexTempDir = getKeepDexTempDir(packageDir)
        val files = keepDexTempDir.listFiles()
        if (files != null) {
            for (file in files) {
                if (!file.name.endsWith(".dex")) continue
                addDex(file.absolutePath, getDexDir(packageDir))
            }
        }
    }

    fun compressDexFiles(packageDir: String) {
        val rulesMap = HashMap<String, CompressionMethod>()
        rulesMap["classes\\d*.dex"] = CompressionMethod.STORE
        val unalignedFilePath = getOutAssetsDir(packageDir).absolutePath + File.separator + FrostConst.KEY_DEXES_STORE_UNALIGNED_NAME
        val alignedFilePath = getOutAssetsDir(packageDir).absolutePath + File.separator + FrostConst.KEY_DEXES_STORE_NAME
        FrostZipUtils.compress(getDexFiles(getDexDir(packageDir)), unalignedFilePath, rulesMap)
        var randomAccessFile: RandomAccessFile? = null
        var out: FileOutputStream? = null
        var isAligned = false
        try {
            randomAccessFile = RandomAccessFile(unalignedFilePath, "r")
            out = FileOutputStream(alignedFilePath)
            ZipAlign.alignZip(randomAccessFile, out as OutputStream)
            FrostIoUtils.close(randomAccessFile)
            FrostIoUtils.close(out)
            org.apache.commons.io.FileUtils.forceDelete(File(unalignedFilePath))
            FrostLogUtils.info("zip aligned: " + alignedFilePath)
            isAligned = true
        } catch (e: Exception) {
            try {
                FrostLogUtils.warn("WARNING: ZipAlign failed: %s", unalignedFilePath)
            } catch (throwable: Throwable) {
                FrostIoUtils.close(randomAccessFile)
                FrostIoUtils.close(out)
                throw throwable
            }
            FrostIoUtils.close(randomAccessFile)
            FrostIoUtils.close(out)
        }
        FrostIoUtils.close(randomAccessFile)
        FrostIoUtils.close(out)
        if (!isAligned) {
            try {
                Files.move(Paths.get(unalignedFilePath), Paths.get(alignedFilePath), StandardCopyOption.REPLACE_EXISTING)
            } catch (e1: Exception) {
                e1.printStackTrace()
            }
        }
    }

    fun copyNativeLibs(packageDir: String) {
        val sourceDirRoot = File(FrostFileUtils.getExecutablePath(), "shell-files" + File.separator + "libs")
        val destDirRoot = File(getOutAssetsDir(packageDir).absolutePath, FrostConst.KEY_LIBS_DIR_NAME)
        if (!destDirRoot.exists()) {
            destDirRoot.mkdirs()
        }
        val abiDirs = sourceDirRoot.listFiles() ?: return
        for (abiDir in abiDirs) {
            if (!abiDir.isDirectory) continue
            val abiName = abiDir.name
            if (excludedAbi != null && excludedAbi!!.contains(abiName)) {
                FrostLogUtils.info("Skipping excluded ABI: " + abiName)
                continue
            }
            val destAbiDir = File(destDirRoot, abiName)
            if (!destAbiDir.exists()) {
                destAbiDir.mkdirs()
            }
            val libFiles = abiDir.listFiles() ?: continue
            for (libFile in libFiles) {
                if (!libFile.isFile || !libFile.name.endsWith(".so")) continue
                val destFile = File(destAbiDir, libFile.name)
                try {
                    Files.copy(libFile.toPath(), destFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                } catch (e: IOException) {
                    FrostLogUtils.error("Failed to copy library: " + e.message)
                }
            }
        }
    }

    fun encryptSoFiles(packageOutDir: String, rc4Key: ByteArray) {
        val obfDir = File(getOutAssetsDir(packageOutDir).absolutePath + File.separator, FrostConst.KEY_LIBS_DIR_NAME)
        val soAbiDirs = obfDir.listFiles() ?: return
        for (soAbiDir in soAbiDirs) {
            val soFiles = soAbiDir.listFiles() ?: continue
            for (soFile in soFiles) {
                if (!soFile.absolutePath.endsWith(".so")) continue
                encryptSoFile(soFile, rc4Key)
                writeSoFileCryptKey(soFile, rc4Key)
            }
        }
    }

    private fun encryptSoFile(soFile: File, rc4Key: ByteArray) {
        try {
            FrostReadElf(soFile).use { readElf ->
                var bitcodeSection = getBuildIdsProperty("bitcodeSection")
                if (bitcodeSection == null || bitcodeSection.isEmpty()) {
                    bitcodeSection = ".bc"
                }
                val sectionHeaders = readElf.getSectionHeaders() ?: return@use
                for (sectionHeader in sectionHeaders) {
                    if (bitcodeSection != sectionHeader.getName()) continue
                    FrostLogUtils.info(
                        "start encrypt %s section: %s, offset: %s, size: %s",
                        soFile.absolutePath, sectionHeader.getName(),
                        FrostHexUtils.toHexString(sectionHeader.getOffset()), sectionHeader.getSize()
                    )
                    val bitcode = FrostIoUtils.readFile(soFile.absolutePath, sectionHeader.getOffset(), sectionHeader.getSize().toInt())
                    val bootKey = Arrays.copyOfRange(rc4Key, 0, 12)
                    val enc = FrostCryptoUtils.rc4Crypt(bootKey, bitcode)
                    if (enc != null) {
                        FrostIoUtils.writeFile(soFile.absolutePath, enc, sectionHeader.getOffset())
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun writeSoFileCryptKey(soFile: File, masterKey: ByteArray) {
        try {
            FrostReadElf(soFile).use { readElf ->
                val shards = arrayOf(
                    Arrays.copyOfRange(masterKey, 0, 4),
                    Arrays.copyOfRange(masterKey, 4, 8),
                    Arrays.copyOfRange(masterKey, 8, 12)
                )
                val shardSymbols = arrayOf("IS_KEY_SHARD1", "IS_KEY_SHARD2", "IS_KEY_SHARD3")
                val decoySymbol = "IS_DECOY_KEY"
                for (i in shards.indices) {
                    writeSymbolData(readElf, soFile, shardSymbols[i], shards[i])
                }
                writeSymbolData(readElf, soFile, decoySymbol, FrostKeyUtils.randomBytes(16))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun writeSymbolData(readElf: FrostReadElf, soFile: File, symbolName: String, data: ByteArray) {
        if (symbolName.isEmpty()) {
            FrostLogUtils.warn("symbol name missing, skip writing shard")
            return
        }
        val symbol = readElf.getDynamicSymbol(symbolName)
        if (symbol == null) {
            FrostLogUtils.warn("cannot find symbol %s in %s", symbolName, soFile.name)
            return
        }
        val value = symbol.value
        val shndx = symbol.shndx
        val sectionHeaders = readElf.getSectionHeaders()
        val sectionHeader = sectionHeaders!![shndx]
        val symbolDataOffset = sectionHeader.getOffset() + value - sectionHeader.getAddr()
        FrostIoUtils.writeFile(soFile.absolutePath, data, symbolDataOffset)
        FrostLogUtils.info("wrote %d bytes to symbol %s @ %s", data.size, symbolName, FrostHexUtils.toHexString(symbolDataOffset))
    }

    private fun getBuildIdsProperty(key: String): String? {
        return try {
            val execPath = FrostFileUtils.getExecutablePath()
            if (execPath.isEmpty()) {
                null
            } else {
                val idsFile = File(execPath, "shell-files" + File.separator + "build-ids.properties")
                if (!idsFile.exists()) {
                    null
                } else {
                    val props = Properties()
                    FileInputStream(idsFile).use { props.load(it) }
                    props.getProperty(key)
                }
            }
        } catch (exception: Exception) {
            null
        }
    }

    fun deleteAllDexFiles(packageDir: String) {
        val dexFiles = getDexFiles(getDexDir(packageDir))
        for (dexFile in dexFiles) {
            dexFile.delete()
        }
    }

    private fun addDex(dexFilePath: String, dexFilesSavePath: String) {
        val dexFile = File(dexFilePath)
        val dexFiles = getDexFiles(dexFilesSavePath)
        val newDexNameNumber = dexFiles.size + 1
        var newDexPath = dexFilesSavePath + File.separator + "classes.dex"
        if (newDexNameNumber > 1) {
            newDexPath = dexFilesSavePath + File.separator + String.format(Locale.US, "classes%d.dex", newDexNameNumber)
        }
        val dexData = FrostIoUtils.readFile(dexFile.absolutePath)
        FrostIoUtils.writeFile(newDexPath, dexData)
    }

    protected abstract fun getManifestFilePath(packageDir: String): String

    abstract fun saveApplicationName(packageDir: String)

    abstract fun saveAppComponentFactory(packageDir: String)

    private fun isSystemComponentFactory(name: String): Boolean {
        return name == "androidx.core.app.CoreComponentFactory" || name == "android.support.v4.app.CoreComponentFactory"
    }

    fun isAndroidPackageFile(f: File): Boolean {
        return f.absolutePath.endsWith(".apk") || f.absolutePath.endsWith(".aab")
    }

    fun extractDexCode(packageDir: String, dexCodeSavePath: String, soKey: ByteArray) {
        val dexFiles = getDexFiles(getDexDir(packageDir))
        val instructionMap = HashMap<Int, List<Instruction>>()
        val shellConfig = FrostShellConfig.getInstance()
        shellConfig.randomizeForProtect()
        shellConfig.setKeyShard4Hex(FrostKeyUtils.toHex(Arrays.copyOfRange(soKey, 12, 16)))
        shellConfig.setInsnsCryptKey(deriveConfigAesKey(Arrays.copyOfRange(soKey, 0, 12)))
        val appNameNew = shellConfig.getInsnsStoreName().orEmpty()
        val dataOutputPath = dexCodeSavePath + File.separator + appNameNew
        val countDownLatch = CountDownLatch(dexFiles.size)
        val totalClassesCount = AtomicInteger(0)
        val keepClassesCount = AtomicInteger(0)
        for (dexFile in dexFiles) {
            FrostThreadPool.execute {
                val dexNo = FrostDexUtils.getDexNumber(dexFile.name)
                if (dexNo < 0) {
                    countDownLatch.countDown()
                    return@execute
                }
                val injectedDexFile = File(dexFile.absolutePath + "_inject.dex")
                try {
                    FrostDexUtils.injectInvokeMethod(dexFile.absolutePath, injectedDexFile.absolutePath, shellConfig.getJniClassNameSig())
                    dexFile.delete()
                    injectedDexFile.renameTo(dexFile)
                } catch (e: Exception) {
                    injectedDexFile.delete()
                }
                if (isKeepClasses() && FrostDexUtils.dexContainsKeepInPlace(dexFile)) {
                    FrostLogUtils.info("Skip split for dex with keep-in-place classes (e.g. Compose): %s", dexFile.name)
                } else if (isKeepClasses()) {
                    val keepDex = File(getKeepDexTempDir(packageDir).absolutePath + File.separator + dexFile.name)
                    val splitDex = File(dexFile.absolutePath + "_split.dex")
                    try {
                        val classesCountPair: Pair<Int, Int> = FrostDexUtils.splitDex(dexFile, keepDex, splitDex)
                        keepClassesCount.set(keepClassesCount.get() + classesCountPair.key)
                        totalClassesCount.set(totalClassesCount.get() + classesCountPair.value)
                        dexFile.delete()
                        splitDex.renameTo(dexFile)
                    } catch (e: Exception) {
                        FrostLogUtils.warn("WARNING: split %s fail", dexFile.name)
                        keepDex.delete()
                        splitDex.delete()
                    }
                }
                val extractedDexName = if (dexFile.name.endsWith(".dex")) dexFile.name.replace(Regex("\\.dex$"), "_extracted.dat") else "_extracted.dat"
                val extractedDexFile = File(dexFile.parent, extractedDexName)
                val obfuscate = !isSmaller()
                val ret = FrostDexUtils.extractAllMethods(dexFile, extractedDexFile, getPackageName().orEmpty(), isDumpCode(), obfuscate)
                instructionMap[dexNo] = ret
                val dexFileRightHashes = File(dexFile.parent, FrostFileUtils.getNewFileSuffix(dexFile.name, "dat"))
                try {
                    FrostDexUtils.writeHashes(extractedDexFile, dexFileRightHashes)
                    dexFile.delete()
                    dexFileRightHashes.renameTo(dexFile)
                } catch (exception: Exception) {
                } finally {
                    if (extractedDexFile.exists()) {
                        extractedDexFile.delete()
                    }
                }
                if ("classes.dex" == dexFile.name) {
                    val dexSignature = FrostDexUtils.getDexSignature(dexFile)
                    FrostShellConfig.getInstance().setDexSign(dexSignature)
                }
                countDownLatch.countDown()
            }
        }
        FrostThreadPool.shutdown()
        try {
            countDownLatch.await()
        } catch (exception: Exception) {
        }
        if (isKeepClasses()) {
            FrostLogUtils.info("Keep classes: %d, total classes: %d", keepClassesCount.get(), totalClassesCount.get())
        }
        val multiDexCode = FrostMultiDexCodeUtils.makeMultiDexCode(instructionMap)
        FrostMultiDexCodeUtils.writeMultiDexCode(dataOutputPath, multiDexCode)
        try {
            val plain = FrostIoUtils.readFile(dataOutputPath)
            val poolKey = FrostCryptoUtils.deriveKey(soKey, FrostConst.LABEL_POOL_KEY)
            val poolIv = FrostKeyUtils.deriveIV(soKey, FrostConst.LABEL_POOL_IV)
            val enc = FrostCryptoUtils.aesEncrypt(poolKey, poolIv, plain)
            if (enc == null) {
                throw IllegalStateException("encrypt instruction pool failed")
            }
            FrostIoUtils.writeFile(dataOutputPath, enc)
            FrostLogUtils.info("Instruction pool encrypted: %d -> %d bytes", plain.size, enc.size)
        } catch (e: Exception) {
            throw IllegalStateException("instruction pool encryption failed", e)
        }
    }

    fun getDexFiles(dir: String): List<File> {
        val dexFiles = ArrayList<File>()
        val dirFile = File(dir)
        val files = dirFile.listFiles()
        if (files != null) {
            Arrays.stream(files).filter { it.name.endsWith(".dex") }.forEach { dexFiles.add(it) }
        }
        dexFiles.sortWith(Comparator.comparingInt<File> { f ->
            val n = FrostDexUtils.getDexNumber(f.name)
            if (n < 0) Integer.MAX_VALUE else n
        })
        return dexFiles
    }

    protected fun buildPackage(originPackagePath: String, unpackFilePath: String, savePath: String) {
        var resultPath: String
        var outputDir: String
        val outputPath = getOutputPath()
        var resultFileName: String? = null
        val shellConfig = FrostShellConfig.getInstance()
        if (outputPath != null) {
            var outputPathFile = File(outputPath)
            if (isAndroidPackageFile(outputPathFile)) {
                outputPathFile = File(if (outputPath.contains(File.separator)) outputPath else "." + File.separator + outputPath)
                outputDir = outputPathFile.parent
                resultFileName = outputPathFile.name
            } else {
                outputDir = outputPath
            }
        } else {
            outputDir = savePath
        }
        val outputDirFile = File(outputDir)
        if (!outputDirFile.exists()) {
            outputDirFile.mkdirs()
        }
        val originPackageName = File(originPackagePath).name
        val packageLastProcessDir = getLastProcessDir().absolutePath
        val unzipalignPackagePath = outputDir + File.separator + (resultFileName?.let { "temp_" + it } ?: getUnzipalignPackageName(originPackageName))
        if (isSmaller()) {
            FrostLogUtils.info("Used smaller option")
        }
        FrostZipUtils.zip(unpackFilePath, unzipalignPackagePath, isSmaller())
        val keyStoreFilePath = packageLastProcessDir + File.separator + FrostConst.KEY_STORE_ASSET_NAME
        try {
            FrostZipUtils.readResourceFromRuntime(FrostConst.KEY_STORE_ASSET_PATH, keyStoreFilePath)
        } catch (e: IOException) {
            e.printStackTrace()
        }
        val unsignedPackagePath = outputDir + File.separator + (resultFileName?.let { "unsigned_" + it } ?: getUnsignPackageName(originPackageName))
        var zipalignSuccess = false
        try {
            zipalign(unzipalignPackagePath, unsignedPackagePath)
            zipalignSuccess = true
            FrostLogUtils.info("zipalign success.")
        } catch (e: Exception) {
            FrostLogUtils.error("zipalign failed!")
        }
        val willSignPackagePath = if (zipalignSuccess) unsignedPackagePath else unzipalignPackagePath
        var signResult = false
        val signedPackagePath = outputDir + File.separator + (resultFileName ?: getSignedPackageName(originPackageName))
        if (isSign()) {
            if (shellConfig.getSignatureConfig() == null || !File(shellConfig.getSignatureConfig()!!.getKeystore()).exists()) {
                FrostLogUtils.info("Use default key store")
                signResult = signPackageDebug(willSignPackagePath, keyStoreFilePath, signedPackagePath)
            } else {
                FrostLogUtils.info("Use custom key store")
                signResult = sign(
                    willSignPackagePath,
                    shellConfig.getSignatureConfig()!!.getKeystore()!!,
                    signedPackagePath,
                    shellConfig.getSignatureConfig()!!.getAlias()!!,
                    shellConfig.getSignatureConfig()!!.getStorePassword()!!,
                    shellConfig.getSignatureConfig()!!.getKeyPassword()!!
                )
            }
        } else {
            try {
                if (outputPath != null) {
                    Files.copy(Paths.get(willSignPackagePath), Paths.get(signedPackagePath), StandardCopyOption.REPLACE_EXISTING)
                }
            } catch (iOException: IOException) {
            }
        }
        val willSignPackageFile = File(willSignPackagePath)
        val signedPackageFile = File(signedPackagePath)
        val keyStoreFile = File(keyStoreFilePath)
        val idsigFile = File(signedPackagePath + ".idsig")
        FrostLogUtils.info("unsign package file: %s, exists: %s", willSignPackageFile.absolutePath, willSignPackageFile.exists())
        resultPath = if (signedPackageFile.exists()) signedPackageFile.absolutePath else willSignPackageFile.absolutePath
        if (signResult && willSignPackageFile.exists()) {
            willSignPackageFile.delete()
        }
        if (signResult) {
            FrostLogUtils.info("signed package file: " + signedPackageFile.absolutePath)
        }
        if (zipalignSuccess) {
            try {
                Files.deleteIfExists(Paths.get(unzipalignPackagePath))
            } catch (e: Exception) {
                FrostLogUtils.debug("unzipalign package path err = %s", e)
            }
        }
        if (idsigFile.exists()) {
            idsigFile.delete()
        }
        if (keyStoreFile.exists()) {
            keyStoreFile.delete()
        }
        FrostLogUtils.info("protected package output path: " + resultPath + "\n")
    }

    private fun signPackageDebug(packagePath: String, keyStorePath: String, signedPackagePath: String): Boolean {
        return sign(packagePath, keyStorePath, signedPackagePath, FrostConst.KEY_ALIAS, FrostConst.STORE_PASSWORD, FrostConst.KEY_PASSWORD)
    }

    protected abstract fun sign(
        packagePath: String,
        keyStorePath: String,
        signedPackagePath: String,
        keyAlias: String,
        storePassword: String,
        keyPassword: String
    ): Boolean

    private fun zipalign(inputPackagePath: String, outputPackagePath: String) {
        RandomAccessFile(inputPackagePath, "r").use { `in` ->
            FileOutputStream(outputPackagePath).use { out ->
                ZipAlign.alignZip(`in`, out as OutputStream)
            }
        }
    }

    private fun processRuleFile() {
        try {
            if (!FrostStringUtils.isBlank(getRulesFilePath())) {
                val file = File(getRulesFilePath())
                FrostLogUtils.debug("Exclude rules file: %s", file)
                val strings = GuavaFiles.readLines(file, StandardCharsets.UTF_8)
                val protectRules = FrostProtectRules.getInstance()
                if (strings.isNotEmpty()) {
                    protectRules.setExcludeRules(strings.toTypedArray())
                } else {
                    FrostLogUtils.debug("Exclude rules file is empty", file)
                }
            }
        } catch (e: IOException) {
            FrostLogUtils.info("Exclude rules file is unavailable: %s", e.message)
        }
    }

    private fun processProtectConfigFile() {
        val autoShellPackageName = FrostConst.SHELL_PACKAGE_NAME_AUTO
        if (FrostStringUtils.isBlank(getProtectConfigFile())) {
            FrostShellConfig.getInstance().init(autoShellPackageName)
            return
        }
        try {
            FrostLogUtils.info("Read config file: %s", getProtectConfigFile())
            val bytes = FrostIoUtils.readFile(getProtectConfigFile()!!)
            val configData = String(bytes, StandardCharsets.UTF_8)
            val shellConfigFromFile = JSON.parseObject(configData, FrostShellConfig::class.java)
            if (shellConfigFromFile != null) {
                val shellConfig = FrostShellConfig.getInstance()
                if (FrostConst.SHELL_PACKAGE_NAME_AUTO == shellConfigFromFile.getShellPackageName()) {
                    shellConfigFromFile.setShellPackageName(autoShellPackageName)
                }
                FrostLogUtils.info("Use config: %s", shellConfigFromFile)
                shellConfig.init(shellConfigFromFile)
            } else {
                FrostShellConfig.getInstance().init(autoShellPackageName)
            }
        } catch (e: Exception) {
            FrostLogUtils.error("Read config file error")
            FrostShellConfig.getInstance().init(autoShellPackageName)
        }
    }

    fun resolveDefaultShellPackageName() {
        val shellConfig = FrostShellConfig.getInstance()
        if (FrostConst.SHELL_PACKAGE_NAME_AUTO != shellConfig.getShellPackageName()) {
            return
        }
        val packageName = getPackageName()
        if (FrostStringUtils.isBlank(packageName)) {
            val fallback = FrostStringUtils.generateIdentifier(10)
            shellConfig.setShellPackageName(fallback)
            FrostLogUtils.warn("Package name is empty, fallback to random shell package name: %s", fallback)
            return
        }
        val shellPackageName = packageName + ".shell"
        shellConfig.setShellPackageName(shellPackageName)
        FrostLogUtils.info("Shell package name: %s", shellPackageName)
    }

    private fun loadKeyStore(inputStream: java.io.InputStream, password: CharArray): KeyStore? {
        val data: ByteArray = try {
            val baos = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            var n: Int
            while (inputStream.read(buf).also { n = it } != -1) {
                baos.write(buf, 0, n)
            }
            baos.toByteArray()
        } catch (e: IOException) {
            return null
        }
        for (type in arrayOf("JKS", "PKCS12", "BKS")) {
            try {
                val ks = KeyStore.getInstance(type)
                ks.load(ByteArrayInputStream(data), password)
                FrostLogUtils.info("Loaded keystore as " + type)
                return ks
            } catch (exception: Exception) {
            }
        }
        return null
    }

    private fun computeSignatureSha256(): String? {
        val shellConfig = FrostShellConfig.getInstance()
        val sigConfig = shellConfig.getSignatureConfig()
        var keystorePath: String? = null
        var storePassword = FrostConst.STORE_PASSWORD
        var alias = FrostConst.KEY_ALIAS
        if (sigConfig != null && !FrostStringUtils.isBlank(sigConfig.getKeystore()) && File(sigConfig.getKeystore()).exists()) {
            keystorePath = sigConfig.getKeystore()
            if (!FrostStringUtils.isBlank(sigConfig.getStorePassword())) {
                storePassword = sigConfig.getStorePassword()!!
            }
            if (!FrostStringUtils.isBlank(sigConfig.getAlias())) {
                alias = sigConfig.getAlias()!!
            }
            FrostLogUtils.info("Computing SHA-256 from signing keystore: " + keystorePath)
        } else {
            FrostLogUtils.info("Computing SHA-256 from default keystore")
        }
        try {
            var ks: KeyStore? = null
            val pwdChars = storePassword.toCharArray()
            if (keystorePath != null) {
                FileInputStream(keystorePath).use { fis ->
                    ks = loadKeyStore(fis, pwdChars)
                }
            }
            if (ks == null) {
                FrostLogUtils.error("Failed to load keystore")
                return null
            }
            val loadedKs = ks!!
            val cert: Certificate = loadedKs.getCertificate(alias) ?: run {
                FrostLogUtils.error("Certificate not found for alias: " + alias)
                return null
            }
            val md = MessageDigest.getInstance("SHA-256")
            val hash = md.digest(cert.encoded)
            val sb = StringBuilder()
            for (b in hash) {
                sb.append(String.format(Locale.US, "%02x", b))
            }
            return sb.toString()
        } catch (e: Exception) {
            FrostLogUtils.error("Failed to compute certificate SHA-256: " + e.message)
            return null
        }
    }

    @Throws(IOException::class)
    open fun protect() {
        val path = "shell-files"
        val shellFiles = File(FrostFileUtils.getExecutablePath() + File.separator + path)
        if (!shellFiles.exists()) {
            val msg = "Cannot find directory: shell-files!" + shellFiles
            FrostLogUtils.error(msg)
            throw java.io.FileNotFoundException(msg)
        }
        val willProtectFile = File(getFilePath())
        if (!willProtectFile.exists()) {
            val msg = String.format(Locale.US, "File not exists: %s", getFilePath())
            throw java.io.FileNotFoundException(msg)
        }
        processRuleFile()
        processProtectConfigFile()
        val shellConfig = FrostShellConfig.getInstance()
        shellConfig.setRiskCheckFlags(shellConfig.getRiskCheckFlags() or getRiskCheckFlags())
        if (isVerifySign()) {
            val sha256 = computeSignatureSha256()
            if (sha256 != null) {
                shellConfig.setAppSignSha256(sha256)
                FrostLogUtils.info("Signature verification enabled, SHA-256: " + sha256)
            } else {
                FrostLogUtils.error("Failed to compute certificate SHA-256, signature verification disabled.")
            }
        }
        FrostJunkCodeGenerator.generateJunkCodeDex(File(getJunkCodeDexPath()))
    }

    abstract class Builder {
        var filePath: String? = null
        var outputPath: String? = null
        var packageName: String? = null
        var debuggable = false
        var sign = true
        var appComponentFactory = true
        var dumpCode = false
        var excludedAbi: List<String>? = null
        var rulesFilePath: String? = null
        var keepClasses = false
        var smaller = false
        var protectConfigFile: String? = null
        var verifySign = false
        var riskCheckFlags = 0

        fun filePath(path: String): Builder {
            this.filePath = path
            return this
        }

        fun outputPath(outputPath: String?): Builder {
            this.outputPath = outputPath
            return this
        }

        fun excludedAbi(excludedAbi: List<String>?): Builder {
            this.excludedAbi = excludedAbi
            return this
        }

        fun packageName(packageName: String?): Builder {
            this.packageName = packageName
            return this
        }

        fun smaller(smaller: Boolean): Builder {
            this.smaller = smaller
            return this
        }

        fun protectConfigFile(protectConfigFile: String?): Builder {
            this.protectConfigFile = protectConfigFile
            return this
        }

        fun verifySign(verifySign: Boolean): Builder {
            this.verifySign = verifySign
            return this
        }

        fun riskCheckFlags(riskCheckFlags: Int): Builder {
            this.riskCheckFlags = riskCheckFlags
            return this
        }

        fun debuggable(debuggable: Boolean): Builder {
            this.debuggable = debuggable
            return this
        }

        fun sign(sign: Boolean): Builder {
            this.sign = sign
            return this
        }

        fun dumpCode(dumpCode: Boolean): Builder {
            this.dumpCode = dumpCode
            return this
        }

        fun appComponentFactory(appComponentFactory: Boolean): Builder {
            this.appComponentFactory = appComponentFactory
            return this
        }

        fun rulesFile(rulesFilePath: String?): Builder {
            this.rulesFilePath = rulesFilePath
            return this
        }

        fun keepClasses(keepClasses: Boolean): Builder {
            this.keepClasses = keepClasses
            return this
        }

        abstract fun build(): FrostAndroidPackage
    }
}
