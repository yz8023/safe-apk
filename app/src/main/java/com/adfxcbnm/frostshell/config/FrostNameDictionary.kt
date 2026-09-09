package com.adfxcbnm.frostshell.config

import java.util.concurrent.atomic.AtomicInteger

/**
 * 混淆字典词条池，供类名/字段名重命名取词（对应 ArkProtector DexObfuscator 的 classDict/fieldDict）。
 *
 * 用法：
 *   FrostNameDictionary.setWords(words)
 *   FrostNameDictionary.genClassIdentifier(used)   -> 形如 "Lw1/w2;" 的两段拼接
 *   FrostNameDictionary.genFieldIdentifier(used)   -> 单段词条化字段名
 *
 * 词条为空时退回随机字母命名（调用方保留原逻辑兜底）。
 */
object FrostNameDictionary {

    @Volatile
    private var words: List<String> = emptyList()

    private val cursor = AtomicInteger(0)

    /** 词条进入命名池前净化：剔除 dex 类名/字段名非法字符，保留 Unicode。 */
    private fun sanitize(word: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < word.length) {
            val cp = word.codePointAt(i)
            i += Character.charCount(cp)
            when (cp) {
                ';'.code, '<'.code, '>'.code, '@'.code, '/'.code, '\\'.code,
                '.'.code, '['.code, ']'.code, '(' .code, ')'.code, ':'.code,
                ' '.code, '\t'.code, '\n'.code, '\r'.code -> continue
            }
            if (cp < 0x20) continue
            sb.appendCodePoint(cp)
        }
        return sb.toString()
    }

    fun setWords(newWords: List<String>) {
        synchronized(cursor) {
            words = newWords.map { sanitize(it) }
                .filter { it.isNotBlank() }
                .distinct()
                .take(50000)
            cursor.set(0)
        }
    }

    fun isEmpty(): Boolean {
        synchronized(cursor) { return words.isEmpty() }
    }

    fun size(): Int {
        synchronized(cursor) { return words.size }
    }

    private fun nextWord(): String? {
        synchronized(cursor) {
            if (words.isEmpty()) return null
            val idx = cursor.getAndIncrement()
            return words[idx % words.size]
        }
    }

    /** 生成类名标识符（格式 "Lw1/w2;"，与 ArkProtector 两段拼接一致）。 */
    fun genClassIdentifier(used: MutableSet<String>): String? {
        synchronized(cursor) {
            if (words.size < 2) return null
            var attempt = 0
            while (attempt < 16) {
                val w1 = nextWord() ?: return null
                val w2 = nextWord() ?: return null
                val name = "L$w1/$w2;"
                if (!used.contains(name)) {
                    used.add(name)
                    return name
                }
                attempt++
            }
            return null
        }
    }

    /** 生成字段名标识符（单段词条，避开与类名相同的 "L/;" 包络）。 */
    fun genFieldIdentifier(used: MutableSet<String>): String? {
        synchronized(cursor) {
            if (words.isEmpty()) return null
            var attempt = 0
            while (attempt < 24) {
                val w = nextWord() ?: return null
                if (!used.contains(w)) {
                    used.add(w)
                    return w
                }
                attempt++
            }
            return null
        }
    }
}