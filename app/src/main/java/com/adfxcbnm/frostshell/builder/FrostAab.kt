package com.adfxcbnm.frostshell.builder

import com.adfxcbnm.frostshell.config.FrostShellConfig
import com.adfxcbnm.frostshell.res.FrostAabManifestEditor
import com.adfxcbnm.frostshell.util.FrostFileUtils
import com.adfxcbnm.frostshell.util.FrostKeyUtils
import com.adfxcbnm.frostshell.util.FrostLogUtils
import com.adfxcbnm.frostshell.util.FrostZipUtils
import java.io.File
import java.io.IOException
import java.io.InputStream

class FrostAab private constructor(builder: Builder) : FrostAndroidPackage(builder) {
    override fun writeProxyAppName(manifestDir: String) {
        val inManifestPath = manifestDir + File.separator + "AndroidManifest.xml"
        val outManifestPath = manifestDir + File.separator + "AndroidManifest_new.xml"
        FrostAabManifestEditor.writeApplicationName(inManifestPath, outManifestPath, getProxyApplicationName())
        val inManifestFile = File(inManifestPath)
        val outManifestFile = File(outManifestPath)
        inManifestFile.delete()
        outManifestFile.renameTo(inManifestFile)
    }

    override fun writeProxyComponentFactoryName(manifestDir: String) {
        val inManifestPath = manifestDir + File.separator + "AndroidManifest.xml"
        val outManifestPath = manifestDir + File.separator + "AndroidManifest_new.xml"
        FrostAabManifestEditor.writeAppComponentFactory(inManifestPath, outManifestPath, getProxyComponentFactory())
        val inManifestFile = File(inManifestPath)
        val outManifestFile = File(outManifestPath)
        inManifestFile.delete()
        outManifestFile.renameTo(inManifestFile)
    }

    override fun setExtractNativeLibs(manifestDir: String) {
        val inManifestPath = manifestDir + File.separator + "AndroidManifest.xml"
        val outManifestPath = manifestDir + File.separator + "AndroidManifest_new.xml"
        FrostAabManifestEditor.writeApplicationExtractNativeLibs(inManifestPath, outManifestPath, "true")
        val inManifestFile = File(inManifestPath)
        val outManifestFile = File(outManifestPath)
        inManifestFile.delete()
        outManifestFile.renameTo(inManifestFile)
    }

    override fun setDebuggable(manifestDir: String, debuggable: Boolean) {
        val inManifestPath = manifestDir + File.separator + "AndroidManifest.xml"
        val outManifestPath = manifestDir + File.separator + "AndroidManifest_new.xml"
        FrostAabManifestEditor.writeDebuggable(inManifestPath, outManifestPath, debuggable.toString())
        val inManifestFile = File(inManifestPath)
        val outManifestFile = File(outManifestPath)
        inManifestFile.delete()
        outManifestFile.renameTo(inManifestFile)
    }

    override fun getOutAssetsDir(packageDir: String): File {
        return FrostFileUtils.getDir(getBaseDir(packageDir), "assets")
    }

    protected fun getManifestFileDir(packageOutDir: String): String {
        return getBaseDir(packageOutDir) + File.separator + "manifest"
    }

    override fun getManifestFilePath(packageOutDir: String): String {
        return getManifestFileDir(packageOutDir) + File.separator + "AndroidManifest.xml"
    }

    override fun saveApplicationName(packageOutDir: String) {
        val androidManifestFile = getManifestFilePath(packageOutDir)
        var appName = FrostAabManifestEditor.getApplicationName(androidManifestFile)
        appName = appName ?: ""
        appName = if (appName.startsWith(".")) appName.substring(1) else appName
        val shellConfig = FrostShellConfig.getInstance()
        shellConfig.setApplicationName(appName)
    }

    override fun saveAppComponentFactory(packageOutDir: String) {
        val androidManifestFile = getManifestFilePath(packageOutDir)
        var acfName = FrostAabManifestEditor.getAppComponentFactory(androidManifestFile)
        acfName = acfName ?: ""
        val shellConfig = FrostShellConfig.getInstance()
        shellConfig.setAppComponentFactoryName(acfName)
    }

    fun getBaseDir(packageDir: String): String {
        return packageDir + File.separator + "base"
    }

    override fun getLibDir(packageDir: String): String {
        return getBaseDir(packageDir) + File.separator + "lib"
    }

    override fun getDexDir(packageDir: String): String {
        return getBaseDir(packageDir) + File.separator + "dex"
    }

    override fun sign(
        packagePath: String,
        keyStorePath: String,
        signedPackagePath: String,
        keyAlias: String,
        storePassword: String,
        keyPassword: String
    ): Boolean {
        val command = ArrayList<String>()
        command.add(FrostFileUtils.getJarSignerCommand())
        command.add("-keystore")
        command.add(keyStorePath)
        command.add("-storepass")
        command.add(storePassword)
        command.add("-keypass")
        command.add(keyPassword)
        command.add("-signedjar")
        command.add(signedPackagePath)
        command.add(packagePath)
        command.add(keyAlias)
        return try {
            val processBuilder = ProcessBuilder(command)
            processBuilder.redirectErrorStream(true)
            val process = processBuilder.start()
            val inputStream: InputStream = process.inputStream
            try {
                inputStream.readAllBytes()
            } finally {
                inputStream.close()
            }
            val exitCode = process.waitFor()
            exitCode == 0
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun process(aab: FrostAab) {
        val aabFile = File(aab.getFilePath())
        val encKey = FrostKeyUtils.generateKey()
        val aabMainProcessPath = aab.getWorkspaceDir().absolutePath
        FrostLogUtils.info("Workspace path: " + aabMainProcessPath)
        FrostZipUtils.unZip(aab.getFilePath()!!, aabMainProcessPath)
        val manifestFilePath = aab.getManifestFilePath(aabMainProcessPath)
        val manifestFileDir = aab.getManifestFileDir(aabMainProcessPath)
        val packageName = FrostAabManifestEditor.getPackageName(manifestFilePath)
        aab.setPackageName(packageName)
        aab.resolveDefaultShellPackageName()
        aab.saveApplicationName(aabMainProcessPath)
        aab.writeProxyAppName(manifestFileDir)
        if (aab.isAppComponentFactory()) {
            aab.saveAppComponentFactory(aabMainProcessPath)
            aab.writeProxyComponentFactoryName(manifestFileDir)
        }
        if (aab.isDebuggable()) {
            FrostLogUtils.info("Make aab debuggable.")
            aab.setDebuggable(manifestFileDir, true)
        }
        aab.setExtractNativeLibs(manifestFileDir)
        val assetsPath = aab.getOutAssetsDir(aabMainProcessPath).absolutePath
        aab.extractDexCode(aabMainProcessPath, assetsPath, encKey)
        aab.addJunkCodeDex(aabMainProcessPath)
        aab.compressDexFiles(aabMainProcessPath)
        aab.deleteAllDexFiles(aabMainProcessPath)
        aab.combineDexZipWithShellDex(aabMainProcessPath, encKey)
        aab.addKeepDexes(aabMainProcessPath)
        val keepDexTempDir = aab.getKeepDexTempDir(aabMainProcessPath)
        FrostFileUtils.deleteRecurse(keepDexTempDir)
        aab.copyNativeLibs(aabMainProcessPath)
        aab.encryptSoFiles(aabMainProcessPath, encKey)
        aab.writeConfig(aabMainProcessPath, encKey)
        aab.buildPackage(aabFile.absolutePath, aabMainProcessPath, FrostFileUtils.getUserDir())
        val apkMainProcessFile = File(aabMainProcessPath)
        if (apkMainProcessFile.exists()) {
            FrostFileUtils.deleteRecurse(apkMainProcessFile)
        }
        FrostLogUtils.info("All done.")
    }

    @Throws(IOException::class)
    override fun protect() {
        super.protect()
        process(this)
    }

    class Builder : FrostAndroidPackage.Builder() {
        override fun build(): FrostAab = FrostAab(this)
    }
}
