package com.adfxcbnm.frostshell.util

import java.security.SecureRandom

object FrostStringUtils {
    fun generateIdentifier(minLength: Int): String {
        val secureRandom = SecureRandom()
        val cnt = secureRandom.nextInt(minLength) + minLength
        val sb = StringBuilder()
        for (i in 0 until cnt) {
            val baseChar = if (secureRandom.nextBoolean()) 65 else 97
            val index = secureRandom.nextInt(26)
            val ch = (baseChar + index).toChar()
            sb.append(ch)
        }
        return sb.toString()
    }

    fun capitalizeFirstLetter(str: String?): String? {
        if (str == null || str.isEmpty()) {
            return str
        }
        return str[0].uppercaseChar() + str.substring(1)
    }

    fun isEmpty(s: String?): Boolean = s.isNullOrEmpty()

    fun isNotEmpty(s: String?): Boolean = !s.isNullOrEmpty()

    fun isBlank(s: String?): Boolean = s.isNullOrBlank()

    fun isNotBlank(s: String?): Boolean = !s.isNullOrBlank()

    fun equalsIgnoreCase(a: String, b: String): Boolean = a.equals(b, ignoreCase = true)
}
