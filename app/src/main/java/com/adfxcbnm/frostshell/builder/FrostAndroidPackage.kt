package com.adfxcbnm.frostshell.builder

import com.adfxcbnm.frostshell.config.FrostConst
import com.adfxcbnm.frostshell.config.FrostProtectRules
import com.adfxcbnm.frostshell.config.FrostShellConfig
import com.adfxcbnm.frostshell.dex.FrostClassRenamer
import com.adfxcbnm.frostshell.dex.FrostDexObfuscator
import com.adfxcbnm.frostshell.dex.FrostJunkCodeGenerator
import com.adfxcbnm.frostshell.dex.FrostStringEncryptor
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
    companion object {
        @Volatile
        private var bcProviderLoaded = false
    }
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
    private var stringEncrypt = false
    private var stringEncryptMinLen = 6
    private var stringEncryptKeywords: Set<String>? = null
    private var dexHeaderObfuscation = false
    private var classShuffle = false
    private var debugRemoval = false
    private var gotoInsertion = false
    private var arithmeticObfuscation = false
    private var controlFlow = false
    private var callIndirection = false
    private var methodOverload = false
    private var fieldRename = false
    private var classRename = false

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
        this.stringEncrypt = builder.stringEncrypt
        this.stringEncryptMinLen = builder.stringEncryptMinLen
        this.stringEncryptKeywords = builder.stringEncryptKeywords
        this.dexHeaderObfuscation = builder.dexHeaderObfuscation
        this.classShuffle = builder.classShuffle
        this.debugRemoval = builder.debugRemoval
        this.gotoInsertion = builder.gotoInsertion
        this.arithmeticObfuscation = builder.arithmeticObfuscation
        this.controlFlow = builder.controlFlow
        this.callIndirection = builder.callIndirection
        this.methodOverload = builder.methodOverload
        this.fieldRename = builder.fieldRename
        this.classRename = builder.classRename
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

    fun isStringEncrypt(): Boolean = stringEncrypt

    fun setStringEncrypt(stringEncrypt: Boolean) {
        this.stringEncrypt = stringEncrypt
    }

    fun getStringEncryptMinLen(): Int = stringEncryptMinLen

    fun setStringEncryptMinLen(stringEncryptMinLen: Int) {
        this.stringEncryptMinLen = stringEncryptMinLen
    }

    fun getStringEncryptKeywords(): Set<String>? = stringEncryptKeywords

    fun setStringEncryptKeywords(stringEncryptKeywords: Set<String>?) {
        this.stringEncryptKeywords = stringEncryptKeywords
    }

    fun isDexHeaderObfuscation(): Boolean = dexHeaderObfuscation

    fun setDexHeaderObfuscation(dexHeaderObfuscation: Boolean) {
        this.dexHeaderObfuscation = dexHeaderObfuscation
    }

    fun isClassShuffle(): Boolean = classShuffle

    fun setClassShuffle(classShuffle: Boolean) {
        this.classShuffle = classShuffle
    }

    fun isDebugRemoval(): Boolean = debugRemoval

    fun setDebugRemoval(debugRemoval: Boolean) {
        this.debugRemoval = debugRemoval
    }

    fun isGotoInsertion(): Boolean = gotoInsertion

    fun setGotoInsertion(gotoInsertion: Boolean) {
        this.gotoInsertion = gotoInsertion
    }

    fun isArithmeticObfuscation(): Boolean = arithmeticObfuscation

    fun setArithmeticObfuscation(arithmeticObfuscation: Boolean) {
        this.arithmeticObfuscation = arithmeticObfuscation
    }

    fun isControlFlow(): Boolean = controlFlow

    fun setControlFlow(controlFlow: Boolean) {
        this.controlFlow = controlFlow
    }

    fun isCallIndirection(): Boolean = callIndirection

    fun setCallIndirection(callIndirection: Boolean) {
        this.callIndirection = callIndirection
    }

    fun isMethodOverload(): Boolean = methodOverload

    fun setMethodOverload(methodOverload: Boolean) {
        this.methodOverload = methodOverload
    }

    fun isFieldRename(): Boolean = fieldRename

    fun setFieldRename(fieldRename: Boolean) {
        this.fieldRename = fieldRename
    }

    fun isClassRename(): Boolean = classRename

    fun setClassRename(classRename: Boolean) {
        this.classRename = classRename
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
        val totalClassesCount = AtomicInteger(0)
        val keepClassesCount = AtomicInteger(0)
        // 阶段1：并行预处理（反射 clinit 注入/类拆分/字符串加密），此时方法体仍完整
        val prepLatch = CountDownLatch(dexFiles.size)
        for (dexFile in dexFiles) {
            FrostThreadPool.execute {
                if (FrostDexUtils.getDexNumber(dexFile.name) < 0) {
                    prepLatch.countDown()
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
                if (isStringEncrypt()) {
                    // L1 字符串加密 pass：必须在 extractAllMethods（方法体清零）之前执行
                    try {
                        val keywords = getStringEncryptKeywords().orEmpty()
                        val ret = FrostStringEncryptor.process(dexFile, keywords, getStringEncryptMinLen())
                        if (ret.encryptedCount > 0) {
                            FrostLogUtils.info(
                                "String encrypted: %d strings, %d helpers in %s",
                                ret.encryptedCount, ret.helperCount, dexFile.name
                            )
                        }
                    } catch (e: Exception) {
                        FrostLogUtils.warn("WARNING: string encrypt %s fail: %s", dexFile.name, e.message)
                    }
                }
                prepLatch.countDown()
            }
        }
        FrostThreadPool.shutdown()
        try {
            prepLatch.await()
        } catch (exception: Exception) {
        }
        if (isKeepClasses()) {
            FrostLogUtils.info("Keep classes: %d, total classes: %d", keepClassesCount.get(), totalClassesCount.get())
        }
        // 阶段2：文件级 DEX 混淆 pass（类打乱/Debug 移除/Goto 插入/算术/控制流/调用间接化/
        // 方法重载/字段重命名/类重命名）。必须在方法体抽取之前对方法体完整的 dex 串行执行：
        // extractAllMethods 输出的 stub dex 携带全局引用池索引，直接操作会抛出
        // Invalid field index / truncated 错误（此类错误导致 pass 在抽取后大面积降级）。
        // 类重命名需要跨 dex 全局映射，须在进入本循环前置构建。
        // task②: manifest 组件类必须保护（重命名不同步清单会导致启动崩溃）。
        // 收集 application/activity/service/receiver/provider 的 android:name 引用类。
        val manifestFile = try {
            File(getManifestFilePath(packageDir))
        } catch (e: Exception) {
            null
        }
        val componentProtected = LinkedHashSet<String>()
        if (manifestFile != null && manifestFile.exists()) {
            try {
                componentProtected.addAll(
                    com.adfxcbnm.frostshell.res.FrostApkManifestEditor
                        .collectComponentClasses(manifestFile.absolutePath)
                )
            } catch (e: Exception) {
                FrostLogUtils.warn("class rename: manifest component scan fail: %s", e.message)
            }
        }
        // 保护自我保护类（manifest 注入的 provider 精确类名），防止壳自检失效。
        // 仅精确保留 SecurityCheckProvider：其余 com/adfxcbnm/protect 内部类仍需参与混淆。
        componentProtected.add("Lcom/adfxcbnm/protect/SecurityCheckProvider;")
        val classRenameMap: Map<String, String> = if (isClassRename()) {
            try {
                FrostClassRenamer.buildClassRenameMap(dexFiles, componentProtected)
            } catch (e: Exception) {
                FrostLogUtils.warn("WARNING: class rename map build fail: %s", e.message)
                emptyMap()
            }
        } else {
            emptyMap()
        }
        for (dexFile in dexFiles) {
            if (!dexFile.exists()) continue
            try {
                if (isDebugRemoval()) {
                    FrostDexObfuscator.stripDebugInfo(dexFile)
                }
                if (isClassShuffle()) {
                    FrostDexObfuscator.shuffleDexClasses(dexFile)
                }
                if (isGotoInsertion()) {
                    FrostDexObfuscator.applyGotoInsertion(dexFile)
                }
                if (isArithmeticObfuscation()) {
                    FrostDexObfuscator.applyArithmeticObfuscation(dexFile)
                }
                if (isControlFlow()) {
                    FrostDexObfuscator.applyControlFlow(dexFile)
                }
                if (isCallIndirection()) {
                    FrostDexObfuscator.applyCallIndirection(dexFile)
                }
                if (isMethodOverload()) {
                    FrostDexObfuscator.applyMethodOverload(dexFile)
                }
            } catch (e: Exception) {
                FrostLogUtils.warn("WARNING: file-level dex obfuscation %s fail: %s", dexFile.name, e.message)
            }
        }
        // 字段重命名跨 dex 原子执行：与类重命名同因（跨 dex 引用错位 → NoSuchFieldError，
        // 如 kotlinx.coroutines DispatchedContinuation.resumeMode 定义与引用分处不同 dex）。
        // 任一 dex 失败则整体回滚并放弃本次字段重命名。
        if (isFieldRename()) {
            try {
                FrostDexObfuscator.applyFieldRenameAtomic(dexFiles, componentProtected)
            } catch (e: Exception) {
                FrostLogUtils.warn("WARNING: field rename pass fail: %s", e.message)
            }
        }
        // 阶段2.5：类重命名（跨 dex 原子执行）。必须与其它文件级 pass 分离：
        // 单一 dex 写回校验失败会破坏跨 dex 引用一致性（如 MainActivity 所在 dex 回滚、
        // 被引用类所在 dex 已改名 → NoClassDefFoundError 崩溃）。因此任一 dex 失败时
        // 必须回滚全部 dex 并整体放弃本次类重命名，保证要么全改名、要么全不改名。
        if (classRenameMap.isNotEmpty() && isClassRename()) {
            val renameTargets = dexFiles.filter { it.exists() }
            if (renameTargets.isNotEmpty()) {
                val atomicBackups = HashMap<File, File>()
                var renameFailed = false
                try {
                    for (df in renameTargets) {
                        val bk = File(df.absolutePath + ".clsrm_atomic_backup")
                        bk.writeBytes(df.readBytes())
                        atomicBackups[df] = bk
                    }
                    for (df in renameTargets) {
                        FrostClassRenamer.applyClassRenameDex(df, classRenameMap)
                    }
                } catch (e: Exception) {
                    renameFailed = true
                    FrostLogUtils.warn("class rename atomic rollback triggered: %s", e.message)
                }
                if (renameFailed) {
                    for ((df, bk) in atomicBackups) {
                        try {
                            if (bk.exists()) df.writeBytes(bk.readBytes())
                        } catch (r: Throwable) {
                            FrostLogUtils.warn("class rename rollback restore %s fail: %s", df.name, r.message)
                        }
                    }
                    FrostLogUtils.warn("class rename aborted: all dex rolled back, app will run with original class names")
                }
                for (bk in atomicBackups.values) {
                    bk.delete()
                }
            }
        }
        // 阶段3：并行方法体抽取到指令池 + hash 重写
        val extractLatch = CountDownLatch(dexFiles.size)
        for (dexFile in dexFiles) {
            FrostThreadPool.execute {
                val dexNo = FrostDexUtils.getDexNumber(dexFile.name)
                if (dexNo < 0) {
                    extractLatch.countDown()
                    return@execute
                }
                try {
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
                } catch (e: Exception) {
                    FrostLogUtils.warn("WARNING: extract %s fail: %s", dexFile.name, e.message)
                } finally {
                    extractLatch.countDown()
                }
            }
        }
        FrostThreadPool.shutdown()
        try {
            extractLatch.await()
        } catch (exception: Exception) {
        }
        // 阶段4：DEX 头部混淆。仅重建头部字节（padding + SHA-1/Adler32 重算），
        // 对抽取后的 stub dex 同样安全，故放在抽取之后执行以保留在最终产物中。
        for (dexFile in dexFiles) {
            if (!dexFile.exists()) continue
            try {
                if (isDexHeaderObfuscation()) {
                    FrostDexObfuscator.obfuscateDexHeader(dexFile)
                }
            } catch (e: Exception) {
                FrostLogUtils.warn("WARNING: file-level dex obfuscation %s fail: %s", dexFile.name, e.message)
            }
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

    private fun loadKeyStore(inputStream: java.io.InputStream, password: CharArray, hintPath: String? = null): KeyStore? {
        if (!bcProviderLoaded) {
            try {
                if (java.security.Security.getProvider("BC") == null) {
                    java.security.Security.addProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())
                }
            } catch (t: Throwable) {
                // BKS 分支不可用，仍可尝试 PKCS12/JKS
            }
            bcProviderLoaded = true
        }
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
        // 按扩展名推断格式，优先尝试最可能的格式，避免全量试错
        val name = hintPath?.lowercase() ?: ""
        val ordered = when {
            name.endsWith(".jks") || name.endsWith(".keystore") || name.endsWith(".ks") -> arrayOf("JKS", "PKCS12", "BKS")
            name.endsWith(".bks") -> arrayOf("BKS", "JKS", "PKCS12")
            else -> arrayOf("PKCS12", "JKS", "BKS")
        }
        for (type in ordered) {
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
                    ks = loadKeyStore(fis, pwdChars, keystorePath)
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
        var stringEncrypt = false
        var stringEncryptMinLen = 6
        var stringEncryptKeywords: Set<String>? = null
        var dexHeaderObfuscation = false
        var classShuffle = false
        var debugRemoval = false
        var gotoInsertion = false
        var arithmeticObfuscation = false
        var controlFlow = false
        var callIndirection = false
        var methodOverload = false
        var fieldRename = false
        var classRename = false

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

        fun stringEncrypt(stringEncrypt: Boolean): Builder {
            this.stringEncrypt = stringEncrypt
            return this
        }

        fun stringEncryptMinLen(stringEncryptMinLen: Int): Builder {
            this.stringEncryptMinLen = stringEncryptMinLen
            return this
        }

        fun stringEncryptKeywords(stringEncryptKeywords: Set<String>?): Builder {
            this.stringEncryptKeywords = stringEncryptKeywords
            return this
        }

        fun dexHeaderObfuscation(dexHeaderObfuscation: Boolean): Builder {
            this.dexHeaderObfuscation = dexHeaderObfuscation
            return this
        }

        fun classShuffle(classShuffle: Boolean): Builder {
            this.classShuffle = classShuffle
            return this
        }

        fun debugRemoval(debugRemoval: Boolean): Builder {
            this.debugRemoval = debugRemoval
            return this
        }

        fun gotoInsertion(gotoInsertion: Boolean): Builder {
            this.gotoInsertion = gotoInsertion
            return this
        }

        fun arithmeticObfuscation(arithmeticObfuscation: Boolean): Builder {
            this.arithmeticObfuscation = arithmeticObfuscation
            return this
        }

        fun controlFlow(controlFlow: Boolean): Builder {
            this.controlFlow = controlFlow
            return this
        }

        fun callIndirection(callIndirection: Boolean): Builder {
            this.callIndirection = callIndirection
            return this
        }

        fun methodOverload(methodOverload: Boolean): Builder {
            this.methodOverload = methodOverload
            return this
        }

        fun fieldRename(fieldRename: Boolean): Builder {
            this.fieldRename = fieldRename
            return this
        }

        fun classRename(classRename: Boolean): Builder {
            this.classRename = classRename
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
