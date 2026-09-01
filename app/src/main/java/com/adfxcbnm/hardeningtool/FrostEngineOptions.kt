package com.adfxcbnm.hardeningtool

data class FrostEngineOptions(
    val keepClasses: Boolean = false,
    val smaller: Boolean = false,
    val verifySign: Boolean = false,
    val excludedAbi: List<String>? = null,
    val signEnabled: Boolean = true,
    val signKeystorePath: String? = null,
    val signAlias: String? = null,
    val signStorePass: String? = null,
    val signKeyPass: String? = null
)
