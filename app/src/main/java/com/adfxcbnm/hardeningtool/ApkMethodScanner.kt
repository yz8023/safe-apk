package com.adfxcbnm.hardeningtool

import com.android.dex.Dex
import java.io.File
import java.util.zip.ZipFile

/**
 * 罗列 APK 内所有类与方法，供用户按类/方法名搜索并勾选生成抽取规则。
 * 通过 ZipFile 读出 classes*.dex，再用 com.android.dex.Dex 解析类与方法。
 */
object ApkMethodScanner {

    data class MethodEntry(
        val className: String,
        val methodName: String,
        val parameterTypes: String = "",
        val returnType: String = ""
    ) {
        /** 用于 UI 展示：类名.方法名 */
        val display get() = "$className.$methodName"
        /** 全局唯一 key：类名 + 方法名 + 参数签名，避免重载方法重复 */
        val uniqueKey get() = "$className.$methodName$parameterTypes"
        /** 生成精确抽取规则（带参数签名以区分重载） */
        fun toRule() = "$className.$methodName"
        /** 完整方法 descriptor： "(Ljava/lang/String;I)V"，用于 smali `.method` 规则匹配 */
        val descriptor get() = "($parameterTypes)$returnType"
    }

    data class ScanResult(
        val entries: List<MethodEntry>,
        val classCount: Int,
        val methodCount: Int
    )

    fun scan(apkFile: File): ScanResult {
        val entries = ArrayList<MethodEntry>()
        val seen = HashSet<String>()
        val classes = HashSet<String>()
        ZipFile(apkFile).use { zip ->
            val dexFiles = zip.entries().asSequence()
                .filter { e -> !e.isDirectory && e.name.matches(Regex("classes(?:\\.\\d+)?\\.dex$")) }
                .toList()
            for (e in dexFiles) {
                val bytes = zip.getInputStream(e).use { it.readBytes() }
                try {
                    val dex = Dex(bytes)
                    val typeNames = dex.typeNames()
                    val stringIds = dex.strings()
                    val methodIds = dex.methodIds()
                    for (classDef in dex.classDefs()) {
                        val typeIndex = classDef.typeIndex
                        if (typeIndex < 0 || typeIndex >= typeNames.size) continue
                        val className = try { typeNames[typeIndex] } catch (e: Exception) { continue }
                        if (classDef.classDataOffset == 0) continue
                        val classData = try { dex.readClassData(classDef) } catch (e: Exception) { continue }
                        for (method in classData.allMethods()) {
                            val mid = method.methodIndex
                            if (mid < 0 || mid >= methodIds.size) continue
                            val nameIndex = try { methodIds[mid].nameIndex } catch (e: Exception) { continue }
                            if (nameIndex < 0 || nameIndex >= stringIds.size) continue
                            val name = try { stringIds[nameIndex] } catch (e: Exception) { continue }
                            var params = ""
                            var retType = ""
                            try {
                                val protoIdx = methodIds[mid].protoIndex
                                val proto = dex.protoIds()[protoIdx]
                                val paramOff = proto.parametersOffset
                                if (paramOff != 0) {
                                    val typeList = dex.readTypeList(paramOff)
                                    params = typeList.types.joinToString("") { idx ->
                                        if (idx >= 0 && idx < typeNames.size) typeNames[idx.toInt()] else "?"
                                    }
                                }
                                val retIdx = proto.returnTypeIndex
                                retType = if (retIdx >= 0 && retIdx < typeNames.size) typeNames[retIdx] else ""
                            } catch (e: Exception) { }
                            val entry = MethodEntry(className, name, params, retType)
                            if (seen.add(entry.uniqueKey)) {
                                entries.add(entry)
                            }
                        }
                        classes.add(className)
                    }
                } catch (e: Exception) {
                    // 单 dex 失败不影响整体
                }
            }
        }
        return ScanResult(entries.sortedBy { it.display }, classes.size, entries.size)
    }

    fun search(entries: List<MethodEntry>, query: String, regexMode: Boolean): List<MethodEntry> {
        val q = query.trim()
        if (q.isEmpty()) return entries
        return if (regexMode) {
            val re = try { Regex(q, RegexOption.IGNORE_CASE) } catch (e: Exception) { null }
            if (re == null) emptyList()
            else entries.filter { re.matches(it.display) }
        } else {
            val lower = q.lowercase()
            entries.filter { it.display.lowercase().contains(lower) }
        }
    }

    /** 单条规则是否命中某方法。语义与 FrostProtectRules 一致。 */
    fun ruleMatches(rule: String, className: String, methodName: String, methodDescriptor: String = ""): Boolean {
        val r = rule.trim()
        if (r.isEmpty()) return false
        // smali `.method` 签名格式（含可选类前缀 `Lxxx;.method ...`）
        if (Regex("\\.method($|\\s)").containsMatchIn(r)) {
            return methodRuleMatches(r, className, methodName, methodDescriptor)
        }
        if (r.startsWith("regex:")) {
            val patternText = r.substring("regex:".length).trim()
            if (patternText.isEmpty()) return false
            return try { Regex(patternText, RegexOption.IGNORE_CASE).matches("$className.$methodName") } catch (e: Exception) { false }
        }
        val semiIndex = r.indexOf(';')
        if (semiIndex < 0) {
            // 纯方法名关键词/正则规则，如 .*vip.*
            return try { Regex(r).matches(methodName) } catch (e: Exception) { false }
        }
        val ruleClass = r.substring(0, semiIndex + 1)
        val classMatched = if (ruleClass.endsWith(";")) ruleClass == className
        else {
            try { Regex(ruleClass, RegexOption.IGNORE_CASE).matches(className) } catch (e: Exception) { false }
        }
        if (!classMatched) return false
        val ruleMember = r.substring(semiIndex + 1).removePrefix(".")
        if (ruleMember.isEmpty()) return false
        if (ruleMember == "*" || ruleMember == methodName) return true
        return try { Regex(ruleMember).matches(methodName) } catch (e: Exception) { false }
    }

    private val ACCESS_MODIFIERS = setOf(
        "public", "private", "protected", "static", "final", "synthetic",
        "native", "abstract", "synchronized", "strictfp", "constructor",
        "declared-synchronized", "bridge", "varargs", "default"
    )

    /** smali `.method` 规则匹配：`.method public static onClick(I)V`。 */
    private fun methodRuleMatches(rule: String, className: String, methodName: String, methodDescriptor: String): Boolean {
        var r = rule.trim()
        val methodIdx = r.indexOf(".method")
        if (methodIdx < 0) return false
        var ruleClass: String? = null
        if (methodIdx > 0) {
            val classMarker = r.lastIndexOf("L", methodIdx)
            if (classMarker >= 0) {
                val semi = r.indexOf(';', classMarker)
                if (semi in classMarker until methodIdx) {
                    ruleClass = r.substring(classMarker, semi + 1)
                }
            }
        }
        r = r.substring(methodIdx).removePrefix(".method").trim()
        val tokens = r.split(' ').filter { it.isNotEmpty() }
        val first = tokens.firstOrNull { !ACCESS_MODIFIERS.contains(it) } ?: return false
        val openIdx = first.indexOf('(')
        val parsedName: String
        val parsedDescriptor: String
        if (openIdx >= 0) {
            parsedName = first.substring(0, openIdx)
            parsedDescriptor = first.substring(openIdx)
        } else {
            parsedName = first
            var rest = r.removePrefix(first).trim()
            if (rest.startsWith("(")) {
                val close = rest.indexOf(')')
                parsedDescriptor = if (close >= 0) rest.substring(0, close + 1) else ""
            } else {
                parsedDescriptor = ""
            }
        }
        if (ruleClass != null) {
            val classMatched = if (ruleClass == className) true
            else {
                val hasWildcard = ruleClass.contains('*') || ruleClass.contains('?')
                if (hasWildcard) { try { Regex(ruleClass).matches(className) } catch (e: Exception) { false } } else false
            }
            if (!classMatched) return false
        }
        val nameMatched = if (parsedName.contains('*') || parsedName.contains('?')) {
            try { Regex(parsedName, RegexOption.IGNORE_CASE).matches(methodName) } catch (e: Exception) { false }
        } else {
            parsedName == methodName
        }
        if (!nameMatched) return false
        if (parsedDescriptor.isNotEmpty() && parsedDescriptor != methodDescriptor) return false
        return true
    }

    /** 统计规则集命中条目数；规则为空表示全量抽取。 */
    fun countHits(entries: List<MethodEntry>, rulesText: String): Int {
        val rules = rulesText.lineSequence().filter { it.isNotBlank() }.toList()
        if (rules.isEmpty()) return entries.size
        return entries.count { e -> rules.any { ruleMatches(it, e.className, e.methodName, e.descriptor) } }
    }
}