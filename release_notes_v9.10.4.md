## v9.10.4 修复 dex 改写 pass 跳转目标错位导致的 ART VerifyError

### 问题现象
加固后 App 启动闪退，崩溃堆栈：
```
java.lang.VerifyError: Verifier rejected class ...MainActivity:
void com.peonyking.gitapp.MainActivity.onBackPressed():
[0x15] target dex pc 0x28 is not at instruction start
```
流程：Class.newInstance → AppComponentFactory.instantiateActivity → ProxyComponentFactory → Instrumentation.newActivity。

### 根因
dex 改写 pass 在重建方法体时，把 DexBacked 指令（保留原始 codeOffset）与新建指令混拼进 ImmutableMethodImplementation：
- `FrostStringEncryptor.rewriteMethod`：对命中的 const-string 插入 6 条解密调用指令，其余指令原样保留
- `FrostReflectionClinitInjector.injectHelperCall`：在 return-void 前插入 invoke helper

方法指令流被插入新指令后整体右移，但 offset 指令（goto/if/switch）仍引用原始 codeOffset，
导致跳转目标落在指令中间，ART verifier 拒绝加载该类。

### 修复方案
两个 pass 均改用 `MutableMethodImplementation(MethodImplementation)` 复制方法体：
构造函数会把所有 offset 指令经 codeAddress→index 映射转为 label 式 builder 指令，
插入/替换后由内部 fixInstructions 统一重算全部跳转偏移。
- FrostStringEncryptor：改写命中点前，将 idx 处 const-string 替换为密文常量（21c/31c），
  并以 (idx+1) 为锚点倒序插入 const/16 + invoke-static/range + move-result-object + move-object/16；
  因 Mutable 的 registerCount 为 private final，用 MethodImplementation facade 覆盖
  getRegisterCount() 提升 2 个临时寄存器。
- FrostReflectionClinitInjector：直接在 return-void 前 addInstruction 插入 invoke 即可。

### 本地回归（独立 driver 直调两个 pass）
构造含分支/goto 的 onBackPressed 方法 + for 循环 clinit + packed-switch 方法的样例 dex，
用 dexlib2 逐个校验所有 offset 指令目标是否落在指令起始边界（等同于 verifier 检查）：
- baseline bad=0（原始 dex 合法）
- 字符串加密 7 处（含 switch 方法）后 bad=0
- clinit 注入后 bad=0

### 构建
- assembleDebug 通过，versionCode 46 / versionName 9.10.4
- aapt badging 校验 package='Forinxy.safe' versionCode='46' versionName='9.10.4'
- 产物 SHA-256: `947486ba30b261b46349cd728ccfd21546b7ac7184896e76ee234f7cc5b1fe8a`