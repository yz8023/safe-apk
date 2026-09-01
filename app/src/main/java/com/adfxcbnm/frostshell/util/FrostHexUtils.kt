package com.adfxcbnm.frostshell.util

import java.util.Arrays
import java.util.Locale

object FrostHexUtils {
    fun toHexArray(data: ByteArray): String = Arrays.toString(toHexStringArray(data))

    fun toHexString(l: Long): String = "0x" + java.lang.Long.toHexString(l)

    fun toHexString(data: ByteArray): String {
        val hexStringArray = toHexStringArray(data)
        val result = StringBuilder()
        for (s in hexStringArray) {
            result.append(s)
        }
        return result.toString()
    }

    private fun toHexStringArray(data: ByteArray): Array<String> {
        val array = arrayOfNulls<String>(data.size)
        for (i in data.indices) {
            var value = data[i].toInt()
            if (data[i] < 0) {
                value = data[i] + 256
            }
            array[i] = String.format(Locale.US, "%02x", value)
        }
        @Suppress("UNCHECKED_CAST")
        return array as Array<String>
    }
}
