package com.adfxcbnm.hardeningtool

import java.security.SecureRandom
import java.util.regex.Pattern

/**
 * SO 命名策略（对齐 ArkProtector 的 getValidSoNameFromSettings 语义）：
 * 1. 校验合法 SO 名（去 lib 前缀 / .so 后缀后，仅允许 [A-Za-z0-9_]+）
 * 2. 已知壳特征名黑名单：命中则弃用，避免伪装名反而暴露加固身份
 * 3. 默认回退为随机名，避免硬编码 ArkStub 等固定特征
 *
 * 供 SoNameRandomizer / SoNameDisguiser / MainActivity 自定义名输入共用。
 */
object SoNamePolicy {

    private val NAME_PATTERN = Pattern.compile("^[A-Za-z0-9_]+$")
    private val RANDOM = SecureRandom()
    private const val RANDOM_LENGTH = 12

    /** 已知壳特征名（可能暴露加固身份），必须随机化 */
    val knownProtectorNames: Set<String> = linkedSetOf(
        "ArkStub", "baiduprotect", "libBaiduprotect", "jiagu", "bangcle",
        "SecShell", "tup", "chaosvmp", "ijiami", "aliprotect", "nqshield",
        "dexprotector", "SecShell", "x3g"
    )

    /** 校验去掉前缀/后缀后的 SO 名（lib<name>.so 形式） */
    fun isValidBaseName(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        return NAME_PATTERN.matcher(name.trim()).matches()
    }

    /** 校验完整 SO 文件名（libXxx.so / /libXxx.so / libXxx） */
    fun isValidSoName(soName: String?): Boolean {
        if (soName.isNullOrBlank()) return false
        var name = soName.trim()
        if (name.startsWith("lib")) name = name.removePrefix("lib")
        if (name.startsWith("/lib")) name = name.removePrefix("/lib")
        if (name.endsWith(".so")) name = name.removeSuffix(".so")
        return isValidBaseName(name)
    }

    /** 命中黑名单（对自定义名做去特征拦截） */
    fun isKnownProtectorName(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val normalized = name.trim().removePrefix("lib").removeSuffix(".so")
        return knownProtectorNames.any { it.equals(normalized, ignoreCase = true) }
    }

    /** 生成随机名（不含 lib 前缀 / .so 后缀），规避黑名单 */
    fun generateRandomBaseName(): String {
        var candidate: String
        do {
            candidate = randomAlphaNumeric(RANDOM_LENGTH)
        } while (isKnownProtectorName(candidate))
        return candidate
    }

    /**
     * 解析最终 SO 名：
     * - 用户自定义名合法且非黑名单 → 使用自定义名
     * - 否则 → 随机名
     * 返回 baseName（不含 lib/.so）。
     */
    fun resolveBaseName(customName: String?, preferRandom: Boolean): String {
        if (!preferRandom && isValidBaseName(customName) && !isKnownProtectorName(customName)) {
            return customName!!.trim()
        }
        return generateRandomBaseName()
    }

    /** 组装完整文件名 lib<base>.so */
    fun toFileName(baseName: String): String = "lib$baseName.so"

    private fun randomAlphaNumeric(length: Int): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        val sb = StringBuilder(length)
        for (i in 0 until length) {
            sb.append(alphabet[RANDOM.nextInt(alphabet.length)])
        }
        return sb.toString()
    }
}