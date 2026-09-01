package com.adfxcbnm.frostshell.util

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object FrostCryptoUtils {
    private const val RC4_TRANSFORM = "RC4"
    private const val HMAC_SHA256 = "HmacSHA256"

    fun rc4Crypt(key: ByteArray, input: ByteArray): ByteArray? {
        return try {
            val cipher = Cipher.getInstance(RC4_TRANSFORM)
            val spec = SecretKeySpec(key, RC4_TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, spec)
            cipher.doFinal(input)
        } catch (e: Exception) {
            null
        }
    }

    fun deriveKey(masterKey: ByteArray, label: String): ByteArray = hmacSha256(masterKey, label)

    fun buildInsnsRc4Key(aesKey: ByteArray, methodIndex: Int): ByteArray {
        if (aesKey.isEmpty()) throw IllegalArgumentException("aes key is empty")
        val buf = ByteBuffer.allocate(aesKey.size + 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(aesKey)
        buf.putInt(methodIndex)
        return buf.array()
    }

    fun hmacSha256(key: ByteArray, keyMaterial: String): ByteArray {
        if (key.isEmpty()) throw IllegalArgumentException("hmac key is empty")
        if (keyMaterial.isEmpty()) throw IllegalArgumentException("key material is empty")
        return try {
            val mac = Mac.getInstance(HMAC_SHA256)
            mac.init(SecretKeySpec(key, HMAC_SHA256))
            val result = mac.doFinal(keyMaterial.toByteArray(StandardCharsets.UTF_8))
            if (result.size != 32) throw IllegalStateException("unexpected hmac length")
            result
        } catch (e: Exception) {
            throw IllegalStateException("hmac-sha256 failed", e)
        }
    }

    fun aesEncrypt(key: ByteArray, iv: ByteArray, input: ByteArray): ByteArray? {
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            cipher.doFinal(input)
        } catch (e: Exception) {
            null
        }
    }
}
