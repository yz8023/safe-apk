package com.adfxcbnm.frostshell.util

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile

object FrostIoUtils {
    fun readFile(file: String, offset: Long, len: Int): ByteArray {
        var fileInputStream: FileInputStream? = null
        val byteArrayOutputStream = ByteArrayOutputStream()
        try {
            fileInputStream = FileInputStream(file)
            val buf = ByteArray(len)
            fileInputStream.skip(offset)
            fileInputStream.read(buf)
            byteArrayOutputStream.write(buf)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            close(fileInputStream)
            close(byteArrayOutputStream)
        }
        return byteArrayOutputStream.toByteArray()
    }

    fun writeFile(dest: String, data: ByteArray, offset: Long) {
        var randomAccessFile: RandomAccessFile? = null
        try {
            randomAccessFile = RandomAccessFile(File(dest), "rw")
            randomAccessFile.seek(offset)
            randomAccessFile.write(data)
        } catch (e: IOException) {
            e.printStackTrace()
        } finally {
            close(randomAccessFile)
        }
    }

    fun readFile(file: String): ByteArray {
        var fileInputStream: FileInputStream? = null
        val byteArrayOutputStream = ByteArrayOutputStream()
        try {
            fileInputStream = FileInputStream(file)
            val buf = ByteArray(4096)
            var len: Int
            while (fileInputStream.read(buf).also { len = it } != -1) {
                byteArrayOutputStream.write(buf, 0, len)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            close(fileInputStream)
            close(byteArrayOutputStream)
        }
        return byteArrayOutputStream.toByteArray()
    }

    fun writeFile(dest: String, data: ByteArray) {
        writeFile(dest, data, false)
    }

    fun appendFile(dest: String, data: ByteArray) {
        writeFile(dest, data, true)
    }

    fun writeFile(dest: String, data: ByteArray, append: Boolean) {
        var fileOutputStream: FileOutputStream? = null
        try {
            fileOutputStream = FileOutputStream(dest, append)
            fileOutputStream.write(data)
        } catch (e: IOException) {
            e.printStackTrace()
        } finally {
            close(fileOutputStream)
        }
    }

    fun copyFile(src: String, dest: String) {
        var fileInputStream: FileInputStream? = null
        var fileOutputStream: FileOutputStream? = null
        try {
            fileInputStream = FileInputStream(src)
            fileOutputStream = FileOutputStream(dest)
            val buf = ByteArray(4096)
            var len: Int
            while (fileInputStream.read(buf).also { len = it } != -1) {
                fileOutputStream.write(buf, 0, len)
            }
        } catch (e: IOException) {
            e.printStackTrace()
        } finally {
            close(fileInputStream)
            close(fileOutputStream)
        }
    }

    fun close(closeable: Closeable?) {
        if (closeable != null) {
            try {
                closeable.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }
}
