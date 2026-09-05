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
        val parameterTypes: String = ""
    ) {
        /** 用于 UI 展示：类名.方法名 */
        val display get() = "$className.$methodName"
        /** 全局唯一 key：类名 + 方法名 + 参数签名，避免重载方法重复 */
        val uniqueKey get() = "$className.$methodName$parameterTypes"
        /** 生成精确抽取规则（带参数签名以区分重载） */
        fun toRule() = "$className.$methodName"
    }

    data class ScanResult(
        val entries: List<MethodEntry>,
        val classCount: Int,
        val methodCount: Int
    )

    fun scan(apkFile: File): ScanResult {
        val entries = ArrayList<MethodEntry>()
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
                            val params = try {
                                val protoIdx = methodIds[mid].protoIndex
                                val proto = dex.protoIds()[protoIdx]
                                val paramOff = proto.parametersOffset
                                if (paramOff == 0) "" else {
                                    val typeList = dex.readTypeList(paramOff)
                                    typeList.types.joinToString("") { idx ->
                                        if (idx >= 0 && idx < typeNames.size) typeNames[idx.toInt()] else "?"
                                    }
                                }
                            } catch (e: Exception) { "" }
                            entries.add(MethodEntry(className, name, params))
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
}