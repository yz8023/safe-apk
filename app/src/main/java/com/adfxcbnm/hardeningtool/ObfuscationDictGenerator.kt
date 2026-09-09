package com.adfxcbnm.hardeningtool

import java.util.LinkedHashMap
import java.util.Random

/**
 * 混淆字典生成器 v2 — 21 个字符分组 + 自定义 Unicode + 自定义符号 + 复杂度控制
 *
 * 从 ArkProtector-v2.8.1 移植，与 ArkProtector 的 ObfuscationDictGenerator 保持一致。
 * 分组：
 *  1.  阿拉伯组合辅音     U+0750~U+077F  连写堆叠
 *  2.  埃塞俄比亚吉兹字母  U+1200~U+126F  圆角相似
 *  3.  韩文谚文音节        U+AC00~U+D7AF  方块冷僻
 *  4.  藏文堆叠符          U+0F00~U+0FBF  多层堆叠
 *  5.  缅甸文连体          U+1000~U+109F  粘连连写
 *  6.  Zalgo 变音堆叠符    U+0300~U+036F  万能叠加
 *  7.  泰文连体草书        U+0E00~U+0E7F  粘连变形
 *  8.  零宽标记堆叠        U+20D0~U+20FF  环绕包裹
 *  9.  蒙古文传统连体      U+1800~U+18AF  首尾变形
 * 10.  格鲁吉亚小语种      U+10A0~U+10FF  冷门文字
 * 11.  叙利亚文连体        U+0700~U+074F  右向左连写
 * 12.  马尔代夫塔纳文      U+0780~U+07BF  海岛冷文字
 * 13.  高棉文堆叠符        U+1780~U+17FF  上下附标
 * 14.  加拿大原住民音节文  U+1400~U+167F  几何符号
 * 15.  空白符/变体选择符   U+E0100~U+E01E7
 * 16.  包围式文字          U+1F200~U+1F2FF
 * 17.  麻将牌              U+1F000~U+1F02B
 * 18.  多米诺骨牌          U+1F030~U+1F093
 * 19.  扑克牌              U+1F0A0~U+1F0DF
 * 20.  太玄经符号          U+1D300~U+1D356
 * 21.  埃及象形文字        U+14400~U+14646
 */
class ObfuscationDictGenerator(seed: Long = System.currentTimeMillis()) {

    companion object {
        // 堆叠/组合字符（用于中/高复杂度）
        private const val STACKING_ABOVE = "\u0300\u0301\u0302\u0303\u0304\u0305\u0306\u0307\u0308\u0309\u030A\u030B\u030C\u030D\u030E\u030F\u0310\u0311"
        private const val STACKING_BELOW = "\u0316\u0317\u0318\u0319\u031A\u031B\u031C\u031D\u031E\u031F\u0320\u0321\u0322\u0323\u0324\u0325\u0326\u0327\u0328\u0329\u032A\u032B\u032C\u032D\u032E\u032F\u0330"
        private const val STACKING_ZALGO = "\u0334\u0335\u0336\u0337\u0338\u0339\u033A\u033B\u033C\u033D\u033E\u033F\u0340\u0341\u0342\u0343\u0344\u0345\u0346\u0347\u0348\u0349\u034A\u034B\u034C\u034D\u034E\u034F"
        private const val STACKING_TIBETAN = "\u0F71\u0F72\u0F74\u0F7A\u0F7B\u0F7C\u0F7D\u0F80\u0F7E\u0F82\u0F83\u0F84\u0F86\u0F87"
        private const val STACKING_ENCLOSING = "\u20D0\u20D1\u20D2\u20D3\u20D4\u20D5\u20D6\u20D7\u20D8\u20D9\u20DA\u20DB\u20DC\u20DD\u20DE\u20DF\u20E0\u20E1\u20E2\u20E3\u20E4\u20E5\u20E6\u20E7\u20E8\u20E9\u20EA\u20EB\u20EC\u20ED\u20EE\u20EF"

        // ===== 分组 ID =====
        const val GROUP_ARABIC_EXT = 1
        const val GROUP_ETHIOPIC = 2
        const val GROUP_HANGUL = 3
        const val GROUP_TIBETAN = 4
        const val GROUP_MYANMAR = 5
        const val GROUP_ZALGO = 6
        const val GROUP_THAI = 7
        const val GROUP_COMBINING_SYM = 8
        const val GROUP_MONGOLIAN = 9
        const val GROUP_GEORGIAN = 10
        const val GROUP_SYRIAC = 11
        const val GROUP_THAANA = 12
        const val GROUP_KHMER = 13
        const val GROUP_CANADIAN = 14
        const val GROUP_VARIATION_SELECTORS = 15
        const val GROUP_ENCLOSED_IDEOGRAPH = 16
        const val GROUP_MAHJONG_TILES = 17
        const val GROUP_DOMINO_TILES = 18
        const val GROUP_PLAYING_CARDS = 19
        const val GROUP_TAI_XUAN_JING = 20
        const val GROUP_EGYPTIAN_HIEROGLYPHS = 21

        // 复杂度
        const val COMPLEXITY_LOW = 0
        const val COMPLEXITY_MEDIUM = 1
        const val COMPLEXITY_HIGH = 2

        private val GROUP_CHARS: LinkedHashMap<Int, String> = LinkedHashMap()
        private val GROUP_NAMES: LinkedHashMap<Int, String> = LinkedHashMap()

        init {
            GROUP_CHARS[GROUP_ARABIC_EXT] = rangeToString(0x0750, 0x077F)
            GROUP_NAMES[GROUP_ARABIC_EXT] = "阿拉伯组合辅音"
            GROUP_CHARS[GROUP_ETHIOPIC] = rangeToString(0x1200, 0x126F)
            GROUP_NAMES[GROUP_ETHIOPIC] = "埃塞俄比亚吉兹"
            GROUP_CHARS[GROUP_HANGUL] = rangeToString(0xAC82, 0xACDF)
            GROUP_NAMES[GROUP_HANGUL] = "韩文谚文音节"
            GROUP_CHARS[GROUP_TIBETAN] = rangeToString(0x0F40, 0x0F6C) + rangeToString(0x0F71, 0x0F87)
            GROUP_NAMES[GROUP_TIBETAN] = "藏文堆叠符"
            GROUP_CHARS[GROUP_MYANMAR] = rangeToString(0x1000, 0x1021) + rangeToString(0x1023, 0x1027) +
                "\u1029\u102A\u102B\u102C\u102D\u102E\u102F\u1030\u1031\u1032\u1036\u1037\u1038\u1039\u103A\u103B\u103C\u103D\u103E\u103F\u1040\u1041\u1042\u1043\u1044\u1045\u1046\u1047\u1048\u1049"
            GROUP_NAMES[GROUP_MYANMAR] = "缅甸文连体"
            GROUP_CHARS[GROUP_ZALGO] = rangeToString(0x0300, 0x036F)
            GROUP_NAMES[GROUP_ZALGO] = "Zalgo变音堆叠"
            GROUP_CHARS[GROUP_THAI] = rangeToString(0x0E01, 0x0E3A) +
                "\u0E3F\u0E40\u0E41\u0E42\u0E43\u0E44\u0E45\u0E46\u0E47\u0E48\u0E49\u0E4A\u0E4B\u0E4C\u0E4D\u0E4E\u0E4F\u0E50\u0E51\u0E52\u0E53\u0E54\u0E55\u0E56\u0E57"
            GROUP_NAMES[GROUP_THAI] = "泰文连体草书"
            GROUP_CHARS[GROUP_COMBINING_SYM] = rangeToString(0x20D0, 0x20FF)
            GROUP_NAMES[GROUP_COMBINING_SYM] = "零宽标记堆叠"
            GROUP_CHARS[GROUP_MONGOLIAN] = rangeToString(0x1800, 0x18AF)
            GROUP_NAMES[GROUP_MONGOLIAN] = "蒙古文传统连体"
            GROUP_CHARS[GROUP_GEORGIAN] = rangeToString(0x10A0, 0x10FF)
            GROUP_NAMES[GROUP_GEORGIAN] = "格鲁吉亚小语种"
            GROUP_CHARS[GROUP_SYRIAC] = rangeToString(0x0700, 0x074F)
            GROUP_NAMES[GROUP_SYRIAC] = "叙利亚文连体"
            GROUP_CHARS[GROUP_THAANA] = rangeToString(0x0780, 0x07BF)
            GROUP_NAMES[GROUP_THAANA] = "马尔代夫塔纳文"
            GROUP_CHARS[GROUP_KHMER] = rangeToString(0x1780, 0x17FF)
            GROUP_NAMES[GROUP_KHMER] = "高棉文堆叠符"
            GROUP_CHARS[GROUP_CANADIAN] = rangeToString(0x1400, 0x167F)
            GROUP_NAMES[GROUP_CANADIAN] = "加拿大原住民音节"
            GROUP_CHARS[GROUP_VARIATION_SELECTORS] = rangeToString(0xE0100, 0xE01E7)
            GROUP_NAMES[GROUP_VARIATION_SELECTORS] = "空白符/变体选择符"
            GROUP_CHARS[GROUP_ENCLOSED_IDEOGRAPH] = rangeToString(0x1F200, 0x1F2FF)
            GROUP_NAMES[GROUP_ENCLOSED_IDEOGRAPH] = "包围式文字"
            GROUP_CHARS[GROUP_MAHJONG_TILES] = rangeToString(0x1F000, 0x1F02B)
            GROUP_NAMES[GROUP_MAHJONG_TILES] = "麻将牌"
            GROUP_CHARS[GROUP_DOMINO_TILES] = rangeToString(0x1F030, 0x1F093)
            GROUP_NAMES[GROUP_DOMINO_TILES] = "多米诺骨牌"
            GROUP_CHARS[GROUP_PLAYING_CARDS] = rangeToString(0x1F0A0, 0x1F0DF)
            GROUP_NAMES[GROUP_PLAYING_CARDS] = "扑克牌"
            GROUP_CHARS[GROUP_TAI_XUAN_JING] = rangeToString(0x1D300, 0x1D356)
            GROUP_NAMES[GROUP_TAI_XUAN_JING] = "太玄经符号"
            GROUP_CHARS[GROUP_EGYPTIAN_HIEROGLYPHS] = rangeToString(0x14400, 0x14646)
            GROUP_NAMES[GROUP_EGYPTIAN_HIEROGLYPHS] = "埃及象形文字"
        }

        private fun rangeToString(start: Int, end: Int): String {
            val sb = StringBuilder()
            for (i in start..end) sb.appendCodePoint(i)
            return sb.toString()
        }

        fun getGroupName(groupId: Int): String = GROUP_NAMES[groupId] ?: "未知分组"

        fun getAllGroups(): Map<Int, String> = LinkedHashMap(GROUP_NAMES)

        fun getAllGroupIds(): IntArray = intArrayOf(
            GROUP_ARABIC_EXT, GROUP_ETHIOPIC, GROUP_HANGUL, GROUP_TIBETAN,
            GROUP_MYANMAR, GROUP_ZALGO, GROUP_THAI, GROUP_COMBINING_SYM,
            GROUP_MONGOLIAN, GROUP_GEORGIAN, GROUP_SYRIAC, GROUP_THAANA,
            GROUP_KHMER, GROUP_CANADIAN, GROUP_VARIATION_SELECTORS,
            GROUP_ENCLOSED_IDEOGRAPH, GROUP_MAHJONG_TILES, GROUP_DOMINO_TILES,
            GROUP_PLAYING_CARDS, GROUP_TAI_XUAN_JING, GROUP_EGYPTIAN_HIEROGLYPHS
        )

        fun getComplexityName(complexity: Int): String = when (complexity) {
            COMPLEXITY_LOW -> "低（仅本体字符）"
            COMPLEXITY_MEDIUM -> "中（+单层堆叠）"
            COMPLEXITY_HIGH -> "高（+多层堆叠）"
            else -> "未知"
        }

        fun getGroupPreview(groupId: Int): String {
            val chars = GROUP_CHARS[groupId] ?: return ""
            if (chars.isEmpty()) return ""
            val end = minOf(20, chars.length)
            return chars.substring(0, end)
        }
    }

    private val random = Random(seed)
    private var enabledGroups: MutableSet<Int> = mutableSetOf()
    private var complexity = COMPLEXITY_LOW
    private var minLen = 4
    private var maxLen = 12
    private var customUnicodeChars = ""
    private var customSymbolChars = ""
    private var useCustomUnicode = false
    private var useCustomSymbols = false

    fun setEnabledGroups(groups: Set<Int>) {
        this.enabledGroups = groups.toMutableSet()
    }

    fun setComplexity(complexity: Int) {
        this.complexity = complexity
    }

    fun setLengthRange(minLen: Int, maxLen: Int) {
        this.minLen = maxOf(1, minLen)
        this.maxLen = maxOf(this.minLen, maxLen)
    }

    fun setCustomUnicodeRange(startCodePoint: Int, endCodePoint: Int) {
        if (startCodePoint > 0 && endCodePoint >= startCodePoint) {
            this.customUnicodeChars = rangeToString(startCodePoint, endCodePoint)
            this.useCustomUnicode = true
        }
    }

    fun setCustomUnicodeChars(chars: String) {
        this.customUnicodeChars = chars
        this.useCustomUnicode = this.customUnicodeChars.isNotEmpty()
    }

    fun setCustomSymbolChars(chars: String) {
        this.customSymbolChars = chars
        this.useCustomSymbols = this.customSymbolChars.isNotEmpty()
    }

    private fun buildBaseCharPool(): String {
        val pool = StringBuilder()
        for (groupId in enabledGroups) {
            GROUP_CHARS[groupId]?.let { pool.append(it) }
        }
        if (useCustomUnicode) pool.append(customUnicodeChars)
        if (useCustomSymbols) pool.append(customSymbolChars)
        return pool.toString()
    }

    private fun getStackingChars(): String {
        val sb = StringBuilder()
        sb.append(STACKING_ABOVE)
        sb.append(STACKING_BELOW)
        if (enabledGroups.contains(GROUP_TIBETAN)) sb.append(STACKING_TIBETAN)
        if (enabledGroups.contains(GROUP_COMBINING_SYM)) sb.append(STACKING_ENCLOSING)
        if (enabledGroups.contains(GROUP_ZALGO)) sb.append(STACKING_ZALGO)
        return sb.toString()
    }

    private fun randomCodePoint(s: String): String {
        if (s.isEmpty()) return ""
        val len = s.length
        var idx = random.nextInt(len)
        if (Character.isLowSurrogate(s[idx])) idx--
        if (idx < 0) idx = 0
        val cp = s.codePointAt(idx)
        return String(Character.toChars(cp))
    }

    private fun generateOne(basePool: String, stackingPool: String): String {
        val len = minLen + random.nextInt(maxLen - minLen + 1)
        val sb = StringBuilder()
        for (i in 0 until len) {
            sb.append(randomCodePoint(basePool))
            if (complexity >= COMPLEXITY_MEDIUM && stackingPool.isNotEmpty()) {
                if (random.nextFloat() < 0.4f) {
                    sb.append(stackingPool[random.nextInt(stackingPool.length)])
                }
            }
            if (complexity >= COMPLEXITY_HIGH && stackingPool.isNotEmpty()) {
                val extraLayers = 1 + random.nextInt(3)
                for (l in 0 until extraLayers) {
                    if (random.nextFloat() < 0.5f) {
                        sb.append(stackingPool[random.nextInt(stackingPool.length)])
                    }
                }
            }
        }
        return sb.toString()
    }

    fun generate(count: Int): List<String> {
        var basePool = buildBaseCharPool()
        if (basePool.isEmpty()) {
            basePool = GROUP_CHARS[GROUP_ARABIC_EXT] + GROUP_CHARS[GROUP_ETHIOPIC] + GROUP_CHARS[GROUP_HANGUL]
        }
        val stackingPool = getStackingChars()
        val unique = linkedSetOf<String>()
        var retries = 0
        while (unique.size < count && retries < count * 5) {
            val word = generateOne(basePool, stackingPool)
            val clean = sanitizeForDex(word)
            if (clean.length >= 2) unique.add(clean)
            retries++
        }
        return unique.toList()
    }

    fun generateForClassNames(count: Int): List<String> = generate(count)

    fun generateForFieldNames(count: Int): List<String> = generate(count)

    private fun sanitizeForDex(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            if (cp == ';'.code || cp == '<'.code || cp == '>'.code || cp == '@'.code || cp == '/'.code || cp == '\\'.code) continue
            if (cp < 0x20 && cp != '\t'.code) continue
            sb.appendCodePoint(cp)
        }
        return sb.toString()
    }

    fun generateProguardDict(count: Int): String {
        val words = generate(count)
        val sb = StringBuilder()
        sb.append("# Obfuscation Dictionary\n")
        sb.append("# Generated by ArkProtector v2 (hardeningtool)\n")
        for (w in words) {
            val clean = w.replace("\n", "").replace("\r", "").trim()
            if (clean.isNotEmpty()) sb.append(clean).append("\n")
        }
        return sb.toString()
    }
}