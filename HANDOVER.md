# HANDOVER · FrostShell-CLI（Android 加固工具）

> 交接对象：接手者
> 交接目标：30 分钟内从零构建并成功运行本工具，产出二合一加固 APK。
> 交接日期：2026-08-30

---

## 1. 项目概述

- **定位**：Android **函数抽取型加固**命令行工具（FrostShell）+ **ADFXCBNM 运行时保护** 二合一加固器。把 dex 方法字节码整体抽空、native 层运行时回填，并叠加 root/Magisk/Frida/Xposed/模拟器等 26 项运行时检测，一条命令输出加固并重签后的 APK，无需 Android SDK。
- **技术栈**：Python 3.11+（CLI/编排） + JDK 17+（加固引擎，Java） + Kotlin/Java + C++（保护模块源码）。引擎 `ironshell.jar` 自包含 `zipalign`/`apksig`/`dx`。
- **minSdk/targetSdk**：本工程是**桌面 CLI 加固器**，非 Android App，本身无 minSdk。其产出的保护模块要求宿主 App `minSdk >= 26`。
- **包名 / 应用名**：加固器命令行入口 `protect.py`；应用名 **FrostShell**。
- **commit 哈希**：
  - 初始导入（原始压缩包）：`b0a7aa8`（`Upload from Android`）
  - 二合一整合（本交接代码）：`ec69b22`（`feat: 整合 ADFXCBNM 运行时保护模块...`）
- **远程仓库**：`https://github.com/yz8023/safe-apk`（分支 `main`）
- **备用镜像（无法直连 GitHub 时）**：`https://ghproxy.com/https://github.com/yz8023/safe-apk`、`https://mirror.ghproxy.com/...`（见第 4 节代理列表）

---

## 2. 开发环境

| 项 | 要求 |
|----|------|
| OS | Linux（验证于 Debian 12 bookworm） / macOS / Windows |
| JDK | **17+**（推荐 21）。Debian: `apt-get install -y openjdk-17-jdk-headless` |
| Python | 3.7+（推荐 3.11） |
| Android SDK | **不需要**（zipalign/apksigner/dx 内置进 jar） |
| NDK | 仅二次编译保护模块 `protection.cpp` 时需要（可选） |
| 环境变量 | 无必须项。`JAVA_HOME` 可选 |
| 第三方 Key | 无（签名 keystore 由用户自备或脚本自动生成 debug 签名） |

> 已知坑：脚本 `--auto-setup` 内置的 Temurin JDK 21 直链下载在部分网络会截断，改用系统包管理器安装更稳（见第 13 节）。

---

## 3. 构建运行

### 从 clone 到出包（复制粘贴即可执行）

```bash
git clone https://github.com/yz8023/safe-apk
cd safe-apk/FrostShell-CLI

# 0) 环境检查（缺 JDK 会自动提示）
python3 scripts/protect.py --check

# 1) 构造测试 APK（无需 Android SDK）
python3 scripts/make_test_apk.py build/

# 2) 二合一加固（函数抽取 + 26 项运行时保护 + 自动 debug 签名）
python3 scripts/protect.py build/test_app.apk --plus -o out

# 产物: out/test_app_signed_plus_signed.apk
# 验签:
java -cp engine/ironshell.jar com.android.apksigner.ApkSignerTool verify \
    --min-sdk-version 21 out/*_plus_signed.apk
```

### 用自有签名

```bash
keytool -genkeypair -v -keystore my.jks -alias key0 \
  -keyalg RSA -keysize 2048 -validity 10950 \
  -storepass android -keypass android -dname "CN=Me"

python3 scripts/protect.py build/test_app.apk --plus \
    --keystore my.jks --alias key0 --storepass android
```

### GitHub Actions 构建

- **脚本路径**：`.github/workflows/build-apk.yml`
- **触发方式**：push 到 `main`/`master`、PR 到上述分支、或仓库 Actions 页手动 `Run workflow`
- **内容**：checkout → setup JDK 17 → setup Python 3.11 → `--check` → 构造测试 APK → 二合一加固 → apksig 验签 → 上传 `frostshell-plus-apk` artifact

---

## 4. 依赖与镜像

- **主要依赖**：无第三方 Maven 依赖。引擎 jar 自包含；唯一外部下载是 **Temurin JDK**（`--auto-setup` 场景）。
- **获取优先级**：本地缓存 → 国内镜像 → 官方源 → 手动下载。
- **自动配置镜像**（如需接入 Gradle/远端依赖）：

| 官方源 | 镜像地址 |
|--------|----------|
| Google Maven | `https://maven.aliyun.com/repository/google` |
| JCenter | `https://maven.aliyun.com/repository/jcenter` |
| Maven Central | `https://maven.aliyun.com/repository/central` |
| Gradle 发行版 | `https://mirrors.cloud.tencent.com/gradle/` |

- **GitHub 下载失败**：依次尝试代理：`ghproxy.com`、`mirror.ghproxy.com`、`gh-proxy.com`、`ghfast.top`、`ghps.cc`、`hub.gitmirror.com`、`mirror.ghproxy.com/https://github.com`、`ghproxy.net`、`gh.ddlc.top`、`github.moeyy.xyz`、`gh.api.99988866.xyz`、`gitclone.com/github.com`
- **需手动下载的**：Temurin JDK 21（Linux x64）：`https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.5%2B11/OpenJDK21U-jdk_x64_linux_hotspot_21.0.5_11.tar.gz`，解压后 `--local-jdk <目录>` 指定。

---

## 5. 本地依赖服务

**无**。本工具为纯本地命令行运行，不需要后端 / 数据库 / Redis 等外部服务。CI（GitHub Actions）与本地行为一致。

---

## 6. 开发进度

- **已完成**
  - FrostShell 函数抽取壳引擎（`engine/ironshell.jar`，v2.20.0）
  - ADFXCBNM 运行时保护模块资产整合（`engine/protect-module/`，v9.4.27）：`security_check.dex`、4 ABI `libsecurity_check.so`、源码参考
  - 纯 Python 二进制 Manifest 注入器 `manifest_inject.py`（移植 AndroidManifestModifier.kt）
  - 保护模块注入器 `merge_protection.py`（Manifest/DEX/SO/features.cfg + 重打包 + 重签）
  - 二合一入口 `protect_plus.py`；`protect.py --plus` 一键转交
  - 复用 jar 内置 apksig 的重签助手 `SignerHelper.java`
  - GitHub Actions 构建脚本
  - 端到端验证通过（Manifest/DEX/SO/features.cfg/签名 v1+v2+v3 全绿）
- **进行中**：无
- **已搁置**：无需 Android SDK 的前提下，将保护模块源码（Kotlin/C++）接入本仓库直接编译出 dex/so 的流水线（当前使用预编译产物）
- **最近可运行 commit**：`ec69b22`

---

## 7. 待开发内容

| 功能 | 优先级 | 预估工时 | 前置依赖 |
|------|--------|----------|----------|
| 保护模块源码就地编译（d8/NDK 构建 dex/so） | P1 | 1-2 天 | 接入 android.jar 与 NDK toolchain |
| `protect.py --plus` 支持 AAB 输入 | P2 | 0.5 天 | 引擎 AAB 支持（参考 -f 处理） |
| features.cfg 按功能粒度注入（--features 与壳类排除联动） | P2 | 0.5 天 | 现有 --features 参数 |
| Windows 一键脚本（.bat） | P3 | 0.5 天 | 无 |
| CI 增加签名密钥注入（secrets 加固正式包） | P3 | 1 天 | 仓库 secrets |

---

## 8. 架构与关键模块

```
FrostShell-CLI/
├── engine/
│   ├── ironshell.jar                  # 加固引擎（自包含 zipalign/apksig/dx，命令行调 java -jar）
│   ├── shell-files/                   # 壳运行时资产（各 ABI .so + proxy dex + 密钥）
│   ├── protect-module/                # ★ ADFXCBNM 运行时保护层
│   │   ├── dex/security_check.dex     #   检测字节码
│   │   ├── libs/{abi}/libsecurity_check.so
│   │   ├── manifest_inject.py         #   纯 Python 二进制 Manifest 注入器
│   │   ├── features.cfg.template
│   │   ├── exclude.rules
│   │   └── src/                       #   保护模块源码（SecurityCheckProvider.java / protection.cpp / S_obfuscated.java）
│   ├── protect-config-template.json
│   └── exclude-classes-template.rules
└── scripts/
    ├── protect.py                     # 快捷加固（--plus 转交 protect_plus.py）
    ├── protect_plus.py                # ★ 二合一入口（阶段一函数抽取 → 阶段二保护注入）
    ├── merge_protection.py            # ★ 保护模块注入器（重打包 + 重签 + 校验）
    ├── SignerHelper.java              # 重签助手（复用 jar 内 apksig）
    └── make_test_apk.py               # 测试 APK 构造（无 SDK）
```

**核心入口 / 调用链**：
1. `protect.py` →（`--plus`）→ `protect_plus.py`
2. `protect_plus.py`：阶段一 `java -jar ironshell.jar -f <apk> -c cfg -o out` → 阶段二 `merge_protection.inject(...)`
3. `merge_protection.py`：读 APK 条目 → `manifest_inject.modify_manifest()` 注入 provider → 追加 `classes<N>.dex` → 注入 4 ABI SO（4096 对齐）→ 写 `assets/features.cfg`（含签名 SHA-256 基线 + dex CRC 基线）→ 手工 zip 重打包（`ZipBuilder`）→ `SignerHelper`（apksig v1+v2+v3）重签 → `verify()` 自检

**前后端对接点**：无前后端，纯 CLI + jar 引擎。
**已知技术债**：
- 重打包用自研 `ZipBuilder`，虽已验证，但极端 APK（非常规压缩、多 comment）未全覆盖
- 保护模块以预编译产物交付，源码侧未接构建链
- 脚本中 JDK 自动下载 URL 固定版本，需随 Temurin 发布维护

---

## 9. 代码概览与已知问题

- **结构简述**：见第 8 节树。核心逻辑集中在 `scripts/protect_plus.py`（编排）、`scripts/merge_protection.py`（注入/重打包/重签/校验）、`engine/protect-module/manifest_inject.py`（二进制 XML 解析/重写）。
- **致命/严重问题**：无已知致命问题。
- **注意点（非致命）**：
  1. 引擎内部 apksig 签名对个别 APK（无 minSdk 等）会报 `not supported on API Level(s) 9-17`，导致 `*_signed.apk` 为 0 字节 —— `protect_plus.py` 已回退使用 `*_unsign.apk`（merge 阶段会统一重签），不影响最终产物。
  2. `apksigner verify` 无 minSdk 参数时会按 API 9-17 校验报错，属误报，加 `--min-sdk-version 21` 即可（README/CI 已处理）。

---

## 10. 架构简评

- **架构模式**：面向过程的命令行工具 + 黑盒引擎 jar，模块按职责拆分为「编排 / 注入 / 签名 / 校验」四段。
- **整体评价**：良好。分层清晰（壳 vs 运行时保护），注入与重打包逻辑自洽且已端到端验证。
- **突出问题与改进方向**：
  1. 自研 `ZipBuilder` 与引擎内置 zipalign 功能重叠，长期应统一到单一可靠实现（或直接调 jar 内 zipalign）。
  2. 保护模块源码与产物分离，建议把 d8/NDK 构建纳入仓库，保证可追溯、可升级。
  3. 常量与路径散落脚本各处，建议引入统一配置（如 `protect-module/defs.py`）。
  4. JDK 自动下载固定版本，建议改为动态解析 Temurin API。
  5. 缺少自动化单元测试（至少为 `manifest_inject` / `ZipBuilder` 补 pytest）。

---

## 11. 测试建议

- **核心流程可走通**：已验证。`make_test_apk.py` → `protect.py --plus` → `_plus_signed.apk`，apksig v1+v2+v3 通过。
- **重点测试模块/场景**：
  - `manifest_inject.py`：对真实 App 的二进制 Manifest 注入/重写（重点：字符串池 UTF-8/UTF-16、多命名空间、resource_id）
  - `merge_protection.py`：多 dex、已存在同名 SO、非 4 对齐输入
  - `--features` 部分功能注入
  - 真实 App（minSdk>=26）在模拟器/真机启动到首页、root/Frida 环境触发检测
- **已知/可能边界问题**：
  - 超大 APK（>100MB）重打包性能
  - 带 APK Signature 扩展（v3 多 signer、SourceStamp）的输入
  - 壳引擎对部分反射密集类不兼容（需 `-r` 排除）

---

## 12. 账号与密钥

- **功能依赖**：唯一需要的是**签名 keystore**（自备或用脚本生成的 debug 签名）。
- **申请方式**：keystore 由用户自行生成（`keytool`，见第 3 节），不入库、不入源码包。CI 场景可将 keystore 与密码放 GitHub Secrets（`KEYSTORE`/`KEYSTORE_PASS` 占位）。
- **第三方 API Key**：无。

---

## 13. 常见问题

| 报错 / 现象 | 解法 |
|------------|------|
| `java: command not found` | 安装 JDK 17+；Debian: `apt-get install -y openjdk-17-jdk-headless`；或 `--local-jdk <目录>` |
| `--auto-setup` 下载 JDK 截断失败 | 用系统包管理器装 JDK，或手动下载 Temurin 后 `--local-jdk`（见第 4 节） |
| `JAR signature ... not supported on API Level(s) 9-17` | apksig 校验误报，验签时加 `--min-sdk-version 21` |
| 引擎 `*_signed.apk` 为 0 字节 | 属引擎内部签名失败；`protect_plus.py` 自动回退 unsign 产物，最终 `_plus_signed.apk` 正常 |
| `zipfile.BadZipFile` / 重打包后 ZIP 损坏 | 属历史 bug（ZipBuilder 中央目录 offset / deflate 头），当前版本已修复，如再遇请反馈 commit `ec69b22` |
| 加固后装不上/秒退 | 多为 minSdk 或反射类不兼容；用 `-r exclude.rules` 排除，或 `--debug --noisy-log` 看 logcat |
| 依赖下载失败 | 按第 4 节优先级：本地缓存 → 国内镜像 → 官方源 → 代理列表 → 手动下载 |

---

## 14. 验收标准

1. **本地**：按第 3 节命令从 clone 到产出 `out/*_plus_signed.apk`，`apksigner verify --min-sdk-version 21` 通过 → 接管成功。
2. **CI**：push 到 `main` 后 `.github/workflows/build-apk.yml` 自动运行，Artifacts 中出现 `frostshell-plus-apk`（内含 `*_plus_signed.apk`）→ 视为自动出包成功。
3. **产物**：源码包 `FrostShell_source.zip`、演示 `FrostShell_v2.20.0_debug.apk` 随仓库发行。

---

*本交接文档基于 commit `ec69b22` 生成；远程仓库 `https://github.com/yz8023/safe-apk`。*
