package com.adfxcbnm.hardeningtool

import java.io.File
import java.security.SecureRandom
import java.util.regex.Pattern

object SoNameRandomizer {

    private val NAME_PATTERN = Pattern.compile("lib([0-9a-f]{16})\\.so")
    private val HEX = "0123456789abcdef".toCharArray()
    private val RANDOM = SecureRandom()

    data class Result(val renamed: Map<String, String>, val errors: List<String>)

    private fun newHexName(): String {
        val sb = StringBuilder(16)
        for (i in 0 until 16) sb.append(HEX[RANDOM.nextInt(16)])
        return sb.toString()
    }

    /**
     * 生成黑名单安全的随机名（16位小写hex），与旧名不冲突。
     * 遵循 SoNamePolicy 黑名单约束，避免命中已知壳特征名。
     */
    private fun newSafeName(): String {
        var candidate = newHexName()
        while (SoNamePolicy.isKnownProtectorName(candidate)) candidate = newHexName()
        return candidate
    }

    /**
     * 兼容入口：保持旧调用契约，委托给 randomizeSafe（含黑名单约束）。
     */
    fun randomize(shellFilesDir: File): Result = randomizeSafe(shellFilesDir)

    /**
     * 会话级随机化：为壳 SO 旧名（lib{16hex}.so）生成新的随机名。
     */
    fun randomizeSafe(shellFilesDir: File): Result {
        val oldNames = linkedSetOf<String>()
        val dexFile = File(shellFilesDir, "dex/classes.dex")
        var dexBytes: ByteArray? = null
        if (dexFile.isFile) {
            dexBytes = dexFile.readBytes()
            oldNames += scanNames(dexBytes)
        }
        val libsRoot = File(shellFilesDir, "libs")
        val abiDirs = libsRoot.listFiles()?.filter { it.isDirectory } ?: emptyList()
        for (abi in abiDirs) {
            for (so in abi.listFiles() ?: emptyArray()) {
                val m = NAME_PATTERN.matcher(so.name)
                if (m.matches()) oldNames.add(m.group(1))
            }
        }
        if (oldNames.isEmpty()) return Result(emptyMap(), emptyList())

        val mapping = linkedMapOf<String, String>()
        for (old in oldNames) {
            var fresh = newSafeName()
            while (oldNames.contains(fresh) || mapping.containsValue(fresh)) fresh = newSafeName()
            mapping[old] = fresh
        }

        val errors = mutableListOf<String>()
        dexBytes?.let { bytes ->
            try {
                dexFile.writeBytes(replaceHex(bytes, mapping))
            } catch (e: Exception) {
                errors.add("壳dex写回失败: ${e.message}")
            }
        }
        for (abi in abiDirs) {
            for (so in abi.listFiles()?.toList() ?: emptyList()) {
                val m = NAME_PATTERN.matcher(so.name)
                if (!m.matches()) continue
                val oldHex = m.group(1)
                val newHex = mapping[oldHex] ?: continue
                try {
                    so.writeBytes(replaceHex(so.readBytes(), mapOf(oldHex to newHex)))
                } catch (e: Exception) {
                    errors.add("${so.name} 内容替换失败: ${e.message}")
                    continue
                }
                val target = File(so.parentFile, "lib$newHex.so")
                if (!so.renameTo(target)) errors.add("${so.name} 改名失败")
            }
        }
        return Result(mapping, errors)
    }

    private fun scanNames(bytes: ByteArray): Set<String> {
        val found = linkedSetOf<String>()
        val m = NAME_PATTERN.matcher(String(bytes, Charsets.ISO_8859_1))
        while (m.find()) found.add(m.group(1))
        return found
    }

    private fun replaceHex(bytes: ByteArray, mapping: Map<String, String>): ByteArray {
        var text = String(bytes, Charsets.ISO_8859_1)
        for ((old, new) in mapping) text = text.replace(old, new)
        return text.toByteArray(Charsets.ISO_8859_1)
    }
}