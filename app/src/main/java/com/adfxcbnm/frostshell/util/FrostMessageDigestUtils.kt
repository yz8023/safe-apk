package com.adfxcbnm.frostshell.util

import java.security.MessageDigest
import java.util.Locale

object FrostMessageDigestUtils {
    private const val ALGORITHM_MD5 = "md5"
    private const val ALGORITHM_SHA256 = "sha-256"

    fun hash(algorithm: String, input: ByteArray): String {
        val ret = StringBuilder()
        try {
            val messageDigest = MessageDigest.getInstance(algorithm)
            val buf = messageDigest.digest(input)
            for (n in buf) {
                var val_ = n.toInt()
                if (val_ < 0) {
                    val_ += 256
                }
                val hex = String.format(Locale.US, "%02x", val_)
                ret.append(hex)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return ret.toString()
    }

    fun md5(input: ByteArray): String = hash(ALGORITHM_MD5, input)

    fun shortMd5(input: ByteArray): String = md5(input).substring(8, 24)

    fun sha256(input: ByteArray): String = hash(ALGORITHM_SHA256, input)

    fun shortSha256Left(input: ByteArray): String = sha256(input).substring(0, 32)

    fun shortSha256Right(input: ByteArray): String = sha256(input).substring(32, 64)
}
