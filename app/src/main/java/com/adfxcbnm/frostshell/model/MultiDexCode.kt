package com.adfxcbnm.frostshell.model

class MultiDexCode {
    var version: Short = 0
    var dexCount: Short = 0
    var dexCodesIndex: MutableList<Int>? = null
    var dexCodes: MutableList<DexCode>? = null

    override fun toString(): String {
        return "MultiDexCode{version=$version, dexCount=$dexCount, dexCodesIndex=$dexCodesIndex, dexCodes=$dexCodes}"
    }
}
