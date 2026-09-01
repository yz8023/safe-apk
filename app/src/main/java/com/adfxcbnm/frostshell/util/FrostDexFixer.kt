package com.adfxcbnm.frostshell.util

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object FrostDexFixer {
    private const val CHECKSUM_OFFSET = 8
    private const val SHA1_OFFSET = 12
    private const val FILE_SIZE_OFFSET = 32

    fun intToByte(value: Int): Byte = (value and 0xFF).toByte()

    fun fixCheckSumHeader(file: File, sha1: ByteArray) {
        val raf = java.io.RandomAccessFile(file, "rw")
        try {
            raf.seek(CHECKSUM_OFFSET.toLong())
            val checksum = computeAdler32(file, sha1.size)
            val buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            buffer.putInt(checksum)
            raf.write(buffer.array())
        } finally {
            raf.close()
        }
    }

    fun fixSHA1Header(file: File) {
        val raf = java.io.RandomAccessFile(file, "rw")
        try {
            val sha1 = computeSha1(file)
            raf.seek(SHA1_OFFSET.toLong())
            raf.write(sha1)
        } finally {
            raf.close()
        }
    }

    fun fixFileSizeHeader(file: File) {
        val raf = java.io.RandomAccessFile(file, "rw")
        try {
            val fileSize = file.length()
            raf.seek(FILE_SIZE_OFFSET.toLong())
            val buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            buffer.putInt(fileSize.toInt())
            raf.write(buffer.array())
        } finally {
            raf.close()
        }
    }

    fun computeSha1(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-1")
        java.io.FileInputStream(file).use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest()
    }

    fun computeAdler32(file: File, sha1Len: Int): Int {
        val data = java.io.FileInputStream(file).use { it.readBytes() }
        var a = 1
        var b = 0
        for (i in 12 until data.size - sha1Len) {
            a = (a + (data[i].toInt() and 0xFF)) % 65521
            b = (b + a) % 65521
        }
        return (b shl 16) or a
    }

    fun sha1(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-1").digest(bytes)

    fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
