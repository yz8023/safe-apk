package com.adfxcbnm.frostshell.dex

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.Label
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction10t
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction10x
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction11n
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction11x
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction12x
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21c
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction22b
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction22t
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction23x
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference

object FrostDexStringDecryptBuilder {
    private const val JAVA_STRING = "Ljava/lang/String;"
    private const val CHAR_ARRAY = "[C"
    private val TO_CHAR_ARRAY = ImmutableMethodReference("Ljava/lang/String;", "toCharArray", emptyList(), "[C")
    private val STRING_INIT = ImmutableMethodReference("Ljava/lang/String;", "<init>", listOf("[C"), "V")

    fun emitDecrypt(
        impl: MutableMethodImplementation,
        outReg: Int,
        encrypted: String,
        arrReg: Int,
        idxReg: Int,
        keyReg: Int,
        lenReg: Int,
        tmpReg: Int
    ) {
        impl.addInstruction(BuilderInstruction21c(Opcode.CONST_STRING, arrReg, ImmutableStringReference(encrypted)))
        impl.addInstruction(BuilderInstruction35c(Opcode.INVOKE_VIRTUAL, 1, arrReg, 0, 0, 0, 0, TO_CHAR_ARRAY))
        impl.addInstruction(BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, arrReg))
        impl.addInstruction(BuilderInstruction11n(Opcode.CONST_4, idxReg, 0))
        impl.addInstruction(BuilderInstruction12x(Opcode.ARRAY_LENGTH, lenReg, arrReg))
        val loopStartIndex = impl.instructions.size
        impl.addInstruction(BuilderInstruction10x(Opcode.NOP))
        impl.addInstruction(BuilderInstruction23x(Opcode.AGET_CHAR, tmpReg, arrReg, idxReg))
        impl.addInstruction(BuilderInstruction23x(Opcode.XOR_INT, tmpReg, tmpReg, keyReg))
        impl.addInstruction(BuilderInstruction12x(Opcode.INT_TO_CHAR, tmpReg, tmpReg))
        impl.addInstruction(BuilderInstruction23x(Opcode.APUT_CHAR, tmpReg, arrReg, idxReg))
        impl.addInstruction(BuilderInstruction22b(Opcode.ADD_INT_LIT8, idxReg, idxReg, 1))
        val loopStart = impl.newLabelForIndex(loopStartIndex)
        impl.addInstruction(BuilderInstruction10t(Opcode.GOTO, loopStart))
        val endIndex = impl.instructions.size
        impl.addInstruction(BuilderInstruction21c(Opcode.NEW_INSTANCE, outReg, ImmutableTypeReference(JAVA_STRING)))
        impl.addInstruction(BuilderInstruction35c(Opcode.INVOKE_DIRECT, 2, outReg, arrReg, 0, 0, 0, STRING_INIT))
        val loopEnd = impl.newLabelForIndex(endIndex)
        impl.replaceInstruction(loopStartIndex, BuilderInstruction22t(Opcode.IF_GE, idxReg, lenReg, loopEnd))
    }
}
