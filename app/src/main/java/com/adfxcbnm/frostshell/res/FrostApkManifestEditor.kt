package com.adfxcbnm.frostshell.res

import com.adfxcbnm.hardeningtool.AndroidManifestModifier
import com.adfxcbnm.frostshell.util.FrostIoUtils
import pxb.android.axml.AxmlParser

object FrostApkManifestEditor {
    @JvmStatic
    fun writeApplicationName(inManifestFile: String, outManifestFile: String, newApplicationName: String) {
        applyApplicationAttribute(inManifestFile, outManifestFile, "name", newApplicationName)
    }

    @JvmStatic
    fun writeAppComponentFactory(inManifestFile: String, outManifestFile: String, newComponentFactory: String) {
        applyApplicationAttribute(inManifestFile, outManifestFile, "appComponentFactory", newComponentFactory)
    }

    @JvmStatic
    fun writeDebuggable(inManifestFile: String, outManifestFile: String, debuggable: String) {
        applyApplicationAttribute(
            inManifestFile,
            outManifestFile,
            "debuggable",
            debuggable,
            AndroidManifestModifier.ApplicationAttrPatch.BOOLEAN_TYPE
        )
    }

    @JvmStatic
    fun writeApplicationExtractNativeLibs(inManifestFile: String, outManifestFile: String, extractNativeLibs: String) {
        applyApplicationAttribute(
            inManifestFile,
            outManifestFile,
            "extractNativeLibs",
            extractNativeLibs,
            AndroidManifestModifier.ApplicationAttrPatch.BOOLEAN_TYPE
        )
    }

    private fun applyApplicationAttribute(
        inManifestFile: String,
        outManifestFile: String,
        attrName: String,
        attrValue: String,
        attrType: Int = AndroidManifestModifier.ApplicationAttrPatch.STRING_TYPE
    ) {
        val manifestData = FrostIoUtils.readFile(inManifestFile)
        val result = AndroidManifestModifier.modifyManifest(
            manifestData,
            appAttrs = listOf(
                AndroidManifestModifier.ApplicationAttrPatch(attrName, attrValue, attrType)
            )
        )
        FrostIoUtils.writeFile(outManifestFile, result.data)
    }

    @JvmStatic
    fun getAttributeValue(file: String, tag: String, ns: String?, attrName: String): String? {
        val axmlData = FrostIoUtils.readFile(file)
        val axmlParser = AxmlParser(axmlData)
        try {
            while (axmlParser.next() != 7) {
                if (axmlParser.attrCount != 0 && !axmlParser.name.equals(tag)) continue
                for (i in 0 until axmlParser.attrCount) {
                    if ((ns != null && !axmlParser.namespacePrefix.equals(ns)) || !axmlParser.getAttrName(i).equals(attrName)) continue
                    return axmlParser.getAttrValue(i) as String?
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    @JvmStatic
    fun getApplicationName(file: String): String? {
        var attributeValue = getAttributeValue(file, "application", "android", "name")
        attributeValue = attributeValue ?: getAttributeValue(file, "application", "dist", "name")
        attributeValue = attributeValue ?: getAttributeValue(file, "application", null, "name")
        return attributeValue
    }

    @JvmStatic
    fun getAppComponentFactory(file: String): String? {
        var attributeValue = getAttributeValue(file, "application", "android", "appComponentFactory")
        attributeValue = attributeValue ?: getAttributeValue(file, "application", "android", "appComponentFactory")
        attributeValue = attributeValue ?: getAttributeValue(file, "application", null, "appComponentFactory")
        return attributeValue
    }

    @JvmStatic
    fun getPackageName(file: String): String? {
        return getAttributeValue(file, "manifest", "android", "package")
    }

    /**
     * 收集 manifest 中所有组件引用的类全限定名（application/activity/alias/service/
     * receiver/provider/instrumentation plus appComponentFactory）。
     * 这些类对 class-rename 必须保护：被系统按清单名实例化，重命名不同步清单会启动崩溃。
     * 返回 dex 描述符格式（"Lcom/foo/Bar;"）。相对名"."前缀按 package 前缀展开。
     */
    @JvmStatic
    fun collectComponentClasses(file: String): Set<String> {
        return try {
            val data = FrostIoUtils.readFile(file)
            if (data.isEmpty()) emptySet() else AndroidManifestModifier.collectComponentClasses(data)
        } catch (e: Exception) {
            emptySet()
        }
    }
}
