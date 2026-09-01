package com.adfxcbnm.frostshell.util

import com.adfxcbnm.frostshell.config.FrostShellConfig
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.model.FileHeader
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.CompressionMethod
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.ArrayList
import java.util.Locale
import java.util.regex.Matcher
import java.util.zip.CRC32
import java.util.zip.CheckedOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object FrostZipUtils {
    private const val META_INF_NAME = "META-INF"
    private val doNotCompress = ArrayList(defaultStoreList())
    private val resConflictFiles = HashMap<String, String>()
    private val compressedLevelMap = HashMap<String, CompressionMethod>()
    private const val RENAME_SUFFIX = ".renamed"

    private fun defaultStoreList(): List<String> = listOf("assets/.meta")

    private fun biggerFileList(): List<String> = listOf("assets/" + FrostShellConfig.getInstance().getInsnsStoreName())

    private fun isSignatureMetaInfFile(entryName: String): Boolean {
        val name = entryName.replace('\\', '/')
        val slash = name.lastIndexOf('/')
        val fileName = if (slash >= 0) name.substring(slash + 1) else name
        val upper = fileName.uppercase(Locale.US)
        return upper == "MANIFEST.MF" || upper.endsWith(".SF") || upper.endsWith(".RSA") ||
            upper.endsWith(".DSA") || upper.endsWith(".EC")
    }

    @Throws(IOException::class)
    fun readResourceFromRuntime(resourcePath: String, distPath: String) {
        var inputStream = FrostZipUtils::class.java.classLoader?.getResourceAsStream(resourcePath)
        if (inputStream == null && FrostFileUtils.getExecutablePath().isNotEmpty()) {
            val fallback = File(FrostFileUtils.getExecutablePath(), resourcePath)
            if (fallback.isFile) {
                inputStream = FileInputStream(fallback)
            }
        }
        if (inputStream == null) {
            throw IOException("cannot get resource:$resourcePath")
        }
        val distFile = File(distPath)
        if (!distFile.parentFile.exists()) {
            distFile.parentFile.mkdirs()
        }
        try {
            BufferedInputStream(inputStream).use { `in` ->
                BufferedOutputStream(FileOutputStream(distFile)).use { out ->
                    val b = ByteArray(1024)
                    var len = -1
                    while (`in`.read(b).also { len = it } != -1) {
                        out.write(b, 0, len)
                    }
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun setCompressionMethod(fileName: String, zipParameters: ZipParameters) {
        if (compressedLevelMap.containsKey(fileName)) {
            zipParameters.compressionMethod = compressedLevelMap[fileName]
        }
        if (defaultStoreList().contains(fileName) || biggerFileList().contains(fileName)) {
            zipParameters.compressionMethod = CompressionMethod.STORE
        }
    }

    @Throws(IOException::class)
    private fun addEntry(zipFile: ZipFile, rootDir: String, parent: File) {
        val zipParameters = ZipParameters()
        if (parent.isDirectory) {
            val list = parent.listFiles()
                ?: return
            if (list.isNotEmpty()) {
                for (f in list) {
                    if (f.isDirectory) {
                        addEntry(zipFile, rootDir, f)
                        continue
                    }
                    var entryName = f.absolutePath.replace(rootDir, "").substring(1)
                    entryName = entryName.replace("\\", "/")
                    val entryFile = File(entryName)
                    zipParameters.rootFolderNameInZip = entryFile.parent
                    setCompressionMethod(entryName, zipParameters)
                    zipFile.addFile(f.absoluteFile, zipParameters)
                }
            } else {
                var entryName = parent.absolutePath.replace(rootDir, "").substring(1)
                entryName = entryName.replace("\\", "/")
                val entryFile = File(entryName)
                zipParameters.rootFolderNameInZip = entryFile.parent
                setCompressionMethod(entryName, zipParameters)
                zipFile.addFolder(parent, zipParameters)
            }
        } else {
            var entryName = parent.absolutePath.replace(rootDir, "").substring(1)
            entryName = entryName.replace("\\", "/")
            val entryFile = File(entryName)
            zipParameters.rootFolderNameInZip = entryFile.parent
            setCompressionMethod(entryName, zipParameters)
            zipFile.addFile(parent, zipParameters)
        }
    }

    fun compressToApk(srcDir: String, destFile: String) {
        var zipFile: ZipFile? = null
        try {
            zipFile = ZipFile(destFile)
            val dir = File(srcDir)
            addEntry(zipFile, dir.absolutePath, dir)
            val fileHeaders = zipFile.fileHeaders
            for (fileHeader in fileHeaders) {
                val fileName = fileHeader.fileName
                if (!fileName.contains(RENAME_SUFFIX)) continue
                val newFileName = fileName.replace(Regex("\\.renamed\\d+$"), "")
                zipFile.renameFile(fileHeader, newFileName)
                FrostLogUtils.noisy("compress file name restore: %s -> %s", fileName, newFileName)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            FrostIoUtils.close(zipFile)
        }
    }

    private fun writeZipEntry(zipInputStream: ZipInputStream, targetFilePath: String) {
        var fos: FileOutputStream? = null
        try {
            val targetFile = File(targetFilePath)
            if (!targetFile.parentFile.exists()) {
                targetFile.parentFile.mkdirs()
            }
            fos = FileOutputStream(targetFile)
            val buf = ByteArray(1024)
            var len = 0
            while (zipInputStream.read(buf).also { len = it } != -1) {
                fos.write(buf, 0, len)
            }
        } catch (e: IOException) {
            FrostLogUtils.error("writeZipEntry err = %s", e)
        } finally {
            FrostIoUtils.close(fos)
        }
    }

    fun extractAPK(zipFilePath: String, destDir: String) {
        var zipInputStream: ZipInputStream? = null
        val zipEntryNameMap = HashMap<String, Int>()
        try {
            zipInputStream = ZipInputStream(FileInputStream(zipFilePath))
            var zipEntry: ZipEntry? = null
            while (zipInputStream.nextEntry.also { zipEntry = it } != null) {
                val zipEntryName = zipEntry!!.name
                val compressionMethod = CompressionMethod.getCompressionMethodFromCode(zipEntry!!.method)
                compressedLevelMap[zipEntryName] = compressionMethod
                val lowerCase = zipEntryName.lowercase(Locale.US)
                var finalFileName: Any = zipEntryName
                if (zipEntryNameMap[lowerCase] != null) {
                    val num = zipEntryNameMap[lowerCase]!! + 1
                    finalFileName = zipEntryName + RENAME_SUFFIX + num
                    zipEntryNameMap[lowerCase] = num
                } else {
                    zipEntryNameMap[lowerCase] = 0
                }
                writeZipEntry(zipInputStream, destDir + File.separator + finalFileName)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            FrostIoUtils.close(zipInputStream)
        }
    }

    fun extractFile(zipFilePath: String, fileName: String, destDir: String) {
        var zipFile: ZipFile? = null
        try {
            zipFile = ZipFile(zipFilePath)
            val fileHeader = zipFile.getFileHeader(fileName)
            zipFile.extractFile(fileHeader, destDir)
        } catch (e: ZipException) {
            e.printStackTrace()
        } finally {
            FrostIoUtils.close(zipFile)
        }
    }

    fun compress(files: List<File>?, destFile: String, rulesMap: Map<String, CompressionMethod>?) {
        if (files == null) {
            return
        }
        var zipFile: ZipFile? = null
        try {
            zipFile = ZipFile(destFile)
            for (f in files) {
                val zipParameters = ZipParameters()
                if (rulesMap != null) {
                    for (key in rulesMap.keys) {
                        if (!f.name.matches(Regex(key))) continue
                        zipParameters.compressionMethod = rulesMap[key]
                        break
                    }
                }
                if (f.isDirectory) {
                    zipFile.addFolder(f.absoluteFile, zipParameters)
                } else {
                    zipFile.addFile(f.absoluteFile, zipParameters)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            FrostIoUtils.close(zipFile)
        }
    }

    fun unZip(zipPath: String, dirPath: String) {
        var zipFile: java.util.zip.ZipFile? = null
        try {
            val zip = File(zipPath)
            val dir = File(dirPath)
            if (dir.exists()) {
                FrostFileUtils.deleteRecurse(dir)
            }
            zipFile = java.util.zip.ZipFile(zip)
            val entries = zipFile.entries()
            while (entries.hasMoreElements()) {
                val zipEntry = entries.nextElement()
                val name = zipEntry.name
                if ((name.startsWith("META-INF/") && isSignatureMetaInfFile(name)) || zipEntry.isDirectory) continue
                var file = File(dir, name)
                if (file.exists()) {
                    val fileName = file.name
                    var count = 1
                    for (v in resConflictFiles.values) {
                        if (!v.equals(fileName, ignoreCase = true)) continue
                        ++count
                    }
                    do {
                        val rename = count.toString() + fileName
                        file = File(file.parentFile, rename)
                        ++count
                    } while (file.exists())
                    resConflictFiles[file.name] = fileName
                }
                if (!file.parentFile.exists()) {
                    file.parentFile.mkdirs()
                }
                if (zipEntry.compressedSize == zipEntry.size) {
                    doNotCompress.add(file.absolutePath.replace(dir.absolutePath + File.separator, ""))
                }
                FileOutputStream(file).use { fos ->
                    val inputStream = zipFile.getInputStream(zipEntry)
                    try {
                        val buffer = ByteArray(1024)
                        var len = 0
                        while (inputStream.read(buffer).also { len = it } != -1) {
                            fos.write(buffer, 0, len)
                        }
                    } finally {
                        inputStream.close()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            FrostIoUtils.close(zipFile)
        }
    }

    fun zip(dirPath: String, zipPath: String, smaller: Boolean) {
        if (smaller) {
            doNotCompress.removeAll(biggerFileList())
        }
        var zos: ZipOutputStream? = null
        try {
            val zip = File(zipPath)
            if (zip.exists()) {
                zip.delete()
            }
            val cos = CheckedOutputStream(Files.newOutputStream(zip.toPath()), CRC32())
            zos = ZipOutputStream(cos)
            for (i in doNotCompress.indices) {
                var check = doNotCompress[i]
                check = check.replace("/", Matcher.quoteReplacement(File.separator))
                doNotCompress[i] = check
            }
            val dir = File(dirPath)
            compress(dir, zos, "", doNotCompress, resConflictFiles)
            zos.flush()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            FrostIoUtils.close(zos)
        }
    }

    @Throws(Exception::class)
    private fun compress(
        srcFile: File,
        zos: ZipOutputStream,
        basePath: String,
        doNotCompress: List<String>,
        resConflictFiles: Map<String, String>
    ) {
        if (srcFile.isDirectory) {
            compressDir(srcFile, zos, basePath, doNotCompress, resConflictFiles)
        } else {
            compressFile(srcFile, zos, basePath, doNotCompress, resConflictFiles)
        }
    }

    @Throws(Exception::class)
    private fun compressDir(
        dir: File,
        zos: ZipOutputStream,
        basePath: String,
        doNotCompress: List<String>,
        resConflictFiles: Map<String, String>
    ) {
        val files = dir.listFiles()
            ?: return
        if (files.isEmpty()) {
            val entryName = basePath + dir.name + "/"
            val entry = ZipEntry(entryName)
            zos.putNextEntry(entry)
            zos.closeEntry()
        }
        for (file in files) {
            compress(file, zos, basePath + dir.name + "/", doNotCompress, resConflictFiles)
        }
    }

    @Throws(Exception::class)
    private fun compressFile(
        file: File,
        zos: ZipOutputStream,
        dir: String,
        doNotCompress: List<String>,
        resConflictFiles: Map<String, String>
    ) {
        var fileName = file.name
        if (resConflictFiles.containsKey(fileName)) {
            fileName = resConflictFiles[fileName]!!
        }
        val dirName = dir + fileName
        if (dirName.contains(META_INF_NAME) && isSignatureMetaInfFile(dirName)) {
            return
        }
        val dirNameNew = dirName.split("/")
        val buffer = StringBuilder()
        if (dirNameNew.size > 1) {
            for (i in 1 until dirNameNew.size) {
                buffer.append("/")
                buffer.append(dirNameNew[i])
            }
        } else {
            buffer.append("/")
        }
        val entry = ZipEntry(buffer.substring(1))
        val rawPath = file.absolutePath
        val index = rawPath.indexOf(dirNameNew[0])
        if (index != -1 && doNotCompress.contains(rawPath.substring(index + 1 + dirNameNew[0].length))) {
            entry.method = ZipEntry.STORED
            entry.size = file.length()
            entry.crc = calFileCRC32(file)
        }
        zos.putNextEntry(entry)
        BufferedInputStream(FileInputStream(file)).use { bis ->
            val data = ByteArray(1024)
            var count = 0
            while (bis.read(data, 0, 1024).also { count = it } != -1) {
                zos.write(data, 0, count)
            }
        }
        zos.closeEntry()
    }

    @Throws(IOException::class)
    private fun calFileCRC32(file: File): Long {
        var crc = CRC32()
        BufferedInputStream(FileInputStream(file)).use { bis ->
            val data = ByteArray(1024)
            var count = 0
            while (bis.read(data, 0, 1024).also { count = it } != -1) {
                crc.update(data, 0, count)
            }
        }
        return crc.value
    }
}
