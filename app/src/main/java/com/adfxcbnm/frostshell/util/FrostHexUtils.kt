package com.adfxcbnm.frostshell.util

object FrostHexUtils {

    private val HEX_CHARS = "0123456789abcdef".toCharArray()

    fun toHexArray(data: ByteArray): String = buildHex(data, "[", ", ", "]")

    fun toHexString(l: Long): String = "0x" + java.lang.Long.toHexString(l)

    fun toHexString(data: ByteArray): String = buildHex(data, "", "", "")

    private fun buildHex(data: ByteArray, prefix: String, separator: String, suffix: String): String {
        if (data.isEmpty()) return "$prefix$suffix"
        val sb = StringBuilder(data.size * 3 + 2)
        sb.append(prefix)
        for (i in data.indices) {
            if (i > 0) sb.append(separator)
            val v = data[i].toInt() and 0xFF
            sb.append(HEX_CHARS[v ushr 4]).append(HEX_CHARS[v and 0xF])
        }
        sb.append(suffix)
        return sb.toString()
    }
}
