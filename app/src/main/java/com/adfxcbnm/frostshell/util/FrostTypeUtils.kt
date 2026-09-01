package com.adfxcbnm.frostshell.util

object FrostTypeUtils {
    fun getHumanizeTypeName(name: String?): String {
        if (name == null || "" == name) {
            return ""
        }
        when (name) {
            "V" -> return "void"
            "I" -> return "int"
            "D" -> return "double"
            "F" -> return "float"
            "S" -> return "short"
            "Z" -> return "boolean"
            "J" -> return "long"
            "B" -> return "byte"
        }
        if (name.length >= 2) {
            return name.substring(1, name.length - 1).replace("/", ".")
        }
        return name
    }

    fun toTypeDescriptor(className: String): String {
        if (className.isEmpty()) throw IllegalArgumentException("class name is empty")
        return if (className.startsWith("L") && className.endsWith(";")) {
            className
        } else {
            "L${className.replace('.', '/')};"
        }
    }
}
