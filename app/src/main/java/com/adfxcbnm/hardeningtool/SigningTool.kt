package com.adfxcbnm.hardeningtool

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.ECGenParameterSpec
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 签名工具：keystore 信息查看与生成（支持 RSA / EC，自签名证书用自建 DER 编码）。
 */
object SigningTool {

    data class KeystoreInfo(
        val file: String,
        val type: String,
        val alias: String,
        val subject: String,
        val issuer: String,
        val serial: String,
        val notBefore: String,
        val notAfter: String,
        val signatureAlg: String,
        val keyAlg: String,
        val keySize: String,
        val sha1: String,
        val sha256: String,
        val md5: String,
        val aliases: List<String>
    )

    /**
     * 读取 keystore 内指定别名的证书信息。
     * 支持 p12/pfx/jks/keystore/ks/bks。
     */
    fun loadKeystoreInfo(
        keystoreFile: File,
        storePass: String,
        aliasHint: String? = null
    ): KeystoreInfo? {
        if (!keystoreFile.exists() || keystoreFile.length() <= 0) return null
        val storePassArr = storePass.toCharArray()
        val types = detectTypes(keystoreFile)
        for (type in types) {
            try {
                val keyStore = KeyStore.getInstance(type)
                FileInputStream(keystoreFile).use { fis -> keyStore.load(fis, storePassArr) }
                val keyAliases = keyStore.aliases().toList()
                val alias = aliasHint?.takeIf { it.isNotBlank() && keyStore.containsAlias(it) }
                    ?: keyAliases.firstOrNull { keyStore.isKeyEntry(it) }
                    ?: continue
                val cert = keyStore.getCertificate(alias) as? X509Certificate ?: continue
                val publicKey = cert.publicKey
                val keyAlg = publicKey.algorithm
                val keySize = when (publicKey) {
                    is RSAPublicKey -> publicKey.modulus.bitLength().toString()
                    is ECPublicKey -> publicKey.params.curve.field.fieldSize.toString()
                    else -> ""
                }
                val encoded = cert.encoded
                val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                return KeystoreInfo(
                    file = keystoreFile.name,
                    type = type,
                    alias = alias,
                    subject = cert.subjectX500Principal.toString(),
                    issuer = cert.issuerX500Principal.toString(),
                    serial = cert.serialNumber.toString(16).uppercase(),
                    notBefore = dateFormat.format(cert.notBefore),
                    notAfter = dateFormat.format(cert.notAfter),
                    signatureAlg = cert.sigAlgName,
                    keyAlg = keyAlg,
                    keySize = keySize,
                    sha1 = fingerprint(encoded, "SHA-1"),
                    sha256 = fingerprint(encoded, "SHA-256"),
                    md5 = fingerprint(encoded, "MD5"),
                    aliases = keyAliases
                )
            } catch (e: Exception) {
                // try next type
            }
        }
        return null
    }

    data class GeneratedKeystore(
        val file: File,
        val type: String,
        val alias: String,
        val storePass: String,
        val keyPass: String,
        val info: KeystoreInfo?
    )

    data class KeystoreSpec(
        val alias: String,
        val storePass: String,
        val keyPass: String,
        val keyAlg: String,      // "RSA" / "EC"
        val keySize: Int,        // RSA bits（2048/3072/4096）或 EC 曲线代码（256/384/521）
        val validityDays: Int,
        val cn: String,
        val ou: String,
        val o: String,
        val l: String,
        val st: String,
        val c: String
    )

    /**
     * 生成自签名证书并写入新 keystore（PKCS12）。
     * 支持 RSA / EC 密钥与对应签名算法。
     */
    fun generateKeystore(
        target: File,
        spec: KeystoreSpec,
        onError: (String) -> Unit
    ): GeneratedKeystore? {
        return try {
            val keyPair = generateKeyPair(spec.keyAlg, spec.keySize)
            val now = System.currentTimeMillis()
            val notBefore = now - 24 * 60 * 60 * 1000L
            val notAfter = now + spec.validityDays.toLong() * 24 * 60 * 60 * 1000L

            val isEc = spec.keyAlg.equals("EC", true)
            val subject = buildDN(spec)
            val serial = BigInteger(128, SecureRandom())

            val tbs = buildTbsCertificate(subject, serial, notBefore, notAfter, keyPair.public)
            val sigAlgName = if (isEc) "SHA256withECDSA" else "SHA256withRSA"
            val sigOid = if (isEc) "1.2.840.10045.4.3.2" else "1.2.840.113549.1.1.11"
            val sig = Signature.getInstance(sigAlgName)
            sig.initSign(keyPair.private)
            sig.update(tbs)
            val signatureBytes = sig.sign()

            val fullCertDer = buildFullCertificate(tbs, signatureBytes, sigOid)
            val certFactory = java.security.cert.CertificateFactory.getInstance("X.509")
            val cert = certFactory.generateCertificate(
                java.io.ByteArrayInputStream(fullCertDer)
            ) as X509Certificate

            val keyStore = KeyStore.getInstance("PKCS12")
            keyStore.load(null, null)
            keyStore.setKeyEntry(spec.alias, keyPair.private, spec.keyPass.toCharArray(), arrayOf(cert))

            target.parentFile?.mkdirs()
            FileOutputStream(target).use { fos ->
                keyStore.store(fos, spec.storePass.toCharArray())
            }
            if (!target.exists() || target.length() <= 0) {
                onError("生成的 keystore 文件无效")
                return null
            }
            val info = loadKeystoreInfo(target, spec.storePass, spec.alias)
            GeneratedKeystore(target, "PKCS12", spec.alias, spec.storePass, spec.keyPass, info)
        } catch (e: Exception) {
            onError(e.message ?: e.javaClass.simpleName)
            null
        }
    }

    /**
     * 一键生成"超强"签名：RSA 4096 + 100 年有效期 + 随机强密码。
     */
    fun generateProKeystore(
        target: File,
        baseAlias: String = "adh",
        onError: (String) -> Unit
    ): GeneratedKeystore? {
        val strongPass = randomStrongPassword(20)
        return generateKeystore(
            target,
            KeystoreSpec(
                alias = baseAlias.ifBlank { "adh" },
                storePass = strongPass,
                keyPass = strongPass,
                keyAlg = "RSA",
                keySize = 4096,
                validityDays = 36500,
                cn = "Android Debug (Pro)",
                ou = "ADFXCBNM",
                o = "ADFXCBNM",
                l = "",
                st = "",
                c = "CN"
            ),
            onError
        )
    }

    fun generateKeyPair(keyAlg: String, keySize: Int): KeyPair {
        val generator = KeyPairGenerator.getInstance(keyAlg.uppercase())
        if (keyAlg.equals("EC", true)) {
            val curve = when (keySize) {
                384 -> "secp384r1"
                521 -> "secp521r1"
                else -> "secp256r1"
            }
            generator.initialize(ECGenParameterSpec(curve), SecureRandom())
        } else {
            generator.initialize(keySize.coerceAtLeast(2048), SecureRandom())
        }
        return generator.generateKeyPair()
    }

    fun randomStrongPassword(length: Int = 20): String {
        val upper = "ABCDEFGHJKLMNPQRSTUVWXYZ"
        val lower = "abcdefghjkmnpqrstuvwxyz"
        val digits = "23456789"
        val symbols = "!@#%&*"
        val all = upper + lower + digits + symbols
        val random = SecureRandom()
        val sb = StringBuilder(length)
        sb.append(upper[random.nextInt(upper.length)])
        sb.append(lower[random.nextInt(lower.length)])
        sb.append(digits[random.nextInt(digits.length)])
        sb.append(symbols[random.nextInt(symbols.length)])
        for (i in 4 until length) {
            sb.append(all[random.nextInt(all.length)])
        }
        return sb.toString()
    }

    fun buildDN(spec: KeystoreSpec): String {
        val parts = mutableListOf("CN=${spec.cn}")
        if (spec.ou.isNotBlank()) parts.add("OU=${spec.ou}")
        if (spec.o.isNotBlank()) parts.add("O=${spec.o}")
        if (spec.l.isNotBlank()) parts.add("L=${spec.l}")
        if (spec.st.isNotBlank()) parts.add("ST=${spec.st}")
        if (spec.c.isNotBlank()) parts.add("C=${spec.c}")
        return parts.joinToString(",")
    }

    // ===== 自建 ASN.1/DER 编码（RSA + EC）=====

    private fun buildTbsCertificate(
        subject: String,
        serial: BigInteger,
        notBefore: Long,
        notAfter: Long,
        publicKey: PublicKey
    ): ByteArray {
        val version = asn1ExplicitTag(0, asn1Integer(byteArrayOf(0x02)))
        val serialBytes = asn1Integer(serial.toByteArray())
        val sigAlg = asn1Sequence(sigAlgOid(publicKey) + asn1Null())
        val issuerDn = buildDNEncoding(subject)
        val subjectDn = buildDNEncoding(subject)
        val validity = asn1Sequence(asn1Time(notBefore) + asn1Time(notAfter))
        val subjectPublicKeyInfo = buildSubjectPublicKeyInfo(publicKey)
        val extensions = asn1ExplicitTag(3, asn1Sequence(buildKeyUsageExtension() + buildBasicConstraintsExtension()))

        val tbsContent = version + serialBytes + sigAlg + issuerDn + validity + subjectDn + subjectPublicKeyInfo + extensions
        return asn1Sequence(tbsContent)
    }

    private fun buildFullCertificate(tbsCert: ByteArray, signature: ByteArray, sigOid: String): ByteArray {
        val sigAlg = asn1Sequence(asn1Oid(sigOid) + asn1Null())
        val sigBits = asn1BitString(signature)
        return asn1Sequence(tbsCert + sigAlg + sigBits)
    }

    private fun sigAlgOid(publicKey: PublicKey): ByteArray =
        asn1Oid("1.2.840.113549.1.1.11") // SHA256withRSA

    private fun buildSubjectPublicKeyInfo(publicKey: PublicKey): ByteArray {
        if (publicKey !is RSAPublicKey) {
            throw IllegalArgumentException("当前仅支持 RSA 密钥生成")
        }
        val modulusInt = asn1Integer(publicKey.modulus.toByteArray())
        val exponentInt = asn1Integer(publicKey.publicExponent.toByteArray())
        val rsaPublicKey = asn1Sequence(modulusInt + exponentInt)
        val keyBits = asn1BitString(rsaPublicKey)
        val algId = asn1Sequence(asn1Oid("1.2.840.113549.1.1.1") + asn1Null())
        return asn1Sequence(algId + keyBits)
    }

    private fun buildKeyUsageExtension(): ByteArray {
        val keyUsageBits = byteArrayOf(0xA0.toByte(), 0x00)
        val keyUsageValue = asn1BitString(keyUsageBits)
        return asn1Sequence(asn1Oid("2.5.29.15") + asn1OctetString(keyUsageValue))
    }

    private fun buildBasicConstraintsExtension(): ByteArray {
        val bcValue = asn1Sequence(asn1Boolean(true) + asn1Integer(byteArrayOf(0x00)))
        return asn1Sequence(asn1Oid("2.5.29.19") + asn1OctetString(bcValue))
    }

    private fun buildDNEncoding(dn: String): ByteArray {
        val parts = dn.split(",").map { it.trim() }
        val rdnList = mutableListOf<ByteArray>()
        for (part in parts) {
            val kv = part.split("=")
            if (kv.size == 2) {
                val oid = when (kv[0]) {
                    "CN" -> "2.5.4.3"
                    "OU" -> "2.5.4.11"
                    "O" -> "2.5.4.10"
                    "L" -> "2.5.4.7"
                    "ST" -> "2.5.4.8"
                    "C" -> "2.5.4.6"
                    else -> "2.5.4.3"
                }
                val value = asn1PrintableString(kv[1])
                rdnList.add(asn1Set(asn1Sequence(asn1Oid(oid) + value)))
            }
        }
        return asn1Sequence(rdnList.reduceOrNull { a, b -> a + b } ?: ByteArray(0))
    }

    private fun asn1Sequence(content: ByteArray): ByteArray = byteArrayOf(0x30) + encodeAsn1Length(content.size) + content
    private fun asn1Set(content: ByteArray): ByteArray = byteArrayOf(0x31) + encodeAsn1Length(content.size) + content
    private fun asn1Integer(value: ByteArray): ByteArray {
        var v = value
        while (v.size > 1 && v[0] == 0x00.toByte() && (v[1].toInt() and 0x80) == 0) {
            v = v.copyOfRange(1, v.size)
        }
        if ((v[0].toInt() and 0x80) != 0) {
            v = byteArrayOf(0x00) + v
        }
        return byteArrayOf(0x02) + encodeAsn1Length(v.size) + v
    }

    private fun asn1Oid(oid: String): ByteArray {
        val parts = oid.split(".").map { it.toLong() }
        val encoded = mutableListOf<Byte>()
        encoded.add((parts[0] * 40 + parts[1]).toByte())
        for (i in 2 until parts.size) {
            var value = parts[i]
            val bytes = mutableListOf<Byte>()
            if (value == 0L) {
                bytes.add(0)
            } else {
                while (value > 0) {
                    bytes.add(0, ((value and 0x7F) or if (bytes.isEmpty()) 0x00 else 0x80).toByte())
                    value = value shr 7
                }
            }
            encoded.addAll(bytes)
        }
        return byteArrayOf(0x06) + encodeAsn1Length(encoded.size) + encoded.toByteArray()
    }

    private fun asn1BitString(value: ByteArray): ByteArray {
        val content = byteArrayOf(0x00) + value
        return byteArrayOf(0x03) + encodeAsn1Length(content.size) + content
    }

    private fun asn1PrintableString(value: String): ByteArray {
        val bytes = value.toByteArray(Charsets.US_ASCII)
        return byteArrayOf(0x13) + encodeAsn1Length(bytes.size) + bytes
    }

    private fun asn1UtcTime(millis: Long): ByteArray {
        val df = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US)
        df.timeZone = java.util.TimeZone.getTimeZone("UTC")
        val timeStr = df.format(java.util.Date(millis))
        return byteArrayOf(0x17) + encodeAsn1Length(timeStr.length) + timeStr.toByteArray(Charsets.US_ASCII)
    }

    private fun asn1GeneralizedTime(millis: Long): ByteArray {
        val df = SimpleDateFormat("yyyyMMddHHmmss'Z'", Locale.US)
        df.timeZone = java.util.TimeZone.getTimeZone("UTC")
        val timeStr = df.format(java.util.Date(millis))
        return byteArrayOf(0x18) + encodeAsn1Length(timeStr.length) + timeStr.toByteArray(Charsets.US_ASCII)
    }

    private fun asn1Time(millis: Long): ByteArray {
        val year = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            .apply { timeInMillis = millis }.get(java.util.Calendar.YEAR)
        // 2050 年后 UTCTime 只支持 2 位年，改用 GeneralizedTime
        return if (year >= 2050) asn1GeneralizedTime(millis) else asn1UtcTime(millis)
    }

    private fun asn1Null(): ByteArray = byteArrayOf(0x05, 0x00)
    private fun asn1Boolean(value: Boolean): ByteArray = byteArrayOf(0x01, 0x01, if (value) 0xFF.toByte() else 0x00)
    private fun asn1OctetString(value: ByteArray): ByteArray = byteArrayOf(0x04) + encodeAsn1Length(value.size) + value

    private fun asn1ExplicitTag(tag: Int, content: ByteArray): ByteArray {
        return byteArrayOf((0xA0 or tag).toByte()) + encodeAsn1Length(content.size) + content
    }

    private fun encodeAsn1Length(length: Int): ByteArray {
        if (length < 128) {
            return byteArrayOf(length.toByte())
        }
        val bytes = mutableListOf<Byte>()
        var v = length
        while (v > 0) {
            bytes.add(0, (v and 0xFF).toByte())
            v = v shr 8
        }
        return byteArrayOf((0x80 or bytes.size).toByte()) + bytes.toByteArray()
    }

    private fun detectTypes(keystoreFile: File): LinkedHashSet<String> {
        val name = keystoreFile.name.lowercase()
        val extType = when {
            name.endsWith(".jks") || name.endsWith(".keystore") || name.endsWith(".ks") -> "JKS"
            name.endsWith(".bks") -> "BKS"
            else -> "PKCS12"
        }
        return linkedSetOf(extType, "PKCS12", "JKS", "BKS")
    }

    private fun fingerprint(encoded: ByteArray, algo: String): String {
        return MessageDigest.getInstance(algo).digest(encoded)
            .joinToString("") { "%02x".format(it) }
            .uppercase()
    }
}
