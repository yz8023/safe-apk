package com.adfxcbnm.hardeningtool

import android.content.Context
import com.adfxcbnm.frostshell.builder.FrostApk
import com.adfxcbnm.frostshell.config.FrostConst
import com.adfxcbnm.frostshell.util.FrostFileUtils
import java.io.File

object FrostShellEngine {

    fun prepare(context: Context): Boolean {
        return try {
            val filesDir = context.filesDir
            val shellFilesDir = File(filesDir, "shell-files")
            if (!shellFilesDir.exists() || shellFilesDir.listFiles().isNullOrEmpty()) {
                copyAssetDir(context, "frostshell", shellFilesDir)
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

    private fun copyAssetDir(context: Context, assetDir: String, dest: File) {
        val list = context.assets.list(assetDir) ?: return
        for (name in list) {
            val assetPath = "$assetDir/$name"
            val target = File(dest, name)
            val child = context.assets.list(assetPath)
            if (child != null && child.isNotEmpty()) {
                copyAssetDir(context, assetPath, target)
            } else {
                target.parentFile?.mkdirs()
                context.assets.open(assetPath).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }

    fun protectApk(apkPath: String, outputDir: File, options: FrostEngineOptions = FrostEngineOptions()): File {
        val builder = FrostApk.Builder()
            .filePath(apkPath)
            .outputPath(outputDir.absolutePath)
            .sign(true)
            .apply {
                if (options.keepClasses) this.keepClasses(true)
                if (options.smaller) this.smaller(true)
                if (options.verifySign) this.verifySign(true)
                if (!options.excludedAbi.isNullOrEmpty()) this.excludedAbi(options.excludedAbi)
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
