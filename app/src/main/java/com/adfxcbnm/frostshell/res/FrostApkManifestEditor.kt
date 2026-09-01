package com.adfxcbnm.frostshell.res

import com.adfxcbnm.frostshell.util.FrostIoUtils
import com.wind.meditor.core.FileProcesser
import com.wind.meditor.property.AttributeItem
import com.wind.meditor.property.ModificationProperty
import pxb.android.axml.AxmlParser

object FrostApkManifestEditor {
    @JvmStatic
    fun writeApplicationName(inManifestFile: String, outManifestFile: String, newApplicationName: String) {
        val property = ModificationProperty()
        property.addApplicationAttribute(AttributeItem("name", newApplicationName))
        FileProcesser.processManifestFile(inManifestFile, outManifestFile, property)
    }

    @JvmStatic
    fun writeAppComponentFactory(inManifestFile: String, outManifestFile: String, newComponentFactory: String) {
        val property = ModificationProperty()
        property.addApplicationAttribute(AttributeItem("appComponentFactory", newComponentFactory))
        FileProcesser.processManifestFile(inManifestFile, outManifestFile, property)
    }

    @JvmStatic
    fun writeDebuggable(inManifestFile: String, outManifestFile: String, debuggable: String) {
        val property = ModificationProperty()
        property.addApplicationAttribute(AttributeItem("debuggable", debuggable))
        FileProcesser.processManifestFile(inManifestFile, outManifestFile, property)
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
}
