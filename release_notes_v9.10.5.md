## v9.10.5 修复字符串加密提升 registerCount 后参数寄存器引用类型破坏（Undefined VerifyError）

### 问题现象
v9.10.4 修复跳转偏移后，加固产物仍启动闪退，崩溃堆栈更换：
```
java.lang.VerifyError: Verifier rejected class ...MainActivity:
void com.peonyking.gitapp.MainActivity.onBackPressed() failed to verify:
void com.peonyking.gitapp.MainActivity.onBackPressed(): [0x0] instance field access on
object that has non-reference type Undefined
```

### 根因
`FrostStringEncryptor.rewriteMethod` 将方法 `registerCount` 提升（baseRegs → baseRegs+2）以容纳临时寄存器，
但 **Dalvik 调用约定中参数寄存器锚定在寄存器区最高位**——registerCount 增加后参数寄存器整体上移 2 位，
而方法内指令（iget/iput/invoke 等）对参数寄存器（this/显式参数）的原始编号引用不会随之迁移。
ART verifier 在入口把最高位那几个寄存器标为参数类型，指令却读取原编号位置（现为无定义的 local 寄存器），
于是报 `instance field access on object that has non-reference type Undefined`（onBackPressed 首条 iget 即崩溃点 [0x0]）。

v9.10.4 只修了跳转偏移（target not at instruction start），类型流破坏仍存。

### 修复方案
- 在方法头部插入**参数搬移指令**：从寄存器数提升后的新参数区（src=baseRegs+4+pos）
  逐槽位搬回原参数区（dst=low+pos，this/对象用 MOVE_OBJECT_16，宽类型用 MOVE_WIDE_16，其余 MOVE_16）。
  既有指令对原参数寄存器的引用因此保持有效，无需逐指令重映射操作数。
- 临时寄存器 tmp/key 放置在参数区之上的新增空间（baseRegs+2 / baseRegs+3），不与任何既有引用冲突。
- 目标指令索引统一 +headShift（头部插入的参数搬移条数）。
- 寄存器上限检查改为 newRegCount = baseRegs + 参数槽数 + 4 ≤ 0xFFFF。

### 本地回归（javac+d8 构造样例 + dexlib2 双校验）
对每个样例方法同时校验：① 所有 offset 指令目标是否落在指令起始边界；② 寄存器类型流
（iget/iput 对象寄存器必须为引用类型，AGET 数组寄存器同理），等价 ART verifier 检查：
- 含分支/goto 的 onBackPressed 形状（iget-boolean this + iput-object + const-string）：0 错误
- packed-switch 方法：0 错误
- 多参数方法（long+int+String+Object + static int/long/String）：0 错误
（三组样例加密后 bad=0、typeBad=0；clinit 注入后同样全 0）

### 构建
- assembleDebug 通过，versionCode 47 / versionName 9.10.5
- aapt badging 校验 package='Forinxy.safe' versionCode='47' versionName='9.10.5'
- 产物 SHA-256: `c2080e0455df1e44c791e6fb2de6e5d7f76b2e677242f26f0bc5ecf01b0e3aa8`