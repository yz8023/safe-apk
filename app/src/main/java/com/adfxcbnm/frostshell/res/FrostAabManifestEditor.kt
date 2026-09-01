package com.adfxcbnm.frostshell.res

import com.android.aapt.Resources
import java.io.FileInputStream
import java.io.FileOutputStream

object FrostAabManifestEditor {
    @JvmStatic
    fun writeApplicationExtractNativeLibs(inManifestFile: String, outManifestFile: String, newExtractNativeLibs: String) {
        try {
            FrostAndroidResourcesEditor.putAttribute(inManifestFile, outManifestFile, "application", "extractNativeLibs", newExtractNativeLibs)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @JvmStatic
    fun writeApplicationName(inManifestFile: String, outManifestFile: String, newApplicationName: String) {
        try {
            FrostAndroidResourcesEditor.putAttribute(inManifestFile, outManifestFile, "application", "name", newApplicationName)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @JvmStatic
    fun writeAppComponentFactory(inManifestFile: String, outManifestFile: String, newComponentFactory: String) {
        try {
            FrostAndroidResourcesEditor.putAttribute(inManifestFile, outManifestFile, "application", "appComponentFactory", newComponentFactory)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @JvmStatic
    fun writeDebuggable(inManifestFile: String, outManifestFile: String, debuggable: String) {
        try {
            FrostAndroidResourcesEditor.putAttribute(inManifestFile, outManifestFile, "application", "debuggable", debuggable)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @JvmStatic
    fun getAttributeValue(file: String, elementName: String, attrName: String): String? {
        return try {
            FrostAndroidResourcesEditor.getAttributeValue(file, elementName, attrName)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    @JvmStatic
    fun getApplicationName(file: String): String? = getAttributeValue(file, "application", "name")

    @JvmStatic
    fun getAppComponentFactory(file: String): String? = getAttributeValue(file, "application", "appComponentFactory")

    @JvmStatic
    fun getPackageName(file: String): String? = getAttributeValue(file, "manifest", "package")
}

object FrostAndroidResourcesEditor {
    const val DEBUGGABLE_RESOURCE_ID = 0x101000F
    const val EXTRACT_NATIVE_LIBS_RESOURCE_ID = 16844010
    const val NAME_RESOURCE_ID = 0x1010003
    const val APP_COMPONENT_FACTORY_RESOURCE_ID = 16844154
    const val DEBUGGABLE_ATTRIBUTE_NAME = "debuggable"
    const val EXTRACT_NATIVE_LIBS_ATTRIBUTE_NAME = "extractNativeLibs"
    const val NAME_ATTRIBUTE_NAME = "name"
    const val APP_COMPONENT_FACTORY_ATTRIBUTE_NAME = "appComponentFactory"

    private val resourceIdMap = mapOf(
        DEBUGGABLE_ATTRIBUTE_NAME to DEBUGGABLE_RESOURCE_ID,
        EXTRACT_NATIVE_LIBS_ATTRIBUTE_NAME to EXTRACT_NATIVE_LIBS_RESOURCE_ID,
        NAME_ATTRIBUTE_NAME to NAME_RESOURCE_ID,
        APP_COMPONENT_FACTORY_ATTRIBUTE_NAME to APP_COMPONENT_FACTORY_RESOURCE_ID
    )

    @Throws(Exception::class)
    fun putAttribute(filePath: String, outFileName: String, elementName: String, attributeName: String, newValue: String) {
        val xmlNode = FileInputStream(filePath).use { Resources.XmlNode.parseFrom(it) }
        val rootXmlNodeBuilder = Resources.XmlNode.newBuilder(xmlNode)
        val rootElementBuilder = rootXmlNodeBuilder.elementBuilder
        var namespaceUri = ""
        for (xmlNamespace in rootElementBuilder.namespaceDeclarationList) {
            if ("android" == xmlNamespace.prefix) {
                namespaceUri = xmlNamespace.uri
            }
        }
        var found = false
        if (elementName == rootElementBuilder.name) {
            for (xmlAttribute in rootElementBuilder.attributeBuilderList) {
                if (attributeName != xmlAttribute.name) continue
                xmlAttribute.value = newValue
                found = true
            }
        }
        if (!found) {
            for (i in 0 until rootElementBuilder.childBuilderList.size) {
                val childBuilder = rootElementBuilder.childBuilderList[i]
                if (!childBuilder.hasElement() || elementName != childBuilder.elementBuilder.name) continue
                val elementBuilder = childBuilder.elementBuilder
                val childAttributeList = elementBuilder.attributeBuilderList
                for (j in 0 until childAttributeList.size) {
                    val xmlAttribute = childAttributeList[j]
                    if (attributeName != xmlAttribute.name) continue
                    xmlAttribute.value = newValue
                    found = true
                }
                if (found) continue
                val builder = Resources.XmlAttribute.newBuilder()
                builder.name = attributeName
                builder.value = newValue
                builder.namespaceUri = namespaceUri
                if (newValue == "true" || newValue == "false") {
                    val itemBuilder = Resources.Item.newBuilder()
                    val primitiveBuilder = Resources.Primitive.newBuilder()
                    primitiveBuilder.booleanValue = true
                    itemBuilder.setPrim(primitiveBuilder.build())
                    builder.setCompiledItem(itemBuilder.build())
                }
                val resId = resourceIdMap[attributeName]
                builder.resourceId = resId ?: 0
                elementBuilder.addAttribute(builder)
            }
        }
        val build = rootXmlNodeBuilder.build()
        val byteArray = build.toByteArray()
        FileOutputStream(outFileName).use { fos ->
            fos.write(byteArray)
        }
    }

    @Throws(Exception::class)
    fun getAttributeValue(filePath: String, elementName: String, attributeName: String): String? {
        val xmlNode = FileInputStream(filePath).use { Resources.XmlNode.parseFrom(it) }
        val rootXmlNodeBuilder = Resources.XmlNode.newBuilder(xmlNode)
        val rootElementBuilder = rootXmlNodeBuilder.elementBuilder
        if (elementName == rootElementBuilder.name) {
            for (xmlAttribute in rootElementBuilder.attributeBuilderList) {
                if (attributeName != xmlAttribute.name) continue
                return xmlAttribute.value
            }
        }
        for (i in 0 until rootElementBuilder.childBuilderList.size) {
            val nodeBuilder = rootElementBuilder.childBuilderList[i]
            val elementBuilder = nodeBuilder.elementBuilder
            if (elementName != elementBuilder.name) continue
            for (j in 0 until elementBuilder.attributeBuilderList.size) {
                val xmlAttribute = elementBuilder.attributeBuilderList[j]
                if (attributeName != xmlAttribute.name) continue
                return xmlAttribute.value
            }
        }
        return null
    }
}
