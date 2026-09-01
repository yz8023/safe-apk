package com.adfxcbnm.frostshell.builder

import com.adfxcbnm.frostshell.config.FrostShellConfig
import com.adfxcbnm.frostshell.res.FrostApkManifestEditor
import com.adfxcbnm.frostshell.util.FrostFileUtils
import com.adfxcbnm.frostshell.util.FrostKeyUtils
import com.adfxcbnm.frostshell.util.FrostLogUtils
import com.adfxcbnm.frostshell.util.FrostZipUtils
import com.wind.meditor.core.FileProcesser
import com.wind.meditor.property.AttributeItem
import com.wind.meditor.property.ModificationProperty
import java.io.File
import java.io.FileInputStream
import java.io.IOException

class FrostApk private constructor(builder: Builder) : FrostAndroidPackage(builder) {
    override fun getOutAssetsDir(packageDir: String): File {
        return FrostFileUtils.getDir(packageDir, "assets")
    }

    override fun getLibDir(packageDir: String): String {
        return packageDir + File.separator + "lib"
    }

    override fun getDexDir(packageDir: String): String {
        return packageDir
    }

    override fun getManifestFilePath(packageOutDir: String): String {
        return packageOutDir + File.separator + "AndroidManifest.xml"
    }

    override fun sign(
        packagePath: String,
        keyStorePath: String,
        signedPackagePath: String,
        keyAlias: String,
        storePassword: String,
        keyPassword: String
    ): Boolean {
        return try {
            val keyStore = loadKeyStore(File(keyStorePath), storePassword.toCharArray())
            val privateKey = keyStore.getKey(keyAlias, keyPassword.toCharArray()) as java.security.PrivateKey
            val certChain = keyStore.getCertificateChain(keyAlias)?.map {
                it as java.security.cert.X509Certificate
            } ?: emptyList()
            if (certChain.isEmpty()) {
                FrostLogUtils.error("Sign failed: empty certificate chain for alias $keyAlias")
                return false
            }
            val signerConfig = com.android.apksig.ApkSigner.SignerConfig.Builder(
                keyAlias, privateKey, certChain
            ).build()
            val signer = com.android.apksig.ApkSigner.Builder(listOf(signerConfig))
                .setInputApk(File(packagePath))
                .setOutputApk(File(signedPackagePath))
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .build()
            signer.sign()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            FrostLogUtils.error("Sign failed: ${e.message}")
            false
        }
    }

    private fun loadKeyStore(keystoreFile: File, password: CharArray): java.security.KeyStore {
        var lastError: Exception? = null
        FileInputStream(keystoreFile).use { fis ->
            for (type in arrayOf("JKS", "PKCS12", "BKS")) {
                try {
                    val ks = java.security.KeyStore.getInstance(type)
                    ks.load(fis, password)
                    return ks
                } catch (e: Exception) {
                    lastError = e
                }
            }
        }
        throw IOException("Unable to load keystore ${keystoreFile.absolutePath}", lastError)
    }

    override fun writeProxyAppName(manifestDir: String) {
        val inManifestPath = manifestDir + File.separator + "AndroidManifest.xml"
        val outManifestPath = manifestDir + File.separator + "AndroidManifest_new.xml"
        FrostApkManifestEditor.writeApplicationName(inManifestPath, outManifestPath, getProxyApplicationName())
        val inManifestFile = File(inManifestPath)
        val outManifestFile = File(outManifestPath)
        inManifestFile.delete()
        outManifestFile.renameTo(inManifestFile)
    }

    override fun writeProxyComponentFactoryName(manifestDir: String) {
        val inManifestPath = manifestDir + File.separator + "AndroidManifest.xml"
        val outManifestPath = manifestDir + File.separator + "AndroidManifest_new.xml"
        FrostApkManifestEditor.writeAppComponentFactory(inManifestPath, outManifestPath, getProxyComponentFactory())
        val inManifestFile = File(inManifestPath)
        val outManifestFile = File(outManifestPath)
        inManifestFile.delete()
        outManifestFile.renameTo(inManifestFile)
    }

    override fun setExtractNativeLibs(manifestDir: String) {
        val inManifestPath = manifestDir + File.separator + "AndroidManifest.xml"
        val outManifestPath = manifestDir + File.separator + "AndroidManifest_new.xml"
        val property = ModificationProperty()
        property.addApplicationAttribute(AttributeItem("extractNativeLibs", "true"))
        FileProcesser.processManifestFile(inManifestPath, outManifestPath, property)
        val inManifestFile = File(inManifestPath)
        val outManifestFile = File(outManifestPath)
        inManifestFile.delete()
        outManifestFile.renameTo(inManifestFile)
    }

    override fun setDebuggable(manifestDir: String, debuggable: Boolean) {
        val inManifestPath = manifestDir + File.separator + "AndroidManifest.xml"
        val outManifestPath = manifestDir + File.separator + "AndroidManifest_new.xml"
        FrostApkManifestEditor.writeDebuggable(inManifestPath, outManifestPath, if (debuggable) "true" else "false")
        val inManifestFile = File(inManifestPath)
        val outManifestFile = File(outManifestPath)
        inManifestFile.delete()
        outManifestFile.renameTo(inManifestFile)
    }

    override fun saveApplicationName(packageOutDir: String) {
        val androidManifestFile = getManifestFilePath(packageOutDir)
        val shellConfig = FrostShellConfig.getInstance()
        var appName = FrostApkManifestEditor.getApplicationName(androidManifestFile)
        appName = appName ?: ""
        appName = if (appName.startsWith(".")) appName.substring(1) else appName
        shellConfig.setApplicationName(appName)
    }

    override fun saveAppComponentFactory(packageOutDir: String) {
        val androidManifestFile = getManifestFilePath(packageOutDir)
        val shellConfig = FrostShellConfig.getInstance()
        var acfName = FrostApkManifestEditor.getAppComponentFactory(androidManifestFile)
        acfName = acfName ?: ""
        shellConfig.setAppComponentFactoryName(acfName)
    }

    private fun process(apk: FrostApk) {
        val encKey = FrostKeyUtils.generateKey()
        val apkFile = File(apk.getFilePath())
        val apkMainProcessPath = apk.getWorkspaceDir().absolutePath
        FrostLogUtils.info("Workspace path: " + apkMainProcessPath)
        FrostZipUtils.unZip(apk.getFilePath()!!, apkMainProcessPath)
        val packageName = FrostApkManifestEditor.getPackageName(apkMainProcessPath + File.separator + "AndroidManifest.xml")
        apk.setPackageName(packageName)
        apk.resolveDefaultShellPackageName()
        apk.saveApplicationName(apkMainProcessPath)
        apk.writeProxyAppName(apkMainProcessPath)
        if (apk.isAppComponentFactory()) {
            apk.saveAppComponentFactory(apkMainProcessPath)
            apk.writeProxyComponentFactoryName(apkMainProcessPath)
        }
        if (apk.isDebuggable()) {
            FrostLogUtils.info("Make apk debuggable.")
            apk.setDebuggable(apkMainProcessPath, true)
        }
        apk.setExtractNativeLibs(apkMainProcessPath)
        val assetsPath = apk.getOutAssetsDir(apkMainProcessPath).absolutePath
        apk.extractDexCode(apkMainProcessPath, assetsPath, encKey)
        apk.addJunkCodeDex(apkMainProcessPath)
        apk.compressDexFiles(apkMainProcessPath)
        apk.deleteAllDexFiles(apkMainProcessPath)
        apk.combineDexZipWithShellDex(apkMainProcessPath, encKey)
        apk.addKeepDexes(apkMainProcessPath)
        FrostFileUtils.deleteRecurse(apk.getKeepDexTempDir(apkMainProcessPath))
        apk.copyNativeLibs(apkMainProcessPath)
        apk.encryptSoFiles(apkMainProcessPath, encKey)
        apk.writeConfig(apkMainProcessPath, encKey)
        apk.buildPackage(apkFile.absolutePath, apkMainProcessPath, FrostFileUtils.getUserDir())
        val apkMainProcessFile = File(apkMainProcessPath)
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
        override fun build(): FrostApk = FrostApk(this)
    }
}
