package com.adfxcbnm.frostshell.util

import java.security.SecureRandom

object FrostKeyUtils {
    fun deriveIV(key: ByteArray, label: String): ByteArray {
        val mac = FrostCryptoUtils.hmacSha256(key, label)
        return mac.copyOfRange(0, 16)
    }

    fun generateKey(): ByteArray {
        val rc4key = ByteArray(16)
        SecureRandom().nextBytes(rc4key)
        return rc4key
    }

    fun randomBytes(n: Int): ByteArray {
        val b = ByteArray(n)
        SecureRandom().nextBytes(b)
        return b
    }

    fun randomName(len: Int): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        val rnd = SecureRandom()
        val sb = StringBuilder(len)
        for (i in 0 until len) {
            sb.append(alphabet[rnd.nextInt(alphabet.length)])
        }
        return sb.toString()
    }

    fun toHex(data: ByteArray): String {
        val sb = StringBuilder(data.size * 2)
        for (b in data) {
            sb.append("%02x".format(b))
        }
        return sb.toString()
    }

    fun fromHex(hex: String): ByteArray {
        val len = hex.length
        val out = ByteArray(len / 2)
        for (i in out.indices) {
            out[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    fun xorWithKey(key: ByteArray, input: ByteArray): ByteArray {
        if (key.isEmpty()) return input.copyOf()
        val out = ByteArray(input.size)
        for (i in input.indices) {
            out[i] = (input[i].toInt() xor key[i % key.size].toInt()).toByte()
        }
        return out
    }

    fun xorWithKeyIv(key: ByteArray, iv: ByteArray, input: ByteArray): ByteArray {
        if (key.isEmpty()) throw IllegalArgumentException("key is empty")
        if (iv.isEmpty()) throw IllegalArgumentException("iv is empty")
        val out = ByteArray(input.size)
        for (i in input.indices) {
            val r1 = key[i % key.size].toInt() and 0xFF
            val r2 = iv[i % iv.size].toInt() and 0xFF
            out[i] = (input[i].toInt() xor r1 xor r2).toByte()
        }
        return out
    }
}
