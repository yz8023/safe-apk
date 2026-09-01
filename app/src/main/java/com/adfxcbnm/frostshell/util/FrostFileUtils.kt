package com.adfxcbnm.frostshell.util

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.Adler32

object FrostFileUtils {
    private const val PIPE_PREFIX = "\u2502   "
    private const val ELBOW_PREFIX = "\u2514\u2500\u2500 "
    private const val T_PREFIX = "\u251c\u2500\u2500 "

    fun getNewFileName(fileName: String, tag: String): String {
        val fileSuffix = fileName.substring(fileName.lastIndexOf(".") + 1)
        return fileName.replace(Regex("\\." + fileSuffix + "$"), "_" + tag + "." + fileSuffix)
    }

    fun getNewFileSuffix(fileName: String, newSuffix: String): String {
        val fileSuffix = fileName.substring(fileName.lastIndexOf(".") + 1)
        return fileName.replace(Regex("\\." + fileSuffix + "$"), "." + newSuffix)
    }

    fun getDir(path: String, dirName: String): File {
        val dirFile = File(path, dirName)
        if (!dirFile.exists()) {
            dirFile.mkdirs()
        }
        return dirFile
    }

    fun deleteRecurse(file: File) {
        try {
            if (file.isFile) {
                file.delete()
            } else {
                deleteDirectory(file)
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun deleteDirectory(directory: File) {
        if (!directory.exists()) return
        val files = directory.listFiles()
        if (files != null) {
            for (f in files) {
                if (f.isDirectory) deleteDirectory(f) else f.delete()
            }
        }
        directory.delete()
    }

    @Volatile
    private var executablePathOverride: String? = null

    fun setExecutablePath(path: String) {
        executablePathOverride = path
    }

    fun getExecutablePath(): String {
        return executablePathOverride ?: ""
    }

    fun getUserDir(): String = System.getProperty("user.dir") ?: ""

    fun fixCheckSumHeader(dexBytes: ByteArray) {
        val adler = Adler32()
        adler.update(dexBytes, 12, dexBytes.size - 12)
        val value = adler.value
        val va = value.toInt()
        val newcs = intToByte(va)
        val recs = ByteArray(4)
        for (i in 0 until 4) {
            recs[i] = newcs[newcs.size - 1 - i]
        }
        System.arraycopy(recs, 0, dexBytes, 8, 4)
    }

    fun intToByte(number: Int): ByteArray {
        var n = number
        val b = ByteArray(4)
        for (i in 3 downTo 0) {
            b[i] = (n % 256).toByte()
            n = n shr 8
        }
        return b
    }

    fun fixSHA1Header(dexBytes: ByteArray) {
        val md = MessageDigest.getInstance("SHA-1")
        md.update(dexBytes, 32, dexBytes.size - 32)
        val newdt = md.digest()
        System.arraycopy(newdt, 0, dexBytes, 12, 20)
    }

    fun fixFileSizeHeader(dexBytes: ByteArray) {
        val newfs = intToByte(dexBytes.size)
        val refs = ByteArray(4)
        for (i in 0 until 4) {
            refs[i] = newfs[newfs.size - 1 - i]
        }
        System.arraycopy(refs, 0, dexBytes, 32, 4)
    }

    fun getJarSignerCommand(): String {
        val os = System.getProperty("os.name").lowercase(Locale.US)
        return if (os.contains("win")) "jarsigner.exe" else "jarsigner"
    }
}
