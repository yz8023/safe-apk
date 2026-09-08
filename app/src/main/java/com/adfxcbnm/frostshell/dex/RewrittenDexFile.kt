package com.adfxcbnm.frostshell.dex

import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.base.reference.BaseTypeReference
import com.android.tools.smali.dexlib2.iface.Annotation
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.DexFile
import com.android.tools.smali.dexlib2.iface.Field
import com.android.tools.smali.dexlib2.iface.Method
import java.util.LinkedHashSet

/**
 * 委托式 ClassDef：直接透传原类元数据，仅替换方法/字段/annotation 集合；
 * 不调用 ImmutableClassDef（其构造会对全部方法 TreeSet 排序并调用 toString，大 dex 会 OOM）。
 * 必须继承 BaseTypeReference——DexPool 写入时对 MethodReference 做 CharSequence 校验链。
 */
class RewrittenClassDef(
    private val source: ClassDef,
    private val directMethods: List<Method>,
    private val virtualMethods: Iterable<Method>,
    private val staticFields: Iterable<Field>? = null,
    private val instanceFields: Iterable<Field>? = null,
    private val classAnnotations: Set<Annotation>? = null
) : BaseTypeReference(), ClassDef {
    override fun validateReference() {
        source.validateReference()
    }

    override fun getType(): String = source.type

    override fun getAccessFlags(): Int = source.accessFlags

    override fun getSuperclass(): String? = source.superclass

    override fun getInterfaces(): List<String> = source.interfaces

    override fun getSourceFile(): String? = source.sourceFile

    override fun getAnnotations(): Set<Annotation> = classAnnotations ?: source.annotations

    override fun getStaticFields(): Iterable<Field> = staticFields ?: source.staticFields

    override fun getInstanceFields(): Iterable<Field> = instanceFields ?: source.instanceFields

    override fun getFields(): Iterable<Field> = Iterable {
        object : Iterator<Field> {
            private val s = (staticFields ?: source.staticFields).iterator()
            private val i = (instanceFields ?: source.instanceFields).iterator()
            override fun hasNext(): Boolean = s.hasNext() || i.hasNext()
            override fun next(): Field = if (s.hasNext()) s.next() else i.next()
        }
    }

    override fun getDirectMethods(): Iterable<Method> = directMethods

    override fun getVirtualMethods(): Iterable<Method> = virtualMethods

    override fun getMethods(): Iterable<Method> = Iterable {
        object : Iterator<Method> {
            private val direct = directMethods.iterator()
            private val virtual = virtualMethods.iterator()
            override fun hasNext(): Boolean = direct.hasNext() || virtual.hasNext()
            override fun next(): Method = if (direct.hasNext()) direct.next() else virtual.next()
        }
    }
}

/**
 * 委托式 DexFile：class 集合用 LinkedHashSet 保持顺序，交由 DexPool 写入，
 * 避免 ImmutableDexFile 内部对全部类再做一次 immutable 化与临时字符串分配（大 dex OOM）。
 */
class RewrittenDexFile(
    private val opcodes: Opcodes,
    classes: List<ClassDef>
) : DexFile {
    private val classSet: Set<ClassDef> = LinkedHashSet(classes)

    override fun getClasses(): Set<ClassDef> = classSet

    override fun getOpcodes(): Opcodes = opcodes
}
