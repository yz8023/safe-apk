package com.adfxcbnm.hardeningtool

import android.content.Context
import com.adfxcbnm.frostshell.builder.FrostApk
import com.adfxcbnm.frostshell.config.FrostConst
import com.adfxcbnm.frostshell.config.FrostShellConfig
import com.adfxcbnm.frostshell.util.FrostFileUtils
import java.io.File

object FrostShellEngine {

    fun prepare(context: Context): Boolean {
        return try {
            // 加载已保存的混淆字典词条（prefs 持久化），供类名/字段名重命名取词
            val imported = context.getSharedPreferences(
                "adfxcbnm_settings", android.content.Context.MODE_PRIVATE
            ).getStringSet("dict_imported_words", null)
            com.adfxcbnm.frostshell.config.FrostNameDictionary.setWords(
                imported?.toList() ?: emptyList()
            )
            val filesDir = context.filesDir
            val shellFilesDir = File(filesDir, "shell-files")
            // filesDir 跨会话持久化：上一次运行（随机化/伪装/OOM 中断）可能已改写
            // shell-files 内的 dex 引用与 so 文件名，导致 dex 引用与 libs 失配。
            // 每次加固前强制从 assets 基线重装，保证 dex 引用与 so 文件始终自洽。
            if (shellFilesDir.exists()) {
                shellFilesDir.deleteRecursively()
            }
            if (!copyAssetDir(context, "frostshell", shellFilesDir)) {
                return false
            }
            val ksDir = File(filesDir, "assets")
            val ksFile = File(ksDir, FrostConst.KEY_STORE_ASSET_NAME)
            if (!ksFile.exists()) {
                ksDir.mkdirs()
                context.assets.open(FrostConst.KEY_STORE_ASSET_NAME).use { input ->
                    ksFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
            FrostFileUtils.setExecutablePath(filesDir.absolutePath)
            val workDir = File(context.cacheDir, "frostshell-work")
            workDir.mkdirs()
            FrostConst.ROOT_OF_OUT_DIR = workDir.absolutePath
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun copyAssetDir(context: Context, assetDir: String, dest: File): Boolean {
        val list = context.assets.list(assetDir) ?: return false
        for (name in list) {
            val assetPath = "$assetDir/$name"
            val target = File(dest, name)
            val child = context.assets.list(assetPath)
            if (child != null && child.isNotEmpty()) {
                if (!copyAssetDir(context, assetPath, target)) return false
            } else {
                target.parentFile?.mkdirs()
                context.assets.open(assetPath).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
        return true
    }

    fun protectApk(apkPath: String, outputDir: File, options: FrostEngineOptions = FrostEngineOptions()): File {
        if (!options.signEnabled) {
            FrostShellConfig.getInstance().setSignatureConfig(null)
        } else if (!options.signKeystorePath.isNullOrBlank()) {
            FrostShellConfig.getInstance().setSignatureConfig(
                FrostShellConfig.SignatureConfig().apply {
                    setKeystore(options.signKeystorePath)
                    setAlias(options.signAlias ?: "")
                    setStorePassword(options.signStorePass ?: "")
                    setKeyPassword(options.signKeyPass ?: "")
                }
            )
        } else {
            FrostShellConfig.getInstance().setSignatureConfig(null)
        }
        val builder = FrostApk.Builder()
            .filePath(apkPath)
            .outputPath(outputDir.absolutePath)
            .sign(options.signEnabled)
            .apply {
                if (options.keepClasses) this.keepClasses(true)
                if (options.smaller) this.smaller(true)
                if (options.verifySign) this.verifySign(true)
                if (!options.excludedAbi.isNullOrEmpty()) this.excludedAbi(options.excludedAbi)
                if (options.stringEncrypt) this.stringEncrypt(true)
                this.stringEncryptMinLen(options.stringEncryptMinLen)
                this.stringEncryptKeywords(options.stringEncryptKeywords)
                if (options.dexHeaderObfuscation) this.dexHeaderObfuscation(true)
                if (options.classShuffle) this.classShuffle(true)
                if (options.debugRemoval) this.debugRemoval(true)
                if (options.gotoInsertion) this.gotoInsertion(true)
                if (options.arithmeticObfuscation) this.arithmeticObfuscation(true)
                if (options.controlFlow) this.controlFlow(true)
                if (options.callIndirection) this.callIndirection(true)
                if (options.methodOverload) this.methodOverload(true)
                if (options.fieldRename) this.fieldRename(true)
                if (options.classRename) this.classRename(true)
            }
        if (options.extractMethodRules != null) {
            com.adfxcbnm.frostshell.config.FrostProtectRules.setMemberRules(options.extractMethodRules.toTypedArray())
        } else {
            com.adfxcbnm.frostshell.config.FrostProtectRules.setMemberRules(emptyArray())
        }
        builder.build().protect()
        return findOutputApk(outputDir, apkPath)
    }

    private fun findOutputApk(outputDir: File, apkPath: String): File {
        val baseName = File(apkPath).name.replace(".apk", "")
        val candidates = listOf("${baseName}_signed.apk", "${baseName}_unsign.apk", "${baseName}_unzipalign.apk")
        for (name in candidates) {
            val f = File(outputDir, name)
            if (f.exists() && f.length() > 0) return f
        }
        val newest = outputDir.listFiles()?.filter { it.isFile && it.name.endsWith(".apk") }?.maxByOrNull { it.lastModified() }
        if (newest != null) return newest
        throw IllegalStateException("引擎未生成输出 APK")
    }
}
