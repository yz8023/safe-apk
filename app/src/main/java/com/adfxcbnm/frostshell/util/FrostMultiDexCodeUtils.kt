package com.adfxcbnm.frostshell.util

import com.adfxcbnm.frostshell.model.DexCode
import com.adfxcbnm.frostshell.model.Instruction
import com.adfxcbnm.frostshell.model.MultiDexCode
import java.io.IOException
import java.io.RandomAccessFile

object FrostMultiDexCodeUtils {
    fun makeMultiDexCode(multiDexInsns: Map<Int, List<Instruction>>): MultiDexCode {
        var fileOffset = 0
        val multiDexCode = MultiDexCode()
        multiDexCode.version = 2
        fileOffset += 2
        multiDexCode.dexCount = multiDexInsns.size.toShort()
        fileOffset += 2
        val dexCodeIndex = ArrayList<Int>()
        multiDexCode.dexCodesIndex = dexCodeIndex
        fileOffset += 4 * multiDexInsns.size
        val dexCodeList = ArrayList<DexCode>()
        val insnsIndexList = ArrayList<Int>()
        val iterator = multiDexInsns.entries.iterator()
        while (iterator.hasNext()) {
            val insns = iterator.next().value
            if (insns == null) continue
            dexCodeIndex.add(fileOffset)
            val dexCode = DexCode()
            // 指令池必须按 methodIndex 升序写入：壳 so 还原时按方法记录的 method_index
            // 直接索引池条目 vector（0xa0868 vector[methodIndex]），而非按池文件顺序消费。
            // 抽取阶段已保证每个 method_index 恰有一条（含 abstract/native 的 size=0 占位
            // 条目），此处排序后 vector[methodIndex] 即指向该方法的条目。
            val sortedInsns = insns.sortedBy { it.methodIndex }
            dexCode.methodCount = sortedInsns.size.toShort()
            dexCode.insns = sortedInsns.toMutableList()
            fileOffset += 2
            insnsIndexList.add(fileOffset)
            dexCode.insnsIndex = insnsIndexList
            for (ins in sortedInsns) {
                fileOffset += 4
                fileOffset += 4
                fileOffset += ins.instructionsData.size
            }
            dexCodeList.add(dexCode)
        }
        multiDexCode.dexCodes = dexCodeList
        return multiDexCode
    }

    fun writeMultiDexCode(out: String, multiDexCode: MultiDexCode) {
        val dexCodes = multiDexCode.dexCodes
        if (dexCodes == null || dexCodes.isEmpty()) {
            return
        }
        var randomAccessFile: RandomAccessFile? = null
        try {
            randomAccessFile = RandomAccessFile(out, "rw")
            randomAccessFile.write(FrostEndian.makeLittleEndian(multiDexCode.version))
            randomAccessFile.write(FrostEndian.makeLittleEndian(multiDexCode.dexCount))
            multiDexCode.dexCodesIndex?.forEach { dexCodesIndex ->
                randomAccessFile.write(FrostEndian.makeLittleEndian(dexCodesIndex))
            }
            for (dexCode in dexCodes) {
                val insns = dexCode.insns
                randomAccessFile.write(FrostEndian.makeLittleEndian(dexCode.methodCount ?: 0))
                if (insns != null) {
                    for (i in insns.indices) {
                        val instruction = insns[i]
                        randomAccessFile.write(FrostEndian.makeLittleEndian(instruction.methodIndex))
                        randomAccessFile.write(FrostEndian.makeLittleEndian(instruction.instructionDataSize))
                        randomAccessFile.write(instruction.instructionsData)
                    }
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
        } finally {
            FrostIoUtils.close(randomAccessFile)
        }
    }
}
