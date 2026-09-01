package com.adfxcbnm.frostshell.model

class DexCode {
    var methodCount: Short? = null
    var insnsIndex: MutableList<Int>? = null
    var insns: MutableList<Instruction>? = null

    override fun toString(): String {
        return "DexCode{methodCount=$methodCount, insnsIndex=$insnsIndex, insns=$insns}"
    }
}
