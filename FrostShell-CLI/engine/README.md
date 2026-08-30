# FrostShell 加固引擎 · 命令行调用说明

本目录是 FrostShell 加固器的独立引擎，可脱离 App 直接在电脑上加固 APK / AAB。

```
engine/
├── ironshell.jar                     # 加固器主程序（自包含，含 zipalign/apksigner，无需 Android SDK）
├── shell-files/                      # 壳运行时资产（native .so + proxy dex + 密钥），加固时被打入 APK
│   ├── dex/                          #   proxy classes.dex + junkcode.dex
│   ├── libs/{arm,arm64,x86,x86_64}/  #   各架构壳 so
│   ├── build-key                     #   与壳 so 匹配的构建密钥
│   └── build-ids.properties
├── protect-module/                   # ★ ADFXCBNM 运行时保护模块（二合一加固用）
│   ├── dex/security_check.dex        #   保护检测 dex（SecurityCheckProvider + S）
│   ├── libs/{arm64-v8a,armeabi-v7a,x86,x86_64}/libsecurity_check.so
│   ├── features.cfg.template         #   assets/features.cfg 生成模板
│   ├── exclude.rules                 #   FrostShell -r 排除规则（方案 B 备用）
│   ├── manifest_inject.py            #   纯 Python 二进制 Manifest 注入器
│   └── src/                          #   保护模块源码（参考/二次编译）
├── protect-config-template.json      # 签名配置模板
└── exclude-classes-template.rules    # 不加固类名规则模板
```

---

## 一、环境要求

- **JDK 17 或更高**（推荐 21）。`java -version` 应显示 17+。
- 不需要 Android SDK：`zipalign` 与 `apksigner` 已内置进 jar。
- `shell-files/` 目录必须与 `ironshell.jar` 保持在同一目录（jar 依据自身位置定位它）。

---

## 二、最简单的用法

### 方式 A：用快捷脚本（推荐）

见 `../scripts/protect.py`，一条命令搞定，环境不全会提示甚至自动下载 JDK：

```bash
python3 ../scripts/protect.py your-app.apk
```

### 方式 B：直接调 jar

```bash
# 进入 engine 目录（保证能找到 shell-files）
cd engine

# 1) 准备签名配置（复制模板改成你的 keystore）
cp protect-config-template.json protect-config.json
#   编辑 protect-config.json 填入 keystore 路径 / 别名 / 密码

# 2) 加固
java -jar ironshell.jar -f /path/to/your-app.apk -c protect-config.json -o ./out
```

产物在 `./out/xxx_signed.apk`。

### 方式 C：二合一加固（函数抽取 + ADFXCBNM 运行时保护）

在 FrostShell 之上叠加 ADFXCBNM v9.4.27 的 26 项运行时检测：

```bash
# 推荐：一条命令完成两层加固
python3 ../scripts/protect.py your-app.apk --plus

# 指定 keystore 与保护功能
python3 ../scripts/protect_plus.py your-app.apk \
    --keystore my.jks --alias key0 --storepass 123456 \
    --features root_detect,frida_detect
```

内部流程：`protect.py` 完成 FrostShell 函数抽取加固后，
`merge_protection.py` 注入 `SecurityCheckProvider`(Manifest) + `security_check.dex` +
`libsecurity_check.so`(4 ABI) + `assets/features.cfg`，再用 jar 内置 apksig 重新签名。
产物 `xxx_plus_signed.apk`。

保护模块说明见 [`protect-module/README.md`](protect-module/README.md)。

---

## 三、签名配置文件

`protect-config.json` 格式：

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

| 字段 | 说明 |
|------|------|
| `shellPkgName` | 壳包名。填 `<random>` 由引擎随机生成（推荐，配合 M1 符号随机化） |
| `signature.keystore` | 你的签名 keystore（.jks/.keystore）路径 |
| `signature.alias` | keystore 内的密钥别名 |
| `signature.storepass` | keystore 密码 |
| `signature.keypass` | 密钥密码（常与 storepass 相同） |

> 没有 keystore？用 keytool 生成一个：
> ```bash
> keytool -genkeypair -v -keystore my.jks -alias key0 \
>   -keyalg RSA -keysize 2048 -validity 10950 \
>   -storepass android -keypass android -dname "CN=Me"
> ```

---

## 四、命令行参数全集

```
用法: java -jar ironshell.jar [选项] -f <包文件>

 -f,--package-file <arg>     待加固的 APK/AAB 文件（必填）
 -o,--output <arg>           加固产物输出目录
 -c,--protect-config <arg>   签名配置文件（见上）
 -r,--rules-file <arg>       不加固的类名规则文件（见模板）
 -e,--exclude-abi <arg>      排除指定架构（逗号分隔）：arm,arm64,x86,x86_64
 -K,--keep-classes           保留部分类不加固以提升启动速度（部分包不兼容）
 -S,--smaller                牺牲部分性能换更小体积
 -vs,--verify-sign           开启运行期签名校验（证书 SHA-256 自动从 keystore 计算）
 -x,--no-sign                加固后不签名（自行签名时用）
    --debug                  产物可调试
    --disable-anti-debug     关闭运行期反调试
    --disable-crc-detect     关闭 libc .text CRC 校验
    --disable-frida-detect   关闭 Frida 检测
    --disable-acf            关闭 AppComponentFactory（仅调试用）
    --dump-code              导出 dex 的 code item 为 json（调试用）
    --noisy-log              输出详细日志
 -v,--version                显示版本号
```

### 常用组合

```bash
# 标准加固 + 签名校验（防二次打包）
java -jar ironshell.jar -f app.apk -c cfg.json -o out -vs

# 只保留 arm64，砍掉其它架构，体积最小
java -jar ironshell.jar -f app.apk -c cfg.json -o out -e arm,x86,x86_64 -S

# 排除某些类不加固（如反射调用密集的类）
java -jar ironshell.jar -f app.apk -c cfg.json -o out -r exclude-classes.rules
```

`exclude-classes.rules` 每行一条类名匹配规则，格式见 `exclude-classes-template.rules`。

---

## 五、加固能力

FrostShell 是**函数抽取型**加固壳：把 dex 方法的字节码整体抽空，运行时由 native 层按需填回。

| 层 | 能力 |
|----|------|
| M1 | 壳类名/方法名/SO 导出符号全量随机重命名，每次打包指纹不同 |
| M2 | 12 字节引导密钥拆散藏进多个 SO 分片 |
| M3 | 解密 IV 运行期动态派生，非硬编码 |
| M4 | 抽取的方法体进独立指令池，逐条 AES-256-CBC 加密，运行时按需解密回填 |
| M5 | 加密数据先 Deflate 压缩再 RC4 加密写入 APK 尾部（体积增幅约 +18%） |
| M10 | 反 dump / 反调试 / 反 Frida / CRC 自校验协同 |

---

## 六、常见问题

**Q: 提示找不到 shell-files？**
A: 确保在 `engine/` 目录内执行，或让 `ironshell.jar` 与 `shell-files/` 同目录。

**Q: 加固后装不上 / 秒退？**
A: 多为签名或 minSdk 问题。先用 `--debug` + `--noisy-log` 重新加固，用 `adb logcat` 看崩溃栈；反射密集的类用 `-r` 排除。

**Q: 支持 x86 模拟器吗？**
A: 支持，`shell-files/libs/` 含 x86/x86_64。别用 `-e` 把它们排除掉即可。

**Q: 加固后能再签名吗？**
A: 可以，加 `-x` 不让引擎签名，产物为 `xxx_unsign.apk`，你用 apksigner 自行签名。
