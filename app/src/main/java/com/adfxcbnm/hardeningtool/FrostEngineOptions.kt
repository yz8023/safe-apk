package com.adfxcbnm.hardeningtool

data class FrostEngineOptions(
    val keepClasses: Boolean = false,
    val smaller: Boolean = false,
    val verifySign: Boolean = false,
    val soRandomization: Boolean = false,
    val disguiseSoName: String? = null,
    val excludedAbi: List<String>? = null,
    val stringEncrypt: Boolean = false,
    val stringEncryptMinLen: Int = 6,
    val stringEncryptKeywords: Set<String>? = null,
    val dexHeaderObfuscation: Boolean = false,
    val classShuffle: Boolean = false,
    val debugRemoval: Boolean = false,
    val gotoInsertion: Boolean = false,
    val arithmeticObfuscation: Boolean = false,
    val controlFlow: Boolean = false,
    val callIndirection: Boolean = false,
    val methodOverload: Boolean = false,
    val fieldRename: Boolean = false,
    val classRename: Boolean = false,
    val signEnabled: Boolean = true,
    val signKeystorePath: String? = null,
    val signAlias: String? = null,
    val signStorePass: String? = null,
    val signKeyPass: String? = null,
    val extractMethodRules: List<String>? = null
)
