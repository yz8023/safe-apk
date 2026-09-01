package com.adfxcbnm.frostshell.dex

import java.security.SecureRandom

object FrostStringXorCipher {
    private val RANDOM = SecureRandom()

    fun randomKey(): Int = RANDOM.nextInt(255) + 1

    fun encrypt(plain: String, key: Int): String {
        val chars = plain.toCharArray()
        for (i in chars.indices) {
            chars[i] = (chars[i].code xor key).toChar()
        }
        return String(chars)
    }
}
