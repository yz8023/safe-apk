package com.adfxcbnm.frostshell.util

object FrostEndian {
    fun makeLittleEndian(number: Int): ByteArray {
        val bytes = byteArrayOf(
            (number and 0xFF).toByte(),
            ((number and 0xFF00) shr 8).toByte(),
            ((number and 0xFF0000) shr 16).toByte(),
            ((number and -0x1000000) shr 24).toByte()
        )
        return bytes
    }

    fun makeLittleEndian(number: Short): ByteArray {
        val bytes = byteArrayOf(
            (number.toInt() and 0xFF).toByte(),
            ((number.toInt() and 0xFF00) shr 8).toByte()
        )
        return bytes
    }

    fun shortToInt16LE(value: Short): Int = value.toInt() and 0xFFFF

    fun int16LEToShort(value: Int): Short = value.toShort()

    fun readIntLE(bytes: ByteArray, offset: Int): Int {
        var value = 0
        for (i in 3 downTo 0) {
            value = (value shl 8) or (bytes[offset + i].toInt() and 0xFF)
        }
        return value
    }

    fun readShortLE(bytes: ByteArray, offset: Int): Short {
        var value = 0
        for (i in 1 downTo 0) {
            value = (value shl 8) or (bytes[offset + i].toInt() and 0xFF)
        }
        return value.toShort()
    }

    fun readUnsignedShortLE(bytes: ByteArray, offset: Int): Int = readShortLE(bytes, offset).toInt() and 0xFFFF
}
