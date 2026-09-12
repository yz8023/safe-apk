package com.adfxcbnm.frostshell.util

import com.adfxcbnm.frostshell.model.DexCode
import com.adfxcbnm.frostshell.model.Instruction
import com.adfxcbnm.frostshell.model.MultiDexCode
import java.io.IOException
import java.io.RandomAccessFile

object FrostMultiDexCodeUtils {
    /**
     * 构建多 dex 指令池。块索引与 dex 序号严格对齐：
     * 壳 so 按池 = dexCodesIndex[i]（i=池内块序）建立 vector[i]，再以运行时从 dex 标识
     * 解析出的序号 w21 定位 vector。因此必须保证「池内第 k 块 == dexNo k 的方法」，
     * 任何 dexNo 缺失（抽取失败/并发丢失）都会让该 dexNo 之后的所有 dex 指令整体错位
     * （整类全部方法 VerifyError: invalid argument count exceeds outsSize）。
     * 修复：按 0..maxDexNo 遍历并把缺失 dexNo 写成空块（methodCount=0）占位，
     * dexCount = maxDexNo+1，而不是依赖尚不稳定的冒泡映射 size。
     */
    fun makeMultiDexCode(multiDexInsns: Map<Int, List<Instruction>>): MultiDexCode {
        var fileOffset = 0
        val multiDexCode = MultiDexCode()
        multiDexCode.version = 2
        fileOffset += 2
        val maxDexNo = multiDexInsns.keys.maxOrNull() ?: -1
        val dexCount = maxDexNo + 1
        multiDexCode.dexCount = dexCount.toShort()
        fileOffset += 2
        val dexCodeIndex = ArrayList<Int>()
        multiDexCode.dexCodesIndex = dexCodeIndex
        fileOffset += 4 * dexCount
        val dexCodeList = ArrayList<DexCode>()
        for (dexNo in 0 until dexCount) {
            dexCodeIndex.add(fileOffset)
            val dexCode = DexCode()
            val insns = multiDexInsns[dexNo]
            if (insns == null || insns.isEmpty()) {
                dexCode.methodCount = 0
                dexCode.insns = mutableListOf()
                fileOffset += 2
                dexCodeList.add(dexCode)
                continue
            }
            // 指令池必须按 methodIndex 升序写入：壳 so 还原时按方法记录的 method_index
            // 直接索引池条目 vector（0xa0868 vector[methodIndex]），而非按池文件顺序消费。
            // 抽取阶段已保证每个 method_index 恰有一条（含 abstract/native 的 size=0 占位
            // 条目），此处排序后 vector[methodIndex] 即指向该方法的条目。
            val sortedInsns = insns.sortedBy { it.methodIndex }
            dexCode.methodCount = sortedInsns.size.toShort()
            dexCode.insns = sortedInsns.toMutableList()
            fileOffset += 2
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
