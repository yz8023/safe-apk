package com.adfxcbnm.frostshell.builder

import com.adfxcbnm.frostshell.config.FrostConst
import com.adfxcbnm.frostshell.util.FrostFileUtils
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.jar.Manifest

object FrostIronShell {
    private const val MANIFEST_BUILD_KEY_ATTR = "IronShell-Build-Key"

    fun getVersion(): String {
        val pkg = FrostIronShell::class.java.getPackage()
        var version = pkg?.implementationVersion
        if (version == null) {
            version = "unknown"
        }
        return version
    }

    fun getBuildKey(): String? {
        val executablePath = FrostFileUtils.getExecutablePath()
        if (executablePath.isNotEmpty()) {
            val keyFile = File(executablePath, "shell-files" + File.separator + FrostConst.KEY_BUILD_KEY_FILE_NAME)
            if (keyFile.isFile) {
                try {
                    val value = String(keyFile.readBytes(), StandardCharsets.UTF_8).trim()
                    if (value.isNotEmpty()) {
                        return value
                    }
                } catch (e: IOException) {
                }
            }
        }
        try {
            val classLoader = FrostIronShell::class.java.classLoader ?: return null
            val resources = classLoader.getResources("META-INF/MANIFEST.MF")
            while (resources.hasMoreElements()) {
                val `is`: InputStream = resources.nextElement().openStream()
                try {
                    val value = Manifest(`is`).mainAttributes.getValue(MANIFEST_BUILD_KEY_ATTR)
                    if (value == null || value.isEmpty()) continue
                    return value
                } finally {
                    `is`.close()
                }
            }
            return null
        } catch (e: IOException) {
        }
        return null
    }
}
