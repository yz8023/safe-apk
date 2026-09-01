package com.adfxcbnm.hardeningtool

data class FrostEngineOptions(
    val keepClasses: Boolean = false,
    val smaller: Boolean = false,
    val verifySign: Boolean = false,
    val excludedAbi: List<String>? = null
)
