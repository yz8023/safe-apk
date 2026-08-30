# User Instruction Memory

This file records user instructions, preferences, and teachings for reference in future interactions.

## Format

### User Instruction Entry
User instruction entries should follow this format:

[User Instruction Summary]
- Date: [YYYY-MM-DD]
- Context: [Mentioned scenario or time]
- Instructions:
  - [Content of user teaching or instruction, described line by line]

### Project Knowledge Entry
Entries discovered by the Agent during task execution should follow this format:

[Project Knowledge Summary]
- Date: [YYYY-MM-DD]
- Context: Discovered by Agent while performing [specific task description]
- Category: [Operations & Deployment|Build Methods|Testing Methods|Troubleshooting & Debugging|Workflow & Collaboration|Environment Configuration]
- Instructions:
  - [Specific knowledge points, described line by line]

## Deduplication Strategy
- Before adding a new entry, check for similar or identical instructions.
- If a duplicate is found, skip the new entry or merge it with the existing one.
- When merging, update the context or date information.
- This helps avoid redundant entries and keeps the memory file tidy.

## Entries

[Project Knowledge Summary]
- Date: 2026-08-30
- Context: Discovered by Agent while performing FrostShell 与 ADFXCBNM 二合一加固合并任务的验证
- Category: Environment Configuration
- Instructions:
  - 本环境（Debian 12 bookworm）默认无 Java，apt 源为清华镜像（mirrors.tuna.tsinghua.edu.cn），用 `apt-get install -y openjdk-17-jdk-headless` 安装 JDK 17 比 Temurin 直链下载快且可靠（Temurin github 下载常被截断）。
  - FrostShell `protect.py --auto-setup` 内置的 Temurin JDK 21 下载在此网络环境易中断，改走 apt 更稳。

[Project Knowledge Summary]
- Date: 2026-08-30
- Context: Discovered by Agent while performing FrostShell 二合一加固 merge_protection.py 验证
- Category: Troubleshooting & Debugging
- Instructions:
  - FrostShell 引擎（ironshell.jar）在 zipalign 后内部 apksig 签名可能失败，导致 `*_signed.apk` 为 0 字节，但 `*_unsign.apk` 有效；合并流程已回退使用 unsign 产物（merge 阶段会重新签名）。
  - apksig 验证报 "JAR signature ... not supported on API Level(s) 9-17" 是无 minSdk 的测试 APK 的误报，需加 `--min-sdk-version 21` 再验证。

[Project Knowledge Summary]
- Date: 2026-08-30
- Context: Discovered by Agent while implementing merge_protection.py 的 ZipBuilder 手动重打包
- Category: Build Methods
- Instructions:
  - 手写 ZIP 时，DEFLATED 条目必须用裸 deflate 流（`zlib.compressobj(6, zlib.DEFLATED, -15)`），不能用 `zlib.compress()`（带 zlib 头 0x789c 会导致 apksig/zipfile 解压失败）。
  - ZipBuilder 写中央目录时必须递增 offset，否则 EOCD 的 cd_size 为 0 导致中央目录损坏。
