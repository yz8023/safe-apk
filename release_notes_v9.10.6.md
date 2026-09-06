## v9.10.6 注入崩溃日志采集：native signal handler 捕获 SIGSEGV + Java 层增强日志

### 问题背景
palmPC 在加固选项基本全选后启动闪退，系统 tombstone 仅显示：
```
signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0
Cause: null pointer dereference
pc 0, lr 0
backtrace: #00 pc 0 <unknown>
```
`pc=0 lr=0` 为 native 空函数指针调用崩溃，发生在保护 native 层，
Java 层 `Thread.setDefaultUncaughtExceptionHandler` 无法捕获，无法定位真实崩溃点。

### 修复方案
在注入的 `libsecurity_check.so` 与 `security_check.dex` 中加入崩溃日志采集：

**native 层（protection.cpp）**
- 新增 `Java_com_adfxcbnm_protect_SecurityCheckProvider_nativeInstallCrashHandler(String path)`：
  注册 SIGSEGV/SIGABRT/SIGBUS/SIGILL/SIGFPE/SIGTRAP 的 signal handler（SA_SIGINFO）
- `crashHandler`：async-signal-safe 方式（open/write/close）记录崩溃现场到日志文件：
  - 时间戳、pid/tid、signal 名称
  - pc/lr/sp/fault address（ucontext，arm64/arm32 双架构）
  - `_Unwind_Backtrace` 栈回溯（最多 32 帧）
  - 同步输出 logcat（tag `ADFXCBNM_CRASH`）
  - 记录后恢复默认 handler 并 `raise` 重放信号，保留系统 tombstone

**Java 层（SecurityCheckProvider.java）**
- `onCreate` 中 `loadNativeLib` 后立即调用 `installNativeCrashHandler()`，
  优先写入 `getExternalFilesDir`（/sdcard/Android/data/<pkg>/files/adfxcbnm_crash.log，无需权限可提取），
  内部 filesDir 也有副本
- `writeCrashLog` 增强：追加 pkg/pid/tid/nativeOk/features 列表上下文，
  日志同时写入外部目录 + 内部目录 + logcat
- `runAllChecks` 增加 `checks:start` / `checks:done` 阶段 marker，
  配合 native crash log 可区分崩溃发生在哪个检测阶段

### 日志提取方式
崩溃后从手机获取 `adfxcbnm_crash.log`（两部路径）：
1. `/sdcard/Android/data/<应用包名>/files/adfxcbnm_crash.log`（文件管理器/USB 直取）
2. logcat 过滤 `ADFXCBNM_CRASH` tag

### 本地回归
- 4 ABI so 编译通过（NDK 25.1，llvm-nm 校验 4 个 SecurityCheckProvider JNI 导出符号齐全）
- security_check.dex 用 d8 重新编译（含 SecurityCheckProvider + S，38684 字节），
  dexdump 校验 nativeInstallCrashHandler/nativeSelfProtect/nativeKill/nativeCheckThreat 声明齐全
- 注入回归：gitapp_test.apk 叠加注入 classes2.dex + lib/arm64-v8a/libsecurity_check.so + features.cfg，
  apksigner 签名后资源与资产 SHA-256 完全一致，v1+v2 签名验证通过
- assembleDebug 通过

### 构建
- versionCode 48 / versionName 9.10.6
- 产物 SHA-256: `5a796ad2413e45e75c03b8ba6e2de50839aa6e14c18b16ce064d1c2aaf41265f`
