package com.adfxcbnm.frostshell.elf

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

class FrostReadElf @Throws(IOException::class) constructor(file: File) : AutoCloseable {
    private val mPath = file.path
    private val mFile = RandomAccessFile(file, "r")
    private val mBuffer = ByteArray(512)
    private var mEndian = 0
    private var mIsDynamic = false
    private var mIsPIE = false
    private var mType = 0
    private var mAddrSize = 0
    private var mSymTabOffset = 0L
    private var mSymTabSize = 0L
    private var mDynSymOffset = 0L
    private var mDynSymSize = 0L
    private var mShStrTabOffset = 0L
    private var mShStrTabSize = 0L
    private var mStrTabOffset = 0L
    private var mStrTabSize = 0L
    private var mDynStrOffset = 0L
    private var mDynStrSize = 0L
    private var mSymbols: Map<String, Symbol>? = null
    private var mDynamicSymbols: Map<String, Symbol>? = null
    private var mSectionHeaderList: MutableList<SectionHeader>? = null

    init {
        if (this.mFile.length() < 16L) {
            throw IllegalArgumentException("Too small to be an ELF file: " + file)
        }
        readHeader()
    }

    fun isDynamic(): Boolean = mIsDynamic

    fun getType(): Int = mType

    fun isPIE(): Boolean = mIsPIE

    override fun close() {
        try {
            this.mFile.close()
        } catch (e: IOException) {
        }
    }

    @Throws(IOException::class)
    private fun readHeader() {
        this.mFile.seek(0L)
        this.mFile.readFully(this.mBuffer, 0, 16)
        if (this.mBuffer[0] != ELFMAG[0] || this.mBuffer[1] != ELFMAG[1] || this.mBuffer[2] != ELFMAG[2] || this.mBuffer[3] != ELFMAG[3]) {
            throw IllegalArgumentException("Invalid ELF file: " + this.mPath)
        }
        val elfClass = this.mBuffer[4].toInt()
        if (elfClass == ELFCLASS32) {
            this.mAddrSize = 4
        } else if (elfClass == ELFCLASS64) {
            this.mAddrSize = 8
        } else {
            throw IOException("Invalid ELF EI_CLASS: $elfClass: ${this.mPath}")
        }
        this.mEndian = this.mBuffer[5].toInt()
        if (this.mEndian != ELFDATA2LSB) {
            if (this.mEndian == ELFDATA2MSB) {
                throw IOException("Unsupported ELFDATA2MSB file: ${this.mPath}")
            }
            throw IOException("Invalid ELF EI_DATA: ${this.mEndian}: ${this.mPath}")
        }
        this.mType = this.readHalf()
        val e_machine = this.readHalf()
        if (e_machine != EM_386 && e_machine != EM_X86_64 && e_machine != EM_AARCH64 && e_machine != EM_ARM && e_machine != EM_MIPS && e_machine != EM_QDSP6) {
            throw IOException("Invalid ELF e_machine: $e_machine: ${this.mPath}")
        }
        if ((e_machine == EM_386 && elfClass != ELFCLASS32) ||
            (e_machine == EM_X86_64 && elfClass != ELFCLASS64) ||
            (e_machine == EM_AARCH64 && elfClass != ELFCLASS64) ||
            (e_machine == EM_ARM && elfClass != ELFCLASS32) ||
            (e_machine == EM_MIPS && elfClass != ELFCLASS32) ||
            (e_machine == EM_QDSP6 && elfClass != ELFCLASS32)
        ) {
            throw IOException("Invalid e_machine/EI_CLASS ELF combination: $e_machine/$elfClass: ${this.mPath}")
        }
        val e_version = this.readWord()
        if (e_version != EV_CURRENT) {
            throw IOException("Invalid e_version: $e_version: ${this.mPath}")
        }
        this.readAddr()
        val ph_off = this.readOff()
        val sh_off = this.readOff()
        this.readWord()
        this.readHalf()
        val e_phentsize = this.readHalf()
        val e_phnum = this.readHalf()
        val e_shentsize = this.readHalf()
        val e_shnum = this.readHalf()
        val e_shstrndx = this.readHalf()
        this.readSectionHeaders(sh_off, e_shnum, e_shentsize, e_shstrndx)
        this.readProgramHeaders(ph_off, e_phnum, e_phentsize)
    }

    @Throws(IOException::class)
    private fun readSectionHeaders(sh_off: Long, e_shnum: Int, e_shentsize: Int, e_shstrndx: Int) {
        if (this.mSectionHeaderList == null) {
            this.mSectionHeaderList = ArrayList()
        }
        this.mFile.seek(sh_off + e_shstrndx.toLong() * e_shentsize.toLong())
        val sh_name = this.readWord()
        val sh_type = this.readWord()
        this.readX(this.mAddrSize)
        this.readAddr()
        val sh_offset = this.readOff()
        val sh_size = this.readX(this.mAddrSize)
        if (sh_type == SHT_STRTAB.toLong()) {
            this.mShStrTabOffset = sh_offset
            this.mShStrTabSize = sh_size
        }
        for (i in 0 until e_shnum) {
            if (i == e_shstrndx) continue
            this.mFile.seek(sh_off + i.toLong() * e_shentsize.toLong())
            val sh_name2 = this.readWord()
            val sh_type2 = this.readWord()
            val sh_flags2 = this.readX(this.mAddrSize)
            val sh_addr2 = this.readAddr()
            val sh_offset2 = this.readOff()
            val sh_size2 = this.readX(this.mAddrSize)
            val shName = this.readShStrTabEntry(sh_name2)
            this.mSectionHeaderList!!.add(SectionHeader(shName, sh_type2, sh_flags2, sh_addr2, sh_offset2, sh_size2))
            if (sh_type2 == SHT_SYMTAB.toLong() || sh_type2 == SHT_DYNSYM.toLong()) {
                if (".symtab" == shName) {
                    this.mSymTabOffset = sh_offset2
                    this.mSymTabSize = sh_size2
                    continue
                }
                if (".dynsym" != shName) continue
                this.mDynSymOffset = sh_offset2
                this.mDynSymSize = sh_size2
                continue
            }
            if (sh_type2 == SHT_STRTAB.toLong()) {
                if (".strtab" == shName) {
                    this.mStrTabOffset = sh_offset2
                    this.mStrTabSize = sh_size2
                    continue
                }
                if (".dynstr" != shName) continue
                this.mDynStrOffset = sh_offset2
                this.mDynStrSize = sh_size2
                continue
            }
            if (sh_type2 != SHT_DYNAMIC.toLong()) continue
            this.mIsDynamic = true
        }
    }

    @Throws(IOException::class)
    private fun readProgramHeaders(ph_off: Long, e_phnum: Int, e_phentsize: Int) {
        for (i in 0 until e_phnum) {
            this.mFile.seek(ph_off + i.toLong() * e_phentsize.toLong())
            val p_type = this.readWord()
            if (p_type != PT_LOAD) continue
            if (this.mAddrSize == 8) {
                this.readWord()
            }
            this.readOff()
            val p_vaddr = this.readAddr()
            if (p_vaddr != 0L) continue
            this.mIsPIE = true
        }
    }

    @Throws(IOException::class)
    private fun readSymbolTable(symStrOffset: Long, symStrSize: Long, tableOffset: Long, tableSize: Long): HashMap<String, Symbol> {
        val result = HashMap<String, Symbol>()
        this.mFile.seek(tableOffset)
        while (this.mFile.filePointer < tableOffset + tableSize) {
            val st_name = this.readWord()
            val st_value: Long
            val st_shndx: Int
            val st_info: Int
            if (this.mAddrSize == 8) {
                st_info = this.readByte()
                this.readByte()
                st_shndx = this.readHalf()
                st_value = this.readAddr()
                this.readX(this.mAddrSize)
            } else {
                st_value = this.readAddr()
                this.readWord()
                st_info = this.readByte()
                this.readByte()
                st_shndx = this.readHalf()
            }
            if (st_name == 0L) continue
            val symName = this.readStrTabEntry(symStrOffset, symStrSize, st_name) ?: continue
            val s = Symbol(symName, st_info, st_value, st_shndx)
            result[symName] = s
        }
        return result
    }

    @Throws(IOException::class)
    private fun readShStrTabEntry(strOffset: Long): String? {
        if (this.mShStrTabOffset == 0L || strOffset < 0L || strOffset >= this.mShStrTabSize) {
            return null
        }
        return this.readString(this.mShStrTabOffset + strOffset)
    }

    @Throws(IOException::class)
    private fun readStrTabEntry(tableOffset: Long, tableSize: Long, strOffset: Long): String? {
        if (tableOffset == 0L || strOffset < 0L || strOffset >= tableSize) {
            return null
        }
        return this.readString(tableOffset + strOffset)
    }

    @Throws(IOException::class)
    private fun readHalf(): Int = this.readX(2).toInt()

    @Throws(IOException::class)
    private fun readWord(): Long = this.readX(4)

    @Throws(IOException::class)
    private fun readOff(): Long = this.readX(this.mAddrSize)

    @Throws(IOException::class)
    private fun readAddr(): Long = this.readX(this.mAddrSize)

    @Throws(IOException::class)
    private fun readX(byteCount: Int): Long {
        this.mFile.readFully(this.mBuffer, 0, byteCount)
        var answer = 0
        if (this.mEndian == ELFDATA2LSB) {
            for (i in byteCount - 1 downTo 0) {
                answer = answer shl 8 or (this.mBuffer[i].toInt() and 0xFF)
            }
        } else {
            for (i in 0 until byteCount) {
                answer = answer shl 8 or (this.mBuffer[i].toInt() and 0xFF)
            }
        }
        return answer.toLong()
    }

    @Throws(IOException::class)
    private fun readString(offset: Long): String? {
        val originalOffset = this.mFile.filePointer
        this.mFile.seek(offset)
        this.mFile.readFully(this.mBuffer, 0, Math.min(this.mBuffer.size.toLong(), this.mFile.length() - offset).toInt())
        this.mFile.seek(originalOffset)
        for (i in this.mBuffer.indices) {
            if (this.mBuffer[i].toInt() != 0) continue
            return String(this.mBuffer, 0, i)
        }
        return null
    }

    @Throws(IOException::class)
    private fun readByte(): Int = this.mFile.read() and 0xFF

    fun getSymbol(name: String): Symbol? {
        if (this.mSymbols == null) {
            this.mSymbols = try {
                this.readSymbolTable(this.mStrTabOffset, this.mStrTabSize, this.mSymTabOffset, this.mSymTabSize)
            } catch (e: IOException) {
                return null
            }
        }
        return this.mSymbols!![name]
    }

    fun getDynamicSymbol(name: String): Symbol? {
        if (this.mDynamicSymbols == null) {
            this.mDynamicSymbols = try {
                this.readSymbolTable(this.mDynStrOffset, this.mDynStrSize, this.mDynSymOffset, this.mDynSymSize)
            } catch (e: IOException) {
                return null
            }
        }
        return this.mDynamicSymbols!![name]
    }

    fun getSectionHeaders(): List<SectionHeader>? = this.mSectionHeaderList

    class SectionHeader(
        private val sh_name: String?,
        private val sh_type: Long,
        private val sh_flags: Long,
        private val sh_addr: Long,
        private val sh_offset: Long,
        private val sh_size: Long
    ) {
        fun getName(): String? = sh_name
        fun getType(): Long = sh_type
        fun getFlags(): Long = sh_flags
        fun getAddr(): Long = sh_addr
        fun getOffset(): Long = sh_offset
        fun getSize(): Long = sh_size
    }

    class Symbol(
        val name: String,
        st_info: Int,
        val value: Long,
        val shndx: Int
    ) {
        val bind = st_info shr 4 and 0xF
        val type = st_info and 0xF

        override fun toString(): String {
            return "Symbol[$name,${toBind()},${toType()},$value,$shndx]"
        }

        private fun toBind(): String {
            return when (bind) {
                0 -> "LOCAL"
                1 -> "GLOBAL"
                2 -> "WEAK"
                else -> "STB_??? ($bind)"
            }
        }

        private fun toType(): String {
            return when (type) {
                0 -> "NOTYPE"
                1 -> "OBJECT"
                2 -> "FUNC"
                3 -> "SECTION"
                4 -> "FILE"
                5 -> "COMMON"
                6 -> "TLS"
                else -> "STT_??? ($type)"
            }
        }
    }

    companion object {
        private val ELFMAG = byteArrayOf(127, 69, 76, 70)
        private const val EI_NIDENT = 16
        private const val EI_CLASS = 4
        private const val EI_DATA = 5
        private const val EM_386 = 3
        private const val EM_MIPS = 8
        private const val EM_ARM = 40
        private const val EM_X86_64 = 62
        private const val EM_QDSP6 = 164
        private const val EM_AARCH64 = 183
        private const val ELFCLASS32 = 1
        private const val ELFCLASS64 = 2
        private const val ELFDATA2LSB = 1
        private const val ELFDATA2MSB = 2
        private const val EV_CURRENT = 1L
        private const val PT_LOAD = 1L
        private const val SHT_SYMTAB = 2
        private const val SHT_STRTAB = 3
        private const val SHT_DYNAMIC = 6
        private const val SHT_DYNSYM = 11

        @JvmStatic
        @Throws(IOException::class)
        fun read(file: File): FrostReadElf = FrostReadElf(file)
    }
}
