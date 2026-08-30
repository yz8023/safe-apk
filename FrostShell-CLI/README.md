# FrostShell CLI 🧊

> Android **函数抽取型加固**的命令行版本 —— 一条命令加固 APK / AAB，无需 Android SDK。
>
> 基于 [dpt-shell](https://github.com/luoyesiqiu/dpt-shell) 深度定制。图形化 App 前端见完整版发布包。
>
> 已整合 **ADFXCBNM Android Hardening Tool v9.4.27** 的运行时保护模块，支持
> **二合一加固**：函数抽取壳 + 26 项运行时检测，一层命令搞定。

---

## 这是什么

FrostShell 把 dex 文件里方法的字节码整体抽空，运行时再由 native 层按需把方法体填回，对抗静态反编译。本 CLI 包含独立加固引擎与一键脚本，适合在电脑上批量加固或集成进 CI。

```
FrostShell-CLI/
├── README.md              # 本文件
├── LICENSE
├── engine/                # 加固引擎
│   ├── ironshell.jar      #   自包含（内置 zipalign/apksigner，无需 Android SDK）
│   ├── shell-files/       #   壳运行时资产（各架构 .so + proxy dex + 密钥）
│   ├── protect-module/    #   ★ ADFXCBNM 运行时保护模块（dex + so + 注入器 + 源码）
│   ├── protect-config-template.json
│   ├── exclude-classes-template.rules
│   └── README.md          #   ← jar 命令行调用完整文档
└── scripts/
    ├── protect.py         # 快捷加固脚本（自动检测环境，缺失可自动下载 JDK）
    ├── protect_plus.py    # ★ 二合一加固脚本（函数抽取 + 运行时保护）
    ├── merge_protection.py# ★ ADFXCBNM 保护模块注入器
    └── SignerHelper.java  # ★ 复用 jar 内 apksig 的签名助手
```

---

## 环境要求

- **JDK 17 或更高**（推荐 21）。`java -version` 应显示 17+。
- **Python 3.7+**（仅 `protect.py` 需要；直接调 jar 则不需要）。
- 不需要 Android SDK：`zipalign` 与 `apksigner` 已内置进 jar。

---

## 快速开始

### 方式 A：一键脚本（推荐）

```bash
# 最简单：自动生成 debug 签名
python3 scripts/protect.py your-app.apk

# 用你自己的签名
python3 scripts/protect.py your-app.apk \
    --keystore my.jks --alias key0 --storepass 123456

# 只检测环境
python3 scripts/protect.py --check

# 环境不全时自动下载 JDK
python3 scripts/protect.py your-app.apk --auto-setup

# 透传额外参数给引擎（如开启签名校验、关闭 Frida 检测）
python3 scripts/protect.py your-app.apk -vs --disable-frida-detect
```

产物默认输出到 `frost-out/xxx_signed.apk`。

### 方式 B：直接调 jar

```bash
cd engine
cp protect-config-template.json protect-config.json
# 编辑 protect-config.json 填入你的 keystore 信息
java -jar ironshell.jar -f /path/to/your-app.apk -c protect-config.json -o ./out
```

完整参数、配置格式与 FAQ 见 [`engine/README.md`](engine/README.md)。

---

## 二合一加固（函数抽取 + 运行时保护）

FrostShell 已整合 **ADFXCBNM Android Hardening Tool v9.4.27** 的运行时保护模块：
在函数抽取壳之上再叠加 26 项运行时环境检测（root/Magisk/Frida/Xposed/模拟器/注入/dump/代理等），
命中即终止。两层保护分工如下：

| 层 | 负责 | 说明 |
|----|------|------|
| FrostShell | 静态对抗 | dex 函数抽取、native 回填、壳层反 dump/反调试 |
| ADFXCBNM | 运行期环境检测 | `SecurityCheckProvider` + `libsecurity_check.so` + `features.cfg` |

### 方式 A：一键二合一（推荐）

```bash
# 函数抽取 + 全部 26 项运行时保护，自动 debug 签名
python3 scripts/protect.py your-app.apk --plus

# 指定 keystore 与部分保护功能
python3 scripts/protect_plus.py your-app.apk \
    --keystore my.jks --alias key0 --storepass 123456 \
    --features root_detect,frida_detect,xposed_detect
```

产物为 `frost-out/xxx_plus_signed.apk`（最终签名版本）。

### 方式 B：对已加固产物追加保护层

```bash
python3 scripts/merge_protection.py -f app_signed.apk -o out/ \
    --keystore my.jks --alias key0 --storepass 123456
```

> `--features` 可选值（默认全部）：`sig_verify,anti_debug,anti_hook,anti_inject,anti_dump,anti_proxy,integrity,runtime_protect,mem_protect,net_secure,root_detect,emu_detect,xposed_detect,frida_detect,magisk_detect,debugger_detect,code_inject,speed_check,multi_instance,ssl_pinning,data_leak,log_protect,app_sig,webview_secure,file_control,component_protect,env_testkeys,env_selinux`

保护模块结构、功能清单与二次编译说明见 [`engine/protect-module/README.md`](engine/protect-module/README.md)。

---

## 签名配置

`protect-config.json`：

```json
{
  "shellPkgName": "<random>",
  "signature": {
    "keystore": "/absolute/path/to/your.jks",
    "alias": "key0",
    "storepass": "your_store_password",
    "keypass": "your_key_password"
  }
}
```

没有 keystore？生成一个：

```bash
keytool -genkeypair -v -keystore my.jks -alias key0 \
  -keyalg RSA -keysize 2048 -validity 10950 \
  -storepass android -keypass android -dname "CN=Me"
```

---

## 加固能力

**函数抽取**：将 dex 中方法的字节码整体抽空，运行时由 native 层按需填回。在此之上：

- 壳类名、方法名、SO 导出符号**全量随机重命名**，**每次打包产物指纹不同**
- 12 字节引导密钥**拆散藏进多个 SO 分片**
- 抽取出的方法体进入独立「**指令池**」，**逐条 AES-256-CBC 加密**，运行时按需解密回填，**内存中不长期驻留完整明文指令**
- 加密数据**先 Deflate 压缩再 RC4 加密**写入 APK 尾部（体积增幅约 +18%）
- **反 dump、反调试、反 Frida、CRC 自校验**协同运行期防护

支持 APK / AAB、多 dex、四种 ABI（arm / arm64 / x86 / x86_64），可运行期签名校验防二次打包。

---

## 常用参数速查

| 参数 | 作用 |
|------|------|
| `-f <file>` | 待加固的 APK/AAB（必填） |
| `-o <dir>` | 输出目录 |
| `-c <json>` | 签名配置文件 |
| `-r <rules>` | 不加固的类名规则文件 |
| `-e <abi>` | 排除架构，如 `arm,x86,x86_64` |
| `-vs` | 开启运行期签名校验 |
| `-S` | 体积优化模式 |
| `-x` | 加固后不签名（自行签名时用） |
| `--disable-frida-detect` | 关闭 Frida 检测 |
| `--disable-anti-debug` | 关闭反调试 |
| `--noisy-log` | 详细日志 |

完整清单：`java -jar engine/ironshell.jar -v` 或见 `engine/README.md`。

---

## 开源致谢

| 项目 | 作者 | 许可 |
|------|------|------|
| [dpt-shell](https://github.com/luoyesiqiu/dpt-shell) | luoyesiqiu | MIT |
| [Dobby](https://github.com/jmpews/Dobby) | jmpews | Apache-2.0 |
| [bhook](https://github.com/bytedance/bhook) | ByteDance | MIT |
| [minizip-ng](https://github.com/zlib-ng/minizip-ng) | zlib-ng | Zlib |
| [zip4j](https://github.com/srikanth-lingala/zip4j) | Srikanth Lingala | Apache-2.0 |
| [zipalign-java](https://github.com/Iyxan23/zipalign-java) | Iyxan23 | MIT |
| [ManifestEditor](https://github.com/WindySha/ManifestEditor) | WindySha | Apache-2.0 |
| [dx](https://android.googlesource.com/platform/dalvik/) | Google/AOSP | Apache-2.0 |

---

## 许可与免责声明

遵循 [MIT License](LICENSE)。

⚠️ **仅供学习交流与自有应用加固使用。** 请勿用于任何非法用途或加固他人应用，因使用本项目产生的一切后果由使用者自行承担。
