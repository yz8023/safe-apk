# ADFXCBNM 运行时保护模块（protect-module）

本目录是从 **ADFXCBNM Android Hardening Tool v9.4.27** 提取并整合进 FrostShell 的
运行时安全检测/保护层。它与 FrostShell 函数抽取壳分层工作，互不冲突：

| 层 | 负责 | 载体 |
|----|------|------|
| FrostShell | dex 函数抽取、native 回填、壳层反 dump/反调试/反 Frida/CRC 自校验 | `engine/ironshell.jar` + `engine/shell-files/` |
| ADFXCBNM | 26 项运行时环境检测（root/Magisk/Frida/Xposed/模拟器/注入/dump/代理…），检测到威胁即终止 | `protect-module/`（本目录） |

## 目录结构

```
protect-module/
├── dex/
│   └── security_check.dex          # 保护检测字节码（已编译，含 SecurityCheckProvider + S）
├── libs/
│   ├── arm64-v8a/libsecurity_check.so     # native 检测库（四 ABI，已编译）
│   ├── armeabi-v7a/libsecurity_check.so
│   ├── x86/libsecurity_check.so
│   └── x86_64/libsecurity_check.so
├── features.cfg.template           # assets/features.cfg 生成模板
├── exclude.rules                   # FrostShell -r 排除规则（方案 B 备用）
├── manifest_inject.py              # 纯 Python 二进制 Manifest 注入器
└── src/                            # 保护模块源码（参考/二次开发）
    ├── SecurityCheckProvider.java  # ContentProvider 入口：读 features.cfg、跑检测、触发终止
    ├── S_obfuscated.java           # 字符串混淆表（原 S.java，常量以字节数组存储）
    └── protection.cpp              # native 检测实现（23 项检测函数 + 自保护线程）
```

## 使用方式

二合一加固（推荐，一条命令）：

```bash
# FrostShell 函数抽取 + ADFXCBNM 运行时保护，自动 debug 签名
python3 ../../scripts/protect.py app.apk --plus

# 指定 keystore 与部分保护功能
python3 ../../scripts/protect_plus.py app.apk \
    --keystore my.jks --alias key0 --storepass 123456 \
    --features root_detect,frida_detect,xposed_detect
```

产物为 `out/xxx_plus_signed.apk`。

单独注入（对已加固产物追加保护层）：

```bash
python3 ../../scripts/merge_protection.py -f app_signed.apk -o out/ \
    --keystore my.jks --alias key0 --storepass 123456
```

## 功能清单（26 项）

- **加固(6)**：签名校验 `sig_verify`、防调试检测 `anti_debug`、防 Hook 检测 `anti_hook`、
  防注入保护 `anti_inject`、防内存 Dump `anti_dump`、防代理检测 `anti_proxy`
- **保护(20)**：完整性校验 `integrity`、运行时保护 `runtime_protect`、内存保护 `mem_protect`、
  网络安全 `net_secure`、ROOT 检测 `root_detect`、模拟器检测 `emu_detect`、
  Xposed 检测 `xposed_detect`、Frida 检测 `frida_detect`、Magisk 检测 `magisk_detect`、
  调试器检测 `debugger_detect`、代码注入检测 `code_inject`、速度检测 `speed_check`、
  多开检测 `multi_instance`、SSL 证书校验 `ssl_pinning`、数据防泄漏 `data_leak`、
  日志保护 `log_protect`、应用签名校验 `app_sig`、WebView 安全 `webview_secure`、
  文件访问控制 `file_control`、应用组件保护 `component_protect`、
  环境密钥检测 `env_testkeys`、SELinux 检测 `env_selinux`

## 运行机制

1. `manifest_inject.py` 向 `AndroidManifest.xml` 注入
   `com.adfxcbnm.protect.SecurityCheckProvider`（ContentProvider，`authorities=com.adfxcbnm.authority.<pkg>`）。
2. 应用启动时系统实例化该 Provider，其 `onCreate` 读取 `assets/features.cfg`，
   按启用项执行检测（native 优先，Java 兜底）。
3. 对 CRITICAL 项（root/magisk/xposed/frida/hook/debugger/inject/code_inject/runtime_protect）
   命中即 kill；native 层同步启动自保护线程轮询 tracer/Frida/Hook。

## 二次编译（可选）

预编译产物已可直接使用；如需修改源码重新构建：

```bash
# DEX：SecurityCheckProvider.java + S_obfuscated.java 用 d8/dx 编译
d8 --release --lib android.jar \
   src/SecurityCheckProvider.java src/S_obfuscated.java --output dex/security_check.dex

# SO：protection.cpp 用 NDK 编译（四种 ABI）
ndk-build 或 cmake -DANDROID_ABI=arm64-v8a ... 输出 libsecurity_check.so
```

## 致谢与许可

保护模块源码源自 ADFXCBNM Android Hardening Tool（v9.4.27，Telegram @ADFXCBNM），
按原项目构建配置（`apksig 8.5.0`、`minSdk 26`）打包；整合入 FrostShell 后遵循
FrostShell 的 MIT License。请仅在自有应用加固场景使用。
