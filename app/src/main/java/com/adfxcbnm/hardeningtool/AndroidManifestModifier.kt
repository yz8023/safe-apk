package com.adfxcbnm.hardeningtool

import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

object AndroidManifestModifier {

    private const val RES_XML_TYPE = 0x0003
    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val RES_XML_RESOURCE_ID_TYPE = 0x0180
    private const val RES_XML_START_NAMESPACE_TYPE = 0x0100
    private const val RES_XML_END_NAMESPACE_TYPE = 0x0101
    private const val RES_XML_START_ELEMENT_TYPE = 0x0102
    private const val RES_XML_END_ELEMENT_TYPE = 0x0103

    private const val TYPE_STRING = 0x03
    private const val TYPE_INT_BOOLEAN = 0x12
    private const val TYPE_INT_HEX = 0x11
    private const val TYPE_REFERENCE = 0x01
    private const val TYPE_INT_DEC = 0x10
    private const val TYPE_FLOAT = 0x04
    private const val TYPE_DIMENSION = 0x05
    private const val TYPE_FRACTION = 0x06
    private const val TYPE_DYNAMIC_REFERENCE = 0x1001
    private const val TYPE_ATTRIBUTE = 0x02

    data class ModResult(val data: ByteArray, val modified: Boolean)

    data class ApplicationAttrPatch(
        val name: String,
        val value: String,
        val type: Int = STRING_TYPE
    ) {
        fun dataOf(): Int = when (type) {
            STRING_TYPE -> 0
            else -> if (value.equals("true", ignoreCase = true)) -1 else 0
        }

        companion object {
            const val STRING_TYPE = 0x03
            const val BOOLEAN_TYPE = 0x12
        }
    }

    private fun s2b(v: Short) = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v).array()
    private fun i2b(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()

    private class TrackingStream(val src: InputStream) {
        var pos = 0
        fun read(): Int { val v = src.read(); if (v >= 0) pos++; return v }
        fun read(b: ByteArray, off: Int, len: Int): Int { val n = src.read(b, off, len); if (n > 0) pos += n; return n }
        fun skip(n: Long): Long { if (n <= 0) return 0; val s = src.skip(n); if (s > 0) pos += s.toInt(); return s }
        fun readUnsignedByte(): Int { return read() and 0xFF }
        fun readUnsignedShort(): Int { return (read() and 0xFF) or ((read() and 0xFF) shl 8) }
        fun readInt(): Int { return (read() and 0xFF) or ((read() and 0xFF) shl 8) or ((read() and 0xFF) shl 16) or ((read() and 0xFF) shl 24) }
        fun available(): Int = src.available()
    }

    data class Attr(
        val name: String,
        val value: String,
        val type: Int,
        val data: Int,
        val ns: String? = null
    )

    private sealed class Chunk {
        data class Namespace(val prefix: Int, val uri: Int) : Chunk()
        data class Element(
            val name: String,
            val attrs: List<Attr>?,
            val isStart: Boolean,
            val namespace: String?
        ) : Chunk()
        data class ResourceIds(val ids: List<Int>) : Chunk()
    }

    private data class StringPoolResult(
        val strings: MutableList<String>,
        val isUtf8: Boolean
    )

    fun modifyManifest(
        orig: ByteArray,
        context: android.content.Context? = null,
        features: List<String> = emptyList(),
        targetPackage: String = "",
        log: ((String) -> Unit)? = null,
        appAttrs: List<ApplicationAttrPatch> = emptyList()
    ): ModResult {
        try {
            if (orig.size < 8) { log?.invoke("manifest too small: ${orig.size}"); return ModResult(orig, false) }

            val r = TrackingStream(ByteArrayInputStream(orig))
            val xmlType = r.readUnsignedShort()
            val headerSize = r.readUnsignedShort()
            val fileSize = r.readInt()

            if (xmlType != RES_XML_TYPE || headerSize != 8) { log?.invoke("bad xmlType=$xmlType headerSize=$headerSize"); return ModResult(orig, false) }

            val stringPoolResult = readStringPool(r, log)
            val stringPool = stringPoolResult.strings
            val originalStringPool = stringPool.toList()
            val originalUtf8 = stringPoolResult.isUtf8
            if (stringPool.isEmpty()) { log?.invoke("string pool empty"); return ModResult(orig, false) }

            val allChunks = readAllChunks(r, stringPool, log)
            val namespaces = allChunks.filterIsInstance<Chunk.Namespace>().map { Pair(it.prefix, it.uri) }
            val elements = allChunks.filterIsInstance<Chunk.Element>().toMutableList()

            log?.invoke("parsed ${elements.size} elements, ${namespaces.size} ns, ${stringPool.size} strings, utf8=$originalUtf8")

            val appIdx = elements.indexOfFirst { it.name == "application" && it.isStart }
            if (appIdx < 0) { log?.invoke("no application tag found"); return ModResult(orig, false) }

            val authority = "com.adfxcbnm.authority" + if (targetPackage.isNotEmpty()) ".$targetPackage" else ""

            val androidNs = "http://schemas.android.com/apk/res/android"

            // 写 application 属性时，新增的属性名/值必须出现在字符串池中
            val appElementIdx = elements.indexOfFirst { it.name == "application" && it.isStart }
            val appElement = if (appElementIdx >= 0) elements[appElementIdx] else null

            for (patch in appAttrs) {
                if (!stringPool.contains(patch.name)) stringPool.add(patch.name)
                if (patch.type == TYPE_STRING && !stringPool.contains(patch.value)) stringPool.add(patch.value)
                if (!stringPool.contains(androidNs)) stringPool.add(androidNs)
            }

            val appElementPatched =
                if (appAttrs.isNotEmpty() && appElementIdx >= 0) {
                    var changed = false
                    val newAttrs = appElement!!.attrs?.toMutableList() ?: mutableListOf()
                    for (patch in appAttrs) {
                        val existingIdx = newAttrs.indexOfFirst { it.name == patch.name }
                        if (existingIdx >= 0) {
                            if (patch.type == TYPE_STRING) {
                                if (newAttrs[existingIdx].value != patch.value) {
                                    newAttrs[existingIdx] = newAttrs[existingIdx].copy(
                                        value = patch.value,
                                        type = TYPE_STRING,
                                        data = 0
                                    )
                                    changed = true
                                }
                            } else {
                                if (newAttrs[existingIdx].data != patch.dataOf()) {
                                    newAttrs[existingIdx] = newAttrs[existingIdx].copy(
                                        value = patch.value,
                                        type = patch.type,
                                        data = patch.dataOf()
                                    )
                                    changed = true
                                }
                            }
                        } else {
                            newAttrs.add(Attr(patch.name, patch.value, patch.type, patch.dataOf(), androidNs))
                            changed = true
                        }
                    }
                    if (changed) elements[appElementIdx] = appElement.copy(attrs = newAttrs)
                    changed
                } else {
                    false
                }

            val alreadyInjected = elements.any { el ->
                el.name == "provider" && el.isStart &&
                    (el.attrs?.any { it.name == "name" && it.value == "com.adfxcbnm.protect.SecurityCheckProvider" } ?: false)
            }
            val injectProvider = appAttrs.isEmpty() && !alreadyInjected
            if (alreadyInjected && appAttrs.isEmpty()) {
                // 已注入过 SecurityCheckProvider：清理重复声明，只保留第一个，避免重复 authority
                val toRemove = mutableSetOf<Int>()
                val providerStartIdxs = mutableListOf<Int>()
                for (i in elements.indices) {
                    val el = elements[i]
                    if (el.name == "provider" && el.isStart &&
                        (el.attrs?.any { it.name == "name" && it.value == "com.adfxcbnm.protect.SecurityCheckProvider" } ?: false)) {
                        providerStartIdxs.add(i)
                    }
                }
                if (providerStartIdxs.size > 1) {
                    for (k in 1 until providerStartIdxs.size) {
                        val start = providerStartIdxs[k]
                        toRemove.add(start)
                        var depth = 0
                        for (j in start until elements.size) {
                            val sub = elements[j]
                            if (sub.name == "provider") {
                                if (sub.isStart) depth++ else {
                                    depth--
                                    if (depth == 0) {
                                        toRemove.add(j)
                                        break
                                    }
                                }
                            }
                        }
                    }
                }
                if (toRemove.isNotEmpty()) {
                    val filtered = mutableListOf<Chunk.Element>()
                    for (i in elements.indices) {
                        if (i in toRemove) {
                            log?.invoke("removed duplicate provider at element index $i")
                        } else {
                            filtered.add(elements[i])
                        }
                    }
                    elements.clear()
                    elements.addAll(filtered)
                }
                log?.invoke("provider already injected, dedup done (kept=${providerStartIdxs.size - toRemove.count { it in providerStartIdxs }})")
            }
            if (injectProvider) {
                val neededStrings = mutableListOf(
                    "com.adfxcbnm.protect.SecurityCheckProvider",
                    authority,
                    "name",
                    "authorities",
                    "exported",
                    "false",
                    "provider",
                    androidNs
                )
                for (s in neededStrings) {
                    if (!stringPool.contains(s)) stringPool.add(s)
                }

                val insIdx = findApplicationEnd(elements, appIdx)
                val providerAttrs = listOf(
                    Attr("name", "com.adfxcbnm.protect.SecurityCheckProvider", TYPE_STRING, 0, androidNs),
                    Attr("authorities", authority, TYPE_STRING, 0, androidNs),
                    Attr("exported", "false", TYPE_INT_BOOLEAN, 0, androidNs)
                )
                val providerStart = Chunk.Element("provider", providerAttrs, true, null)
                val providerEnd = Chunk.Element("provider", null, false, null)

                elements.add(insIdx, providerStart)
                elements.add(insIdx + 1, providerEnd)
            }

            val out = ByteArrayOutputStream()
            writeStringPool(out, stringPool, originalUtf8)
            val resourceIds = allChunks.filterIsInstance<Chunk.ResourceIds>().flatMap { it.ids }
            writeResourceIds(out, stringPool, resourceIds, originalStringPool)
            writeContent(out, namespaces, elements, stringPool)

            val payload = out.toByteArray()
            val result = ByteArrayOutputStream()
            result.write(s2b(RES_XML_TYPE.toShort()))
            result.write(s2b(8))
            result.write(i2b(8 + payload.size))
            result.write(payload)

            val resultData = result.toByteArray()

            if (!validateManifest(resultData, log)) {
                log?.invoke("manifest validation failed, using original")
                return ModResult(orig, false)
            }

            return ModResult(resultData, true)
        } catch (e: Exception) {
            log?.invoke("manifest modify exception: ${e.javaClass.simpleName}: ${e.message}")
            return ModResult(orig, false)
        }
    }

    /**
     * 从二进制 AndroidManifest.xml 收集所有组件引用的类（application/activity/alias/
     * service/receiver/provider/instrumentation 的 android:name 属性）。
     * 返回 dex 描述符（"Lcom/foo/Bar;"）。相对名（".Bar" 或纯类名）按 package 前缀展开。
     * 供 class-rename 保护清单组件，避免组件类被重命名后清单失配导致启动崩溃。
     */
    fun collectComponentClasses(orig: ByteArray, log: ((String) -> Unit)? = null): Set<String> {
        val out = LinkedHashSet<String>()
        try {
            if (orig.size < 8) return out
            val r = TrackingStream(ByteArrayInputStream(orig))
            val xmlType = r.readUnsignedShort()
            val headerSize = r.readUnsignedShort()
            r.readInt()
            if (xmlType != RES_XML_TYPE || headerSize != 8) return out
            val stringPoolResult = readStringPool(r, log)
            val stringPool = stringPoolResult.strings
            if (stringPool.isEmpty()) return out
            val allChunks = readAllChunks(r, stringPool, log)
            val elements = allChunks.filterIsInstance<Chunk.Element>()
            var packageName: String? = null
            for (el in elements) {
                if (el.name == "manifest" && el.isStart) {
                    for (a in el.attrs ?: emptyList()) if (a.name == "package") packageName = a.value
                }
            }
            val componentTags = arrayOf(
                "application", "activity", "activity-alias", "service", "receiver", "provider", "instrumentation"
            )
            for (el in elements) {
                if (!el.isStart) continue
                if (el.name !in componentTags) continue
                for (a in el.attrs ?: emptyList()) {
                    if (a.name != "name" || a.value.isEmpty()) continue
                    if (a.type != TYPE_STRING) continue
                    toComponentDescriptor(a.value, packageName)?.let { out.add(it) }
                }
            }
        } catch (e: Exception) {
            log?.invoke("collectComponentClasses: ${e.message}")
        }
        return out
    }

    private fun toComponentDescriptor(raw: String, packageName: String?): String? {
        var cls = raw.trim()
        if (cls.startsWith(".")) {
            if (packageName.isNullOrEmpty()) return null
            cls = "$packageName$cls"
        } else if (!cls.contains(".")) {
            if (packageName.isNullOrEmpty()) return null
            cls = "$packageName.$cls"
        }
        if (!cls.startsWith("L")) {
            cls = "L" + cls.replace('.', '/') + ";"
        }
        if (cls.length <= 2) return null
        return cls
    }

    private fun readStringPool(r: TrackingStream, log: ((String) -> Unit)? = null): StringPoolResult {
        val basePos = r.pos
        val chunkType = r.readUnsignedShort()
        val headerSize = r.readUnsignedShort()
        val chunkSize = r.readInt()

        if (chunkType != RES_STRING_POOL_TYPE) {
            r.skip((chunkSize - (r.pos - basePos)).toLong())
            return StringPoolResult(mutableListOf(), false)
        }

        val stringCount = r.readInt()
        val styleCount = r.readInt()
        val flags = r.readInt()
        val stringsStart = r.readInt()
        val stylesStart = r.readInt()
        val isUtf8 = (flags and 0x100) != 0

        val offsets = IntArray(stringCount)
        for (i in 0 until stringCount) offsets[i] = r.readInt()

        val stringsStartAbsolute = basePos + stringsStart

        val result = MutableList(stringCount) { "" }
        for (i in 0 until stringCount) {
            val strOffset = stringsStartAbsolute + offsets[i]
            val skip = strOffset - r.pos
            if (skip > 0) r.skip(skip.toLong())

            result[i] = if (isUtf8) {
                val charCountRaw = r.readUnsignedByte()
                val charCount = if (charCountRaw and 0x80 != 0) {
                    val lo = charCountRaw and 0x7F
                    val hi = r.readUnsignedByte()
                    (hi shl 7) or lo
                } else {
                    charCountRaw
                }
                val byteCountRaw = r.readUnsignedByte()
                val byteCount = if (byteCountRaw and 0x80 != 0) {
                    val lo = byteCountRaw and 0x7F
                    val hi = r.readUnsignedByte()
                    (hi shl 7) or lo
                } else {
                    byteCountRaw
                }
                if (byteCount > 0) {
                    val chars = ByteArray(byteCount)
                    r.read(chars, 0, byteCount)
                    r.read()
                    String(chars, Charsets.UTF_8)
                } else {
                    r.read()
                    ""
                }
            } else {
                val charCountRaw = r.readUnsignedShort()
                val charCount = if (charCountRaw and 0x8000 != 0) {
                    val lo = charCountRaw and 0x7FFF
                    val hi = r.readUnsignedShort()
                    (hi shl 15) or lo
                } else {
                    charCountRaw
                }
                val byteCount = charCount * 2
                if (byteCount > 0) {
                    val chars = ByteArray(byteCount)
                    r.read(chars, 0, byteCount)
                    r.skip(2)
                    String(chars, Charsets.UTF_16LE)
                } else {
                    r.skip(2)
                    ""
                }
            }
        }

        val endPos = basePos + chunkSize
        if (endPos > r.pos) r.skip((endPos - r.pos).toLong())
        return StringPoolResult(result, isUtf8)
    }

    private fun readAllChunks(r: TrackingStream, stringPool: List<String>, log: ((String) -> Unit)? = null): List<Chunk> {
        val chunks = mutableListOf<Chunk>()
        while (r.available() > 0) {
            val basePos = r.pos
            val chunkType = r.readUnsignedShort()
            val headerSize = r.readUnsignedShort()
            val chunkSize = r.readInt()

            when (chunkType) {
                RES_XML_START_NAMESPACE_TYPE -> {
                    val lineNum = r.readInt()
                    val comment = r.readInt()
                    val prefixIdx = r.readInt()
                    val uriIdx = r.readInt()
                    chunks.add(Chunk.Namespace(prefixIdx, uriIdx))
                }
                RES_XML_END_NAMESPACE_TYPE -> {
                    r.skip((chunkSize - (r.pos - basePos)).toLong())
                }
                RES_XML_START_ELEMENT_TYPE -> {
                    val lineNum = r.readInt()
                    val comment = r.readInt()
                    val nsIdx = r.readInt()
                    val nameIdx = r.readInt()
                    val attrStart = r.readUnsignedShort()
                    val attrSize = r.readUnsignedShort()
                    val attrCount = r.readUnsignedShort()
                    val idIndex = r.readUnsignedShort()
                    val classIndex = r.readUnsignedShort()
                    val styleIndex = r.readUnsignedShort()

                    val ns = if (nsIdx >= 0 && nsIdx < stringPool.size) stringPool[nsIdx] else null
                    val name = if (nameIdx >= 0 && nameIdx < stringPool.size) stringPool[nameIdx] else ""

                    val attrs = mutableListOf<Attr>()
                    for (i in 0 until attrCount) {
                        val attrNsIdx = r.readInt()
                        val attrNameIdx = r.readInt()
                        val attrValueStrIdx = r.readInt()
                        val attrAttrSize = r.readUnsignedShort()
                        val attrRes0 = r.readUnsignedByte()
                        val attrType = r.readUnsignedByte()
                        val attrData = r.readInt()

                        val attrNs = if (attrNsIdx >= 0 && attrNsIdx < stringPool.size) stringPool[attrNsIdx] else null
                        val attrName = if (attrNameIdx >= 0 && attrNameIdx < stringPool.size) stringPool[attrNameIdx] else ""
                        val attrValue = if (attrValueStrIdx >= 0 && attrValueStrIdx < stringPool.size) {
                            stringPool[attrValueStrIdx]
                        } else {
                            parseAttrValue(attrType, attrData, stringPool)
                        }
                        attrs.add(Attr(attrName, attrValue, attrType, attrData, attrNs))
                    }

                    chunks.add(Chunk.Element(name, attrs, true, ns))
                }
                RES_XML_END_ELEMENT_TYPE -> {
                    val lineNum = r.readInt()
                    val comment = r.readInt()
                    val nsIdx = r.readInt()
                    val nameIdx = r.readInt()
                    val ns = if (nsIdx >= 0 && nsIdx < stringPool.size) stringPool[nsIdx] else null
                    val name = if (nameIdx >= 0 && nameIdx < stringPool.size) stringPool[nameIdx] else ""
                    chunks.add(Chunk.Element(name, null, false, ns))
                }
                RES_XML_RESOURCE_ID_TYPE -> {
                    val count = (chunkSize - headerSize) / 4
                    val ids = mutableListOf<Int>()
                    for (i in 0 until count) ids.add(r.readInt())
                    chunks.add(Chunk.ResourceIds(ids))
                }
                else -> {
                    r.skip((chunkSize - (r.pos - basePos)).toLong())
                }
            }
        }
        return chunks
    }

    private fun parseAttrValue(type: Int, data: Int, stringPool: List<String>): String {
        return when (type) {
            TYPE_REFERENCE -> "@${Integer.toHexString(data)}"
            TYPE_ATTRIBUTE -> "?${Integer.toHexString(data)}"
            TYPE_INT_BOOLEAN -> if (data != 0) "true" else "false"
            TYPE_INT_HEX -> "0x${Integer.toHexString(data)}"
            TYPE_INT_DEC -> data.toString()
            TYPE_FLOAT -> Float.fromBits(data).toString()
            TYPE_DIMENSION -> data.toString()
            TYPE_FRACTION -> data.toString()
            TYPE_STRING -> stringPool.getOrElse(data) { "" }
            else -> data.toString()
        }
    }

    private fun findApplicationEnd(elements: List<Chunk.Element>, start: Int): Int {
        var depth = 0
        for (i in start + 1 until elements.size) {
            val n = elements[i]
            if (n.name == "application") {
                if (n.isStart) depth++ else {
                    depth--
                    if (depth < 0) return i
                }
            }
        }
        return elements.size - 1
    }

    private fun writeStringPool(out: ByteArrayOutputStream, strings: List<String>, utf8: Boolean = true) {
        val stringData = ByteArrayOutputStream()
        val offsets = IntArray(strings.size)
        var currentOffset = 0

        for (i in strings.indices) {
            offsets[i] = currentOffset
            val s = strings[i]

            if (utf8) {
                val bytes = s.toByteArray(Charsets.UTF_8)
                val charCount = s.length
                val byteCount = bytes.size

                if (charCount < 128) {
                    stringData.write(charCount)
                } else {
                    stringData.write((charCount and 0x7F) or 0x80)
                    stringData.write((charCount shr 7) and 0xFF)
                }

                if (byteCount < 128) {
                    stringData.write(byteCount)
                } else {
                    stringData.write((byteCount and 0x7F) or 0x80)
                    stringData.write((byteCount shr 7) and 0xFF)
                }

                stringData.write(bytes, 0, bytes.size)
                stringData.write(0)
                currentOffset += (if (charCount < 128) 1 else 2) + (if (byteCount < 128) 1 else 2) + bytes.size + 1
            } else {
                val charCount = s.length
                val bytes = s.toByteArray(Charsets.UTF_16LE)
                val byteCount = bytes.size

                if (charCount < 0x8000) {
                    stringData.write(charCount and 0xFF)
                    stringData.write((charCount shr 8) and 0xFF)
                    stringData.write(bytes, 0, byteCount)
                    stringData.write(0)
                    stringData.write(0)
                    currentOffset += 2 + byteCount + 2
                } else {
                    val high = (charCount and 0x7FFF) or 0x8000
                    val low = (charCount shr 15) and 0xFFFF
                    stringData.write(high and 0xFF)
                    stringData.write((high shr 8) and 0xFF)
                    stringData.write(low and 0xFF)
                    stringData.write((low shr 8) and 0xFF)
                    stringData.write(bytes, 0, byteCount)
                    stringData.write(0)
                    stringData.write(0)
                    currentOffset += 4 + byteCount + 2
                }
            }
        }

        while (stringData.size() % 4 != 0) stringData.write(0)

        val stringsStart = 28 + strings.size * 4
        val chunkSize = stringsStart + stringData.size()

        out.write(s2b(RES_STRING_POOL_TYPE.toShort()))
        out.write(s2b(28))
        out.write(i2b(chunkSize))
        out.write(i2b(strings.size))
        out.write(i2b(0))
        out.write(i2b(if (utf8) 0x100 else 0x0))
        out.write(i2b(stringsStart))
        out.write(i2b(0))
        for (o in offsets) out.write(i2b(o))
        out.write(stringData.toByteArray())
    }

    private fun writeResourceIds(
        out: ByteArrayOutputStream,
        strings: List<String>,
        originalIds: List<Int>,
        originalStrings: List<String>
    ) {
        if (strings.isEmpty()) return
        val nameToId = HashMap<String, Int>(strings.size * 2)
        val limit = minOf(originalIds.size, originalStrings.size)
        for (i in 0 until limit) {
            val id = originalIds[i]
            if (id != 0) nameToId[originalStrings[i]] = id
        }
        for ((name, id) in KNOWN_ANDROID_ATTR_IDS) {
            if (!nameToId.containsKey(name)) nameToId[name] = id
        }
        val ids = IntArray(strings.size)
        for (i in strings.indices) {
            ids[i] = nameToId[strings[i]] ?: 0
        }
        val chunkSize = 8 + ids.size * 4
        out.write(s2b(RES_XML_RESOURCE_ID_TYPE.toShort()))
        out.write(s2b(8))
        out.write(i2b(chunkSize))
        for (id in ids) out.write(i2b(id))
    }

    private val KNOWN_ANDROID_ATTR_IDS = mapOf(
        "theme" to 0x01010000,
        "label" to 0x01010001,
        "icon" to 0x01010002,
        "name" to 0x01010003,
        "permission" to 0x01010006,
        "protectionLevel" to 0x01010009,
        "enabled" to 0x0101000e,
        "debuggable" to 0x0101000f,
        "exported" to 0x01010010,
        "process" to 0x01010011,
        "authorities" to 0x01010018,
        "grantUriPermissions" to 0x0101001b,
        "value" to 0x01010024,
        "resource" to 0x01010025,
        "minSdkVersion" to 0x0101020c,
        "versionCode" to 0x0101021b,
        "versionName" to 0x0101021c,
        "windowSoftInputMode" to 0x0101022b,
        "targetSdkVersion" to 0x01010270,
        "allowBackup" to 0x01010280,
        "largeHeap" to 0x0101035a,
        "supportsRtl" to 0x010103af,
        "extractNativeLibs" to 0x010104ea,
        "directBootAware" to 0x01010505,
        "compileSdkVersion" to 0x01010572,
        "compileSdkVersionCodename" to 0x01010573,
        "appComponentFactory" to 0x0101057a
    )

    private fun writeContent(
        out: ByteArrayOutputStream,
        namespaces: List<Pair<Int, Int>>,
        elements: List<Chunk.Element>,
        stringPool: List<String>
    ) {
        val indexMap = buildStringIndexMap(stringPool)

        for ((prefix, uri) in namespaces) {
            out.write(s2b(RES_XML_START_NAMESPACE_TYPE.toShort()))
            out.write(s2b(16))
            out.write(i2b(24))
            out.write(i2b(0))
            out.write(i2b(-1))
            out.write(i2b(prefix))
            out.write(i2b(uri))
        }

        for (element in elements) {
            if (element.isStart) {
                writeStartElement(out, element, stringPool, indexMap)
            } else {
                writeEndElement(out, element, indexMap)
            }
        }

        for ((prefix, uri) in namespaces) {
            out.write(s2b(RES_XML_END_NAMESPACE_TYPE.toShort()))
            out.write(s2b(16))
            out.write(i2b(24))
            out.write(i2b(0))
            out.write(i2b(-1))
            out.write(i2b(prefix))
            out.write(i2b(uri))
        }
    }

    private fun buildStringIndexMap(strings: List<String>): Map<String, Int> {
        val map = HashMap<String, Int>(strings.size * 2)
        for (i in strings.indices) map[strings[i]] = i
        return map
    }

    private fun writeStartElement(out: ByteArrayOutputStream, element: Chunk.Element, stringPool: List<String>, indexMap: Map<String, Int>) {
        val nsIdx = if (element.namespace != null) indexMap[element.namespace] ?: -1 else -1
        val nameIdx = indexMap[element.name] ?: -1

        val attrBytes = ByteArrayOutputStream()
        val attrs = element.attrs ?: emptyList()

        for (attr in attrs) {
            val attrNsIdx = if (attr.ns != null) indexMap[attr.ns] ?: -1 else -1
            val attrNameIdx = indexMap[attr.name] ?: -1
            val attrValueStrIdx = if (attr.type == TYPE_STRING) {
                indexMap[attr.value] ?: -1
            } else {
                -1
            }

            attrBytes.write(i2b(attrNsIdx))
            attrBytes.write(i2b(attrNameIdx))
            attrBytes.write(i2b(attrValueStrIdx))
            attrBytes.write(s2b(20))  // attributeSize = 20
            attrBytes.write(0)         // res0 = 0
            attrBytes.write(attr.type) // dataType
            val dataVal = if (attr.type == TYPE_STRING) attrValueStrIdx else attr.data
            attrBytes.write(i2b(dataVal)) // data
        }

        val attrData = attrBytes.toByteArray()
        // chunkSize = 16 (header) + 20 (attrExt) + attrData
        val chunkSize = 16 + 20 + attrData.size

        out.write(s2b(RES_XML_START_ELEMENT_TYPE.toShort()))  // type = 0x0102
        out.write(s2b(16))                                     // headerSize = 16
        out.write(i2b(chunkSize))                              // chunkSize
        out.write(i2b(0))                                      // lineNumber = 0
        out.write(i2b(-1))                                     // comment = -1 (0xFFFFFFFF)
        out.write(i2b(nsIdx))                                  // ns index
        out.write(i2b(nameIdx))                                // name index
        out.write(s2b(20))                                     // attributeStart = 20 (从attrExt开始到attributes的偏移)
        out.write(s2b(20))                                     // attributeSize = 20 (每个attribute的大小)
        out.write(s2b(attrs.size.toShort()))                   // attributeCount
        out.write(s2b(0))                                      // idIndex = 0
        out.write(s2b(0))                                      // classIndex = 0
        out.write(s2b(0))                                      // styleIndex = 0
        out.write(attrData)
    }

    private fun writeEndElement(out: ByteArrayOutputStream, element: Chunk.Element, indexMap: Map<String, Int>) {
        val nsIdx = if (element.namespace != null) indexMap[element.namespace] ?: -1 else -1
        val nameIdx = indexMap[element.name] ?: -1

        out.write(s2b(RES_XML_END_ELEMENT_TYPE.toShort()))
        out.write(s2b(16))
        out.write(i2b(24))
        out.write(i2b(0))
        out.write(i2b(-1))
        out.write(i2b(nsIdx))
        out.write(i2b(nameIdx))
    }

    private fun validateManifest(data: ByteArray, log: ((String) -> Unit)? = null): Boolean {
        try {
            if (data.size < 8) return false
            val r = TrackingStream(ByteArrayInputStream(data))
            val xmlType = r.readUnsignedShort()
            val headerSize = r.readUnsignedShort()
            val fileSize = r.readInt()

            if (xmlType != RES_XML_TYPE || headerSize != 8 || fileSize != data.size) {
                log?.invoke("validate: header mismatch xmlType=$xmlType headerSize=$headerSize fileSize=$fileSize actual=${data.size}")
                return false
            }

            val basePos = r.pos
            val chunkType = r.readUnsignedShort()
            if (chunkType != RES_STRING_POOL_TYPE) {
                log?.invoke("validate: first chunk not string pool: 0x${chunkType.toString(16)}")
                return false
            }
            r.skip(2)
            val spChunkSize = r.readInt()
            r.skip((basePos + spChunkSize - r.pos).coerceAtLeast(0).toLong())

            var nsCount = 0
            var elementCount = 0
            while (r.available() > 0) {
                val pos = r.pos
                val type = r.readUnsignedShort()
                r.skip(2)
                val size = r.readInt()
                when (type) {
                    RES_XML_START_NAMESPACE_TYPE, RES_XML_END_NAMESPACE_TYPE -> nsCount++
                    RES_XML_START_ELEMENT_TYPE, RES_XML_END_ELEMENT_TYPE -> elementCount++
                }
                r.skip((pos + size - r.pos).coerceAtLeast(0).toLong())
            }

            if (elementCount == 0) {
                log?.invoke("validate: no elements found")
                return false
            }
            log?.invoke("validate: OK ns=$nsCount elements=$elementCount")
            return true
        } catch (e: Exception) {
            log?.invoke("validate exception: ${e.javaClass.simpleName}: ${e.message}")
            return false
        }
    }
}
