package com.adfxcbnm.hardeningtool

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Security
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

    /** X.509 GeneralizedTime 年份上限 9999-12-31 23:59:59.999 UTC */
    private val X509_MAX_NOT_AFTER: Long = run {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(9999, java.util.Calendar.DECEMBER, 31, 23, 59, 59)
        cal.set(java.util.Calendar.MILLISECOND, 999)
        cal.timeInMillis
    }

    private val providerRegistered = try {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())
        }
        true
    } catch (t: Throwable) {
        false
    }

    /** BKS/BKS-V1/UBER 等格式由 BouncyCastle provider 提供；注册失败时这些格式不可用。 */
    private fun bcProviderAvailable(): Boolean = providerRegistered

    /**
     * JKS 的纯解析依赖自实现 JksParser（Android 无 SunJCE JKS provider），
     * 因此 JKS 文件无法通过 KeyStore API 读取要求 provider 的格式。
     * 但真正的 .bks 文件必须走 BouncyCastle 的 KeyStore，而不是 JKS 解析。
     */

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

        // pk8/pem：直接读同名 x509.pem 证书信息
        val name = keystoreFile.name.lowercase()
        if (name.endsWith(".pk8") || name.endsWith(".key") || name.endsWith(".pem")) {
            return loadPk8PemInfo(keystoreFile)
        }

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
        // 回退：JKS provider 可能不存在（Android），用纯解析
        return try {
            loadJksInfoManually(keystoreFile, storePass, aliasHint)
        } catch (e: Exception) {
            null
        }
    }

    /** pk8/pem 证书信息查看 */
    private fun loadPk8PemInfo(keystoreFile: File): KeystoreInfo? {
        val name = keystoreFile.name.lowercase()
        val pemFile = if (name.endsWith(".pem")) keystoreFile else {
            val base = keystoreFile.name.removeSuffix(name.substringAfterLast('.')).trimEnd('.')
            File(keystoreFile.parentFile, "$base.x509.pem").takeIf { it.exists() }
                ?: File(keystoreFile.parentFile, "$base.pem").takeIf { it.exists() }
                ?: return null
        }
        return try {
            val cert = parsePemCertificate(pemFile.readBytes()) ?: return null
            val publicKey = cert.publicKey
            val keySize = when (publicKey) {
                is RSAPublicKey -> publicKey.modulus.bitLength().toString()
                is ECPublicKey -> publicKey.params.curve.field.fieldSize.toString()
                else -> ""
            }
            val encoded = cert.encoded
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            KeystoreInfo(
                file = keystoreFile.name,
                type = "PK8/PEM",
                alias = cert.subjectX500Principal.toString(),
                subject = cert.subjectX500Principal.toString(),
                issuer = cert.issuerX500Principal.toString(),
                serial = cert.serialNumber.toString(16).uppercase(),
                notBefore = dateFormat.format(cert.notBefore),
                notAfter = dateFormat.format(cert.notAfter),
                signatureAlg = cert.sigAlgName,
                keyAlg = publicKey.algorithm,
                keySize = keySize,
                sha1 = fingerprint(encoded, "SHA-1"),
                sha256 = fingerprint(encoded, "SHA-256"),
                md5 = fingerprint(encoded, "MD5"),
                aliases = emptyList()
            )
        } catch (e: Exception) {
            null
        }
    }

    /** 纯解析 JKS 获取信息（Android 无 JKS provider 时使用） */
    private fun loadJksInfoManually(keystoreFile: File, storePass: String, aliasHint: String?): KeystoreInfo? {
        val data = keystoreFile.readBytes()
        if (data.size < 12 || data[0] != 0xFE.toByte() || data[1] != 0xED.toByte()) return null // 非 JKS
        return try {
            val m = JksParser(data)
            val keys = m.parseAllKeys(storePass.toCharArray())
            val target = aliasHint?.takeIf { it.isNotBlank() }
                ?.let { hint -> keys.firstOrNull { it.alias == hint } }
                ?: keys.firstOrNull()
            ?: return null
            val cert = target.certificate
            val pk = target.privateKey
            val pub = cert.publicKey
            val keyAlg = pub.algorithm
            val keySize = when (pub) {
                is RSAPublicKey -> pub.modulus.bitLength().toString()
                is ECPublicKey -> pub.params.curve.field.fieldSize.toString()
                else -> ""
            }
            val encoded = cert.encoded
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            KeystoreInfo(
                file = keystoreFile.name,
                type = "JKS",
                alias = target.alias,
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
                aliases = keys.map { it.alias }
            )
        } catch (e: Exception) {
            null
        }
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
        val c: String,
        val notBeforeMillis: Long? = null,
        val notAfterMillis: Long? = null
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
            val notBefore = spec.notBeforeMillis?.let { normalizeDateStart(it) }
                ?: (now - 24 * 60 * 60 * 1000L)
            var notAfter = spec.notAfterMillis?.let { normalizeDateEnd(it) }
                ?: (now + spec.validityDays.toLong() * 24 * 60 * 60 * 1000L)
            // X.509 GeneralizedTime 年份上限为 9999，超限钳制到 9999-12-31 23:59:59.999
            if (notAfter > X509_MAX_NOT_AFTER) {
                notAfter = X509_MAX_NOT_AFTER
            }

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

    private fun normalizeDateStart(millis: Long): Long {
        return normalizeDateStart(java.util.Date(millis))
    }

    private fun normalizeDateEnd(millis: Long): Long {
        return normalizeDateEnd(java.util.Date(millis))
    }

    /** 起始日期归一到当天 00:00:00 */
    private fun normalizeDateStart(d: java.util.Date): Long {
        val cal = java.util.Calendar.getInstance()
        cal.time = d
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /** 结束日期归一到当天 23:59:59（含当日整天） */
    private fun normalizeDateEnd(d: java.util.Date): Long {
        val cal = java.util.Calendar.getInstance()
        cal.time = d
        cal.set(java.util.Calendar.HOUR_OF_DAY, 23)
        cal.set(java.util.Calendar.MINUTE, 59)
        cal.set(java.util.Calendar.SECOND, 59)
        cal.set(java.util.Calendar.MILLISECOND, 999)
        return cal.timeInMillis
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
        val asciiPrintable = value.all { it.code in 0x20..0x7E }
        val bytes = value.toByteArray(Charsets.UTF_8)
        return byteArrayOf(if (asciiPrintable) 0x13 else 0x0C) + encodeAsn1Length(bytes.size) + bytes
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
        var m = millis
        if (m > X509_MAX_NOT_AFTER) m = X509_MAX_NOT_AFTER
        val year = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            .apply { timeInMillis = m }.get(java.util.Calendar.YEAR)
        // 2050 年后 UTCTime 只支持 2 位年，改用 GeneralizedTime
        return if (year >= 2050) asn1GeneralizedTime(m) else asn1UtcTime(m)
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

    // ===== JKS 纯解析（Android 无 JKS provider，需自行实现）=====

    data class SignatureKey(
        val alias: String,
        val privateKey: PrivateKey,
        val certificate: X509Certificate
    )

    /**
     * 纯 Kotlin 解析 JKS v2 文件（Android 无 SunJCE JKS provider，必须自实现）。
     * 返回所有 PrivateKeyEntry 的可签名密钥对。
     */
    fun loadKeysFromJks(keystoreFile: File, storePass: String, aliasHint: String? = null): SignatureKey? {
        val data = keystoreFile.readBytes()
        if (data.size < 12) return null
        if (data[0] != 0xFE.toByte() || data[1] != 0xED.toByte()) return null // FEEDFEED 魔数
        return try {
            val m = JksParser(data)
            val pass = storePass.toCharArray()
            val keys = m.parseAllKeys(pass)
            val target = aliasHint?.takeIf { it.isNotBlank() }
                ?.let { hint -> keys.firstOrNull { it.alias == hint } }
                ?: keys.firstOrNull()
            target
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 从 PKCS8 私钥文件（.pk8/.key DER 或 PEM）+ X509 证书文件（.pem/.crt）加载签名密钥对。
     * 私有密钥支持未加密 PKCS8 与加密 PKCS8（EncryptedPrivateKeyInfo，PBES2/PBE）。
     */
    fun loadKeysFromPk8Pem(
        pk8File: File,
        pemFile: File,
        keyPass: String? = null
    ): SignatureKey? {
        return try {
            val pemCert = parsePemCertificate(pemFile.readBytes())
            val raw = pk8File.readBytes()
            val privateKey = parsePk8(raw, keyPass) ?: return null
            SignatureKey("pk8pem", privateKey, pemCert)
        } catch (e: Exception) {
            null
        }
    }

    /** 解析 X509 PEM/DER 证书 */
    fun parsePemCertificate(data: ByteArray): X509Certificate {
        val der = pemToDer(data)
        val cf = java.security.cert.CertificateFactory.getInstance("X.509")
        return cf.generateCertificate(java.io.ByteArrayInputStream(der)) as X509Certificate
    }

    /**
     * 解析 PKCS8 私钥（DER 或 PEM）。支持：
     * - 未加密 PrivateKeyInfo
     * - EncryptedPrivateKeyInfo（需密码）
     */
    fun parsePk8(data: ByteArray, keyPass: String?): PrivateKey? {
        var raw = pemToDer(data)
        // 检测是否 EncryptedPrivateKeyInfo（外层 SEQUENCE 内为 AlgorithmIdentifier + OCTET STRING）
        if (isEncryptedPkcs8(raw)) {
            if (keyPass.isNullOrEmpty()) return null
            raw = decryptPkcs8(raw, keyPass) ?: return null
        }
        return try {
            val spec = java.security.spec.PKCS8EncodedKeySpec(raw)
            val alg = detectKeyAlgorithmFromPkcs8(raw)
            val kf = KeyFactory.getInstance(alg)
            kf.generatePrivate(spec)
        } catch (e: Exception) {
            // 兜底：尝试 RSA / EC
            try {
                val kf = KeyFactory.getInstance("RSA")
                kf.generatePrivate(java.security.spec.PKCS8EncodedKeySpec(raw))
            } catch (e2: Exception) {
                try {
                    val kf = KeyFactory.getInstance("EC")
                    kf.generatePrivate(java.security.spec.PKCS8EncodedKeySpec(raw))
                } catch (e3: Exception) {
                    null
                }
            }
        }
    }

    /** 解密 EncryptedPrivateKeyInfo（支持 PBES2: PBKDF2+DESede/RC2/AES，以及 PBEWithMD5And*） */
    private fun decryptPkcs8(encryptedPkcs8: ByteArray, password: String): ByteArray? {
        return try {
            val epki = javax.crypto.EncryptedPrivateKeyInfo(encryptedPkcs8)
            val algName = epki.algName
            val spec = when {
                algName.contains("PBES2", true) -> {
                    val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
                    val pbeSpec = epki.algParameters.getParameterSpec(javax.crypto.spec.PBEParameterSpec::class.java)
                    javax.crypto.spec.PBEKeySpec(password.toCharArray(), pbeSpec.salt, pbeSpec.iterationCount)
                        .let { pks ->
                            val skey = factory.generateSecret(pks)
                            javax.crypto.Cipher.getInstance(algName).let { c ->
                                c.init(javax.crypto.Cipher.DECRYPT_MODE, skey, epki.algParameters)
                                c.doFinal(epki.encryptedData)
                            }
                        }
                }
                else -> {
                    val factory = javax.crypto.SecretKeyFactory.getInstance(algName)
                    val pbeSpec = epki.algParameters?.getParameterSpec(javax.crypto.spec.PBEParameterSpec::class.java)
                    if (pbeSpec != null) {
                        val pks = javax.crypto.spec.PBEKeySpec(password.toCharArray(), pbeSpec.salt, pbeSpec.iterationCount)
                        val skey = factory.generateSecret(pks)
                        val c = javax.crypto.Cipher.getInstance(algName)
                        c.init(javax.crypto.Cipher.DECRYPT_MODE, skey, epki.algParameters)
                        c.doFinal(epki.encryptedData)
                    } else {
                        val pks = javax.crypto.spec.PBEKeySpec(password.toCharArray())
                        val skey = factory.generateSecret(pks)
                        val c = javax.crypto.Cipher.getInstance(algName)
                        c.init(javax.crypto.Cipher.DECRYPT_MODE, skey)
                        c.doFinal(epki.encryptedData)
                    }
                }
            }
            spec as ByteArray
        } catch (e: Exception) {
            null
        }
    }

    /** PEM (-----BEGIN ...-----) 转 DER，兼容纯 DER 输入 */
    private fun pemToDer(data: ByteArray): ByteArray {
        val text = String(data)
        if (!text.contains("BEGIN ")) return data
        val base64Part = text.lines()
            .filter { !it.startsWith("-----") }
            .filter { it.isNotBlank() }
            .joinToString("")
        return try {
            java.util.Base64.getDecoder().decode(base64Part)
        } catch (e: Exception) {
            // 尝试 MIME 解码（容忍换行符）
            java.util.Base64.getMimeDecoder().decode(base64Part)
        }
    }

    // ===== 简单 DER 读取工具（供 JKS / PKCS8 / OID 解析）=====

    private class DerReader(val data: ByteArray, var pos: Int = 0) {
        fun tag(): Int = data[pos].toInt() and 0xFF
        fun read(): Int {
            val t = data[pos++].toInt() and 0xFF
            return t
        }
        fun readSequenceLen(): Int {
            // 当前位置是 SEQUENCE tag，返回序列内容结束位置
            read() // tag
            return readValueEnd()
        }
        fun readValueEnd(): Int {
            val first = read()
            if (first < 0x80) return pos + first
            val n = first and 0x7F
            var len = 0
            for (i in 0 until n) len = (len shl 8) or read()
            return pos + len
        }
        fun readBytes(n: Int): ByteArray {
            val out = data.copyOfRange(pos, pos + n)
            pos += n
            return out
        }
    }

    /** 读取 DER 长度字段（b[pos] 为长度首字节），返回长度值 */
    private fun derLength(b: ByteArray, pos: Int): Int {
        val first = b[pos].toInt() and 0xFF
        if (first < 0x80) return first
        val n = first and 0x7F
        var len = 0
        for (i in 0 until n) len = (len shl 8) or (b[pos + 1 + i].toInt() and 0xFF)
        return len
    }

    /** DER 长度字段占用字节数（含长度首字节） */
    private fun derLengthBytes(b: ByteArray, pos: Int): Int {
        val first = b[pos].toInt() and 0xFF
        return if (first < 0x80) 1 else 1 + (first and 0x7F)
    }

    /** 跳到 value 起始（跳过 tag + length 字段） */
    private fun derValueStart(b: ByteArray, tagPos: Int): Int {
        return tagPos + 1 + derLengthBytes(b, tagPos + 1)
    }

    /** 返回 TLV 的 value 结束位置（下一个元素的 tag 位置） */
    private fun derValueEnd(b: ByteArray, tagPos: Int): Int {
        return derValueStart(b, tagPos) + derLength(b, tagPos + 1)
    }

    /** 解析 OID（oidTagPos 指向 0x06 tag 位置） */
    private fun derOid(b: ByteArray, oidTagPos: Int): String? {
        if (b[oidTagPos] != 0x06.toByte()) return null
        val contentStart = derValueStart(b, oidTagPos)
        val len = derLength(b, oidTagPos + 1)
        val bytes = b.copyOfRange(contentStart, contentStart + len)
        return decodeOidBytes(bytes)
    }

    private fun decodeOidBytes(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val parts = mutableListOf<Long>()
        val b0 = bytes[0].toLong() and 0x7F
        val arc0: Long
        val arc1: Long
        if (b0 >= 80) {
            // 首组多字节（极罕见），按 2.x 处理
            var v = 0L
            var byteIdx = 0
            while (byteIdx < bytes.size) {
                val b = bytes[byteIdx].toLong() and 0xFF
                v = (v shl 7) or (b and 0x7F)
                byteIdx++
                if (b and 0x80 == 0.toLong()) break
            }
            arc0 = 2
            arc1 = v - 80
        } else {
            arc0 = b0 / 40
            arc1 = b0 % 40
        }
        parts.add(arc0)
        parts.add(arc1)
        var value = 0L
        for (i in 1 until bytes.size) {
            val b = bytes[i].toLong() and 0xFF
            value = (value shl 7) or (b and 0x7F)
            if (b and 0x80 == 0.toLong()) {
                parts.add(value)
                value = 0
            }
        }
        return parts.joinToString(".")
    }

    /** 读取 PKCS8 内层算法 OID：跳过 version(INTEGER,可选) 后找第一个 SEQUENCE 的 OID */
    private fun readPkcs8AlgOid(raw: ByteArray): String? {
        return try {
            var p = derValueStart(raw, 0)
            val outerEnd = derValueEnd(raw, 0)
            // 跳过 INTEGER version（可选）
            while (p < outerEnd) {
                val tag = raw[p].toInt() and 0xFF
                if (tag == 0x30) {
                    val seqContent = derValueStart(raw, p)
                    return derOid(raw, seqContent)
                }
                p = derValueEnd(raw, p)
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun isEncryptedPkcs8(raw: ByteArray): Boolean {
        if (raw.size < 4 || raw[0] != 0x30.toByte()) return false
        return try {
            val oid = readPkcs8AlgOid(raw)
            oid != null && oid != "1.2.840.113549.1.1.1" && oid != "1.2.840.10045.2.1"
        } catch (e: Exception) {
            false
        }
    }

    private fun detectKeyAlgorithmFromPkcs8(raw: ByteArray): String {
        val oid = readPkcs8AlgOid(raw)
        return if (oid == "1.2.840.10045.2.1") "EC" else "RSA"
    }

    // ===== JKS 格式解析器 =====

    private class JksParser(val data: ByteArray) {
        private var p = 0

        fun parseAllKeys(pass: CharArray): List<SignatureKey> {
            val magic = readInt()
            val version = readInt()
            if (magic != 0xFEEDFEED.toInt()) throw IllegalArgumentException("bad magic")
            val count = readInt()
            val result = mutableListOf<SignatureKey>()
            for (i in 0 until count) {
                val tag = readInt()
                val alias = readUTF()
                readLong() // timestamp
                if (tag == 1) { // PrivateKeyEntry
                    val keyLen = readInt()
                    val protectedKey = readBytes(keyLen)
                    val numCerts = readInt()
                    val certs = mutableListOf<X509Certificate>()
                    if (version == 2) {
                        for (j in 0 until numCerts) {
                            val certType = readUTF()
                            val certLen = readInt()
                            val certDer = readBytes(certLen)
                            try {
                                val cf = java.security.cert.CertificateFactory.getInstance(certType)
                                certs.add(cf.generateCertificate(java.io.ByteArrayInputStream(certDer)) as X509Certificate)
                            } catch (e: Exception) { /* skip bad cert */ }
                        }
                    } else {
                        // v1: 无 certType，直接 certificate
                        for (j in 0 until numCerts) {
                            val certLen = readInt()
                            val certDer = readBytes(certLen)
                            try {
                                val cf = java.security.cert.CertificateFactory.getInstance("X.509")
                                certs.add(cf.generateCertificate(java.io.ByteArrayInputStream(certDer)) as X509Certificate)
                            } catch (e: Exception) { /* skip */ }
                        }
                    }
                    if (certs.isNotEmpty()) {
                        val pkcs8 = recoverJksKey(protectedKey, pass)
                        if (pkcs8 != null) {
                            val alg = if (detectKeyAlgorithmFromPkcs8(pkcs8) == "EC") "EC" else "RSA"
                            try {
                                val key = KeyFactory.getInstance(alg)
                                    .generatePrivate(java.security.spec.PKCS8EncodedKeySpec(pkcs8))
                                result.add(SignatureKey(alias, key, certs[0]))
                            } catch (e: Exception) {
                                try {
                                    val key = KeyFactory.getInstance("RSA")
                                        .generatePrivate(java.security.spec.PKCS8EncodedKeySpec(pkcs8))
                                    result.add(SignatureKey(alias, key, certs[0]))
                                } catch (e2: Exception) { /* skip */ }
                            }
                        }
                    }
                } else if (tag == 2) { // TrustedCertEntry
                    if (version == 2) {
                        val certType = readUTF()
                        val certLen = readInt()
                        readBytes(certLen)
                    } else {
                        val certLen = readInt()
                        readBytes(certLen)
                    }
                }
            }
            return result
        }

        private fun readInt(): Int {
            var v = 0
            for (i in 0 until 4) v = (v shl 8) or (readByte().toInt() and 0xFF)
            return v
        }

        private fun readLong(): Long {
            var v = 0L
            for (i in 0 until 8) v = (v shl 8) or (readByte().toLong() and 0xFF)
            return v
        }

        private fun readUTF(): String {
            val len = (readByte().toInt() and 0xFF shl 8) or (readByte().toInt() and 0xFF)
            val bytes = readBytes(len)
            return decodeModifiedUtf8(bytes)
        }

        private fun readByte(): Byte = data[p++]

        private fun readBytes(n: Int): ByteArray {
            val out = data.copyOfRange(p, p + n)
            p += n
            return out
        }

        private fun decodeModifiedUtf8(bytes: ByteArray): String {
            // DataOutputStream.writeUTF 的 modified UTF-8 → UTF-16
            val sb = StringBuilder()
            var i = 0
            while (i < bytes.size) {
                val b = bytes[i].toInt() and 0xFF
                if (b and 0x80 == 0) {
                    sb.append(b.toChar())
                    i++
                } else if (b and 0xE0 == 0xC0) {
                    val b2 = bytes[i + 1].toInt() and 0xFF
                    sb.append(((b and 0x1F) shl 6 or (b2 and 0x3F)).toChar())
                    i += 2
                } else {
                    // 3 字节（含 surrogate 对）
                    val b2 = bytes[i + 1].toInt() and 0xFF
                    val b3 = bytes[i + 2].toInt() and 0xFF
                    sb.append(((b and 0x0F) shl 12 or ((b2 and 0x3F) shl 6) or (b3 and 0x3F)).toChar())
                    i += 3
                }
            }
            return sb.toString()
        }
    }

    /**
     * 复刻 sun.security.provider.KeyProtector.recover：
     * JKS 私钥保护 = SHA1(password-utf16be || salt) 迭代 XOR 明文，末尾 SHA1(password||plain) 校验。
     * protectedKey 为 EncryptedPrivateKeyInfo 编码（OID JAVASOFT_JDKKeyProtector）。
     */
    private fun recoverJksKey(protectedKey: ByteArray, password: CharArray): ByteArray? {
        return try {
            val epki = javax.crypto.EncryptedPrivateKeyInfo(protectedKey)
            val enc = epki.encryptedData
            // 密码 UTF-16BE（JavaKeyStore.convertPassword）
            val pw = java.io.ByteArrayOutputStream()
            for (ch in password) {
                pw.write((ch.toInt() shr 8 and 0xFF))
                pw.write(ch.toInt() and 0xFF)
            }
            val pwBytes = pw.toByteArray()

            val salt = enc.copyOfRange(0, SALT_LEN)
            val encrKeyLen = enc.size - SALT_LEN - DIGEST_LEN
            if (encrKeyLen <= 0) return null
            val numRounds = (encrKeyLen + DIGEST_LEN - 1) / DIGEST_LEN

            val md = MessageDigest.getInstance("SHA1")
            var digest = salt
            val xorKey = ByteArray(encrKeyLen)
            var xo = 0
            for (i in 0 until numRounds) {
                md.update(pwBytes)
                md.update(digest)
                digest = md.digest()
                md.reset()
                val n = minOf(DIGEST_LEN, encrKeyLen - xo)
                System.arraycopy(digest, 0, xorKey, xo, n)
                xo += n
            }

            val plain = ByteArray(encrKeyLen)
            for (i in 0 until encrKeyLen) {
                (enc[i + SALT_LEN].toInt() xor xorKey[i].toInt()).also { plain[i] = it.toByte() }
            }

            md.update(pwBytes)
            md.update(plain)
            val check = md.digest()
            for (i in 0 until DIGEST_LEN) {
                if (check[i] != enc[SALT_LEN + encrKeyLen + i]) {
                    return null // 密码错误或指纹不符
                }
            }
            plain
        } catch (e: Exception) {
            null
        }
    }

    private const val DIGEST_LEN = 20
    private const val SALT_LEN = 20

    // ===== 格式转换 =====

    /** 从 keystore 导出 pk8（DER PKCS8）与 pem（X509 证书） */
    fun exportPk8Pem(
        keystoreFile: File,
        storePass: String,
        aliasHint: String?,
        pk8Out: File,
        pemOut: File,
        onError: (String) -> Unit
    ): Boolean {
        return try {
            val key = loadSignatureKey(keystoreFile, storePass, aliasHint)
                ?: run {
                    onError("无法从 keystore 读取密钥（检查密码/别名）")
                    return false
                }
            pk8Out.writeBytes(key.privateKey.encoded)
            pemOut.writeText(pemEncode(key.certificate.encoded, "CERTIFICATE"))
            true
        } catch (e: Exception) {
            onError(e.message ?: e.javaClass.simpleName)
            false
        }
    }

    /**
     * 从任意来源（jks/jksjk 纯解析 / p12 / pk8pem）生成 PKCS12 keystore（转换格式用）。
     * 便于把用户的原 JKS 转为 Android 原生可读的 p12。
     */
    fun convertToP12(
        keystoreFile: File,
        storePass: String,
        aliasHint: String?,
        p12Out: File,
        newStorePass: String = "",
        newKeyPass: String = "",
        srcKeyPass: String? = null,
        onError: (String) -> Unit
    ): Boolean {
        return convertKeystoreFormat(
            keystoreFile, storePass, aliasHint, p12Out,
            "PKCS12", newStorePass, newKeyPass, srcKeyPass, onError
        )
    }

    /**
     * 通用 keystore 格式转换：jks/p12/pfx/bks 输入 → PKCS12 (.p12/.pfx) 或 BKS (.bks) 输出。
     * 输出容器用 JSSE/BouncyCastle KeyStore API；仅导出单个 key entry（主别名）。
     */
    fun convertKeystoreFormat(
        keystoreFile: File,
        storePass: String,
        aliasHint: String?,
        outFile: File,
        targetType: String,
        newStorePass: String = "",
        newKeyPass: String = "",
        srcKeyPass: String? = null,
        onError: (String) -> Unit
    ): Boolean {
        return try {
            val key = loadSignatureKey(keystoreFile, storePass, aliasHint, srcKeyPass)
                ?: run {
                    onError("无法读取密钥（检查密码/别名）")
                    return false
                }
            val outType = when (targetType.uppercase()) {
                "BKS", ".bks" -> "BKS"
                else -> "PKCS12"
            }
            if (outType == "BKS" && !bcProviderAvailable()) {
                onError("BKS 需要 BouncyCastle 支持（当前不可用）")
                return false
            }
            val storePw = newStorePass.ifBlank { storePass }
            val keyPw = newKeyPass.ifBlank { storePass }
            val ks = KeyStore.getInstance(outType)
            ks.load(null, null)
            ks.setKeyEntry(
                key.alias.ifBlank { "adh" },
                key.privateKey,
                keyPw.toCharArray(),
                arrayOf(key.certificate)
            )
            outFile.parentFile?.mkdirs()
            FileOutputStream(outFile).use { ks.store(it, storePw.toCharArray()) }
            if (!outFile.exists() || outFile.length() <= 0) {
                onError("转换生成的 ${outType} 文件无效")
                return false
            }
            true
        } catch (e: Exception) {
            onError(e.message ?: e.javaClass.simpleName)
            false
        }
    }

    /** 将 pk8/key/pem 文件解析为 (pk8File, pemFile) 配对 */
    private fun resolvePk8PemFiles(file: File): Pair<File, File>? {
        val name = file.name.lowercase()
        return when {
            name.endsWith(".pk8") || name.endsWith(".key") -> {
                val base = file.name.removeSuffix(name.substringAfterLast('.')).trimEnd('.')
                val pemFile = File(file.parentFile, "$base.x509.pem").takeIf { it.exists() }
                    ?: File(file.parentFile, "$base.pem").takeIf { it.exists() }
                    ?: return null
                Pair(file, pemFile)
            }
            else -> {
                val base = file.name.removeSuffix(name.substringAfterLast('.')).trimEnd('.')
                val pk8File = File(file.parentFile, "$base.pk8").takeIf { it.exists() }
                    ?: File(file.parentFile, "$base.key").takeIf { it.exists() }
                    ?: return null
                Pair(pk8File, file)
            }
        }
    }

    /** 通用 KeyStore API 加载签名密钥（PKCS12 / BKS 等 provider 支持的格式） */
    private fun loadFromKeyStoreFile(
        keystoreFile: File,
        type: String,
        storePass: String,
        aliasHint: String?,
        keyPass: String? = null
    ): SignatureKey? {
        if (!bcProviderAvailable()) return null
        return try {
            val ks = KeyStore.getInstance(type)
            FileInputStream(keystoreFile).use { ks.load(it, storePass.toCharArray()) }
            val alias = aliasHint?.takeIf { it.isNotBlank() && ks.containsAlias(it) }
                ?: ks.aliases().toList().firstOrNull { ks.isKeyEntry(it) }
                ?: return null
            val pass = (keyPass ?: storePass).toCharArray()
            val key = ks.getKey(alias, pass) as? PrivateKey ?: return null
            val cert = ks.getCertificate(alias) as? X509Certificate ?: return null
            SignatureKey(alias, key, cert)
        } catch (e: Exception) {
            null
        }
    }

    /** 通用加载签名密钥：自动识别 jks / p12 / bks / pk8pem */
    fun loadSignatureKey(
        keystoreFile: File,
        storePass: String,
        aliasHint: String?,
        keyPass: String? = null
    ): SignatureKey? {
        val name = keystoreFile.name.lowercase()
        return when {
            name.endsWith(".pk8") || name.endsWith(".pem") || name.endsWith(".key") -> {
                val pair = resolvePk8PemFiles(keystoreFile) ?: return null
                loadKeysFromPk8Pem(pair.first, pair.second, keyPass ?: storePass)
            }
            name.endsWith(".p12") || name.endsWith(".pfx") -> {
                loadFromKeyStoreFile(keystoreFile, "PKCS12", storePass, aliasHint, keyPass)
            }
            name.endsWith(".jks") || name.endsWith(".keystore") || name.endsWith(".ks") -> {
                loadKeysFromJks(keystoreFile, storePass, aliasHint)
            }
            name.endsWith(".bks") -> {
                loadFromKeyStoreFile(keystoreFile, "BKS", storePass, aliasHint, keyPass)
            }
            else -> {
                // 自动探测：jks 纯解析 > p12 > bks
                loadKeysFromJks(keystoreFile, storePass, aliasHint)
                    ?: loadFromKeyStoreFile(keystoreFile, "PKCS12", storePass, aliasHint, keyPass)
                    ?: loadFromKeyStoreFile(keystoreFile, "BKS", storePass, aliasHint, keyPass)
            }
        }
    }

    /** PEM 编码辅助 */
    fun pemEncode(der: ByteArray, type: String): String {
        val b64 = java.util.Base64.getEncoder().encodeToString(der)
        val sb = StringBuilder("-----BEGIN $type-----\n")
        for (i in b64.indices step 64) {
            sb.append(b64.substring(i, minOf(i + 64, b64.length))).append('\n')
        }
        sb.append("-----END $type-----\n")
        return sb.toString()
    }
}
