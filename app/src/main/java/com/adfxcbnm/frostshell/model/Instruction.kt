package com.adfxcbnm.frostshell.model

import java.util.Arrays

class Instruction {
    var methodIndex: Int = 0
    var instructionDataSize: Int = 0
    var instructionsData: ByteArray = ByteArray(0)

    override fun toString(): String {
        return "Instruction{methodIndex=$methodIndex, instructionDataSize=$instructionDataSize, instructionsData=" +
            Arrays.toString(instructionsData) + "}"
    }
}
