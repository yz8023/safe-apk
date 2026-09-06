# v9.10.7 发布说明

## 修复：勾选 FrostShell 加固后必闪退且无崩溃日志

### 问题根因

FrostShell 壳 + ADFXCBNM 叠加层双保护体系冲突：

1. FrostShell 壳的 native 库 `libvenSec.so` 内置 **ByteHook**（GOT/PLT hook），运行时对 `open/read/write/mmap/mprotect/dlopen/dlsym/kill` 等 libc 函数做保护性改写。
2. 叠加层注入的 `SecurityCheckProvider` 的 `anti_hook`/`anti_inject`/`runtime_protect`/`code_inject` 检测到进程被 hook，命中 **CRITICAL 集合**。
3. `enforce()` → `triggerKill()` → `nativeKill()` → `kill(getpid(), SIGKILL)`。
4. **SIGKILL 无法被 crash handler 捕获**（只注册了 SIGSEGV/ABRT/BUS/ILL/FPE/TRAP），所以闪退时没有任何崩溃日志、没有 tombstone。

### 修复方案（兼顾强度与兼容性）

**不关闭检测，而是让检测能区分"壳自身防护"与"攻击者的 hook"：**

- **Native 层**（protection.cpp）：
  - `isFunctionHooked()` 现在解码 ARM64 B/BL 跳转指令的**目标地址**，通过 `/proc/self/maps` 判断目标落在哪个库：
    - 目标在 `libc/libart/libvenSec/libsecurity_check` 等可信库内 → 壳/系统正常行为，放行
    - 目标在 frida/xposed/sandhook 等攻击库或匿名内存 → 判定为攻击，保留 kill
  - `detectLibcHook()` 在壳共存时豁免（ByteHook 改写 `/proc/self/status` 读取路径导致误判）

- **Java 层**（SecurityCheckProvider）：
  - 新增 `isShellCoexist()`：检测进程 maps 中已加载 `libvenSec`/`libbytehook`（壳标志）
  - 壳共存时，`hook`/`inject`/`code_inject`/`runtime_protect` 的 CRITICAL 杀进程豁免
  - **frida/xposed/root/magisk/debugger 等独立检测完全保留**，攻击者仍会被检测并拦截

### 附带修复（继承自 v9.10.6）

- SO 覆盖注入：二次加固时无条件覆盖注入最新版 `libsecurity_check.so`
- Manifest provider 去重：二次叠加不再产生重复 SecurityCheckProvider

### 变更文件

- `FrostShell-CLI/engine/protect-module/src/protection.cpp`
- `FrostShell-CLI/engine/protect-module/src/SecurityCheckProvider.java`
- `FrostShell-CLI/engine/protect-module/src/S_obfuscated.java`
- `app/src/main/assets/lib/{arm64-v8a,armeabi-v7a,x86,x86_64}/libsecurity_check.so`
- `app/src/main/assets/security_check.dex`
- `app/build.gradle.kts`（versionCode 49 / versionName 9.10.7）
