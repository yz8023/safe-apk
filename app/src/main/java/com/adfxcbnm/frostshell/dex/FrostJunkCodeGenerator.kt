package com.adfxcbnm.frostshell.dex

import com.adfxcbnm.frostshell.util.FrostLogUtils
import com.adfxcbnm.frostshell.util.FrostStringUtils
import com.android.dx.Code
import com.android.dx.DexMaker
import com.android.dx.Local
import com.android.dx.MethodId
import com.android.dx.TypeId
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths
import java.security.SecureRandom
import java.util.HashSet
import java.util.Locale

object FrostJunkCodeGenerator {
    private const val BASE_CLASS_NAME = "com/ironshell/core/junkcode/JunkClass"
    private const val MAX_GENERATE_COUNT = 100
    private val classNameSet = HashSet<String>()

    private fun insertSystemExit(code: Code, returnVoid: Boolean) {
        val systemType: TypeId<System> = TypeId.get(System::class.java)
        val exit: MethodId<System, Void> = systemType.getMethod(TypeId.VOID, "exit", TypeId.INT)
        val exitCode: Local<Int> = code.newLocal(TypeId.INT)
        code.loadConstant(exitCode, 0)
        code.invokeStatic(exit, null, exitCode)
        if (returnVoid) {
            code.returnVoid()
        }
    }

    private fun insertNullExceptionCode(code: Code) {
        val nullPointerExceptionTypeId: TypeId<NullPointerException> = TypeId.get(NullPointerException::class.java)
        val throwableLocal: Local<NullPointerException> = code.newLocal(nullPointerExceptionTypeId)
        val constructor: MethodId<NullPointerException, Void> = nullPointerExceptionTypeId.getConstructor()
        code.newInstance(throwableLocal, constructor)
        code.throwValue(throwableLocal)
    }

    private fun generateBaseClassName(): String = String.format(Locale.US, "L%s;", BASE_CLASS_NAME)

    private fun generateClassName(): String {
        val secureRandom = SecureRandom()
        val number = secureRandom.nextInt() % 1000
        return String.format(Locale.US, "L%s%d;", BASE_CLASS_NAME, number)
    }

    @Throws(IOException::class)
    fun generateJunkCodeDex(file: File) {
        val secureRandom = SecureRandom()
        val generateClassCount = secureRandom.nextInt(50) + 50
        val dexMaker = DexMaker()
        for (i in 0 until generateClassCount) {
            val className: String
            if (i == 0) {
                className = generateBaseClassName()
            } else {
                var candidate: String
                do {
                    candidate = generateClassName()
                } while (classNameSet.contains(candidate))
                classNameSet.add(candidate)
                className = candidate
            }
            val typeId = TypeId.get<Any>(className)
            dexMaker.declare(typeId, "", 1, TypeId.OBJECT)
            val clinitMethod = typeId.getMethod(TypeId.VOID, "<clinit>")
            val clinitCode = dexMaker.declare(clinitMethod, 8)
            insertSystemExit(clinitCode, false)
            val initMethod = typeId.getConstructor()
            val initCode = dexMaker.declare(initMethod, 1)
            insertSystemExit(initCode, true)
            val methodCount = secureRandom.nextInt(2) + 2
            for (j in 0 until methodCount) {
                val methodName = FrostStringUtils.generateIdentifier(3)
                val randomMethod = typeId.getMethod(TypeId.VOID, methodName)
                val randomMethodCode = dexMaker.declare(randomMethod, 1)
                if (j % 2 == 0) {
                    insertSystemExit(randomMethodCode, true)
                } else {
                    insertNullExceptionCode(randomMethodCode)
                }
            }
        }
        val generate = dexMaker.generate()
        Files.write(Paths.get(file.absolutePath), generate)
        FrostLogUtils.info("generated junk class count: %d", generateClassCount)
    }
}
