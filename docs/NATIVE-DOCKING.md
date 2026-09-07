# Native 对接点说明（VMP / SO 解密 / ELF section）

> 适用范围：AndroidHardeningTool FrostShell 引擎与 ArkProtector v2.8.0 的 native 层特征移植。
> 本工程当前以 **Kotlin 层实现**为主（SO 随机命名、字面字符串加密、抽取壳增强、lib 加密开关），
> 下列 native 依赖项**尚未移植**，本文档记录其在本工程中的对接接入点，供后续 native 实现落地。

---

## 1. 背景与范围

ArkProtector 的强化链路包含：

```
DEX 混淆 → DPT 抽取 → VMP 抽取 → native b() 加密
→ lib/so 改名 stub → 注入 vmp.bin（ELF section）→ SO 加密
```

其中面向 native 平台的三个能力在本工程中的对应关系：

| ArkProtector native 能力 | 参考代码位置 | 本工程现状 | 对接点 |
|--------------------------|--------------|-----------|--------|
| VMP 指令解释器（vmEngine） | `VmEngineFactory`（NONE/ORIGINAL/NMMP 三态） | 未实现 | `FrostStringEncryptor` 注入的 helper 执行期替换 |
| SO 文件加密 / RC4 解密 | `FrostReadElf` + `.bc` section | **已实现**（TC 侧加密） | native 侧运行时解密 `lib/{abi}/*.so` |
| ELF section 注入（vmp.bin） | `ArkVMP.cpp` `writeVmpBinToElfSection` | 未实现 | `encryptSoFile()` 的伴随实现 |

---

## 2. VMP 解释器对接点

### 2.1 现有约束

- 壳 dex（`shell-files/dex/classes.dex`）中，`FrostDexUtils.injectInvokeMethod` 已向所有可抽取方法注入反射调用（对齐 ArkProtector 的 `invokeMethod`）。抽取后方法体清零，运行时经 JNI 回填。
- ArkProtector 在此基础上进一步将指令替换为 **NMMP 自定义指令集**，由 native 解释器执行；当前 Kotlin 工程仅保留原始指令池（`FrostMultiDexCodeUtils.writeMultiDexCode` + AES 加密落盘）。

### 2.2 对接点（建议顺序）

1. **指令抽取后、指令池加密前**，在主流程 `extractDexCode()` 中新增一步：把回收的原始指令 `MULTI_DEX_CODE` 拆分为「VMP 指令」与「解密令牌」两种载荷，交由 native 解释器注册表消费（对应 ArkProtector `ArkVmEngine` 的 `VmEngine.NMMP` 分支）。
2. **运行时**：`ProtectionNative.kt` 新增 JNI 入口（如 `nativeInstallVmInterpreter`）→ `protection.cpp` 中用 `extern "C"` 导出 dlsym 可寻址解释器符号，与 `FrostJunkCodeGenerator` 生成的无关代码同位存放，见 `app/src/main/cpp/protection.cpp:854` 的宏区。
3. **回填**：拦截方法调用点，用解释器执行指令以替代聚合函数 `sXY`（`FrostStringEncryptor` 生成的解密 helper）所依赖的原始字节码，从而消除字符串常量池明文。

> 注意：字符串 helper（`FrostStringEncryptor`, `sXXXXXXXX (String, int) -> String`）在 Kotlin 侧已注入为真实方法；若后续接入 VMP，需调整 `CallReplacer.helperRef` 的 emit 目标（`INVOKE_STATIC_RANGE` → 解释器 trap 指令），改为在运行时由 native 解释器广播字节。

---

## 3. SO 文件加密 / native 解密对接点

### 3.1 TC 侧已实现

`FrostAndroidPackage.encryptSoFiles(packageOutDir, rc4Key)`（`FrostAndroidPackage.kt:444`）：

- 对 `assets/irn/{abi}/*.so` 中的每个 `*.so`，用 `FrostReadElf` 打开，
  定位 `.bc`（或 `bitcodeSection` 配置名）section，读取其字节内容，用
  `rc4Crypt(Arrays.copyOfRange(rc4Key, 0, 12), bitcode)` 原位写回。
- 随后 `writeSoFileCryptKey` 把 `rc4Key` 拆成 4 字节 × 3 片写入 ELF 符号
  `IS_KEY_SHARD1` / `IS_KEY_SHARD2` / `IS_KEY_SHARD3`，并写 16 字节随机
  诱饵到 `IS_DECOY_KEY` 符号。

### 3.2 native 侧待实现（解密与自校验）

建议在 `protection.cpp` 复用 `.bc` section 定位逻辑，新增如下符号（可放置于
`Java_com_adfxcbnm_hardeningtool_ProtectionNative` 导出区之后）：

```c
// 读取 IS_KEY_SHARD* 三个符号的 4 字节，拼接成 12 字节 -> 派生 RC4 key
static int soDecryptInit(void) {
    // 1. dlopen("libxxx.so") 或直接读 /data/app/.../lib/{abi}/libxxx.so
    // 2. 用 FrostReadElf 等价实现扫描 ELF section `.bc`
    // 3. 读取 IS_KEY_SHARD1..3 拼接 key，rc4 解密 .bc 内容写回内存中的 .data
    return 0;
}
```

与 TC 侧密钥派生保持严格一致：

- RC4 前 12 字节来自 `rc4Key[0..12)`（同 `encryptSoFile` 的 `Arrays.copyOfRange(rc4Key, 0, 12)`）。
- 全量校验可借用现有 `selfIntegrityCheck()` / `runtimeCheckCodeIntegrity()`；
  需额外把 `.bc` 解密后的字节纳入完整性哈希集合（防御用 hook 手段篡改 section 内容）。

---

## 4. ELF section 注入对接点

### 4.1 参考实现

ArkProtector 把 VMP 引擎二进制写入壳 SO 的 ELF section：

```c
// ArkVMP.cpp（参考，未移植）
constexpr const char *kSectionName = ".ArkProtector_MaintainedBy_Forinxy_OpenSourceLearning";
writeVmpBinToElfSection(vmpBytes, outSoPath, kSectionName);
```

同名 section 在运行期被 `dlopen` 后由 native 代码自认证。

### 4.2 本工程建议接入位置

- 现有 SO 处理发生在 `FrostApk.process()` 的 `copyNativeLibs()`（复制壳 SO 到 `assets/irn/{abi}/`）与
  `encryptSoFiles()`（`.bc` 加密 + key 分片写入符号）。
- 若实现 VMP，可在 `encryptSoFile()` 中 `.bc` 加密**之后**追加一步：
  生成 vmp.bin（或 `.bc` 解密 stub），调用新增的
  `writeSectionToElf(soFile, sectionName, vmpBin)` 写入自定义 section；
  section 名建议沿用 ArkProtector 的 `.ArkProtector_MaintainedBy_Forinxy_OpenSourceLearning`
  或改用本工程命名空间（见下）。

> 安全提示：`writeSoFileCryptKey` 已写入 `IS_DECOY_KEY` 诱饵符号；注入 ELF section 时需要
> 与诱饵保持一致的长度分布，避免通过 section 大小指纹识别出壳。

---

## 5. 键名 / 配置贯通

加密与解密两侧依赖的密钥分片命名统一（TC 侧 `FrostAndroidPackage` ↔ native 侧符号）：

| 数据 | TC 侧写入 | native 侧读取 |
|------|-----------|---------------|
| RC4 前 12 字节 | `rc4Key[0..12)` | `soDecryptInit()` 内用于 RC4 decrypt `.bc` |
| 3 × 4 字节分片 | 符号 `IS_KEY_SHARD1..3` | 读取拼接还原 `rc4Key` |
| 随机诱饵 | 符号 `IS_DECOY_KEY`（16B 随机） | 应忽略或参与诱饵校验 |
| 指令池 AES key | `FrostShellConfig.setInsnsCryptKey()` | 壳 runtime 读取 `assets/irn/.meta` 的 `insns_key` 字段 |

当前 `FrostShellConfig` 序列化至 `assets/irn/.meta`（JSON），native 侧注意：
- `.meta` 是 JSON 文本，字段名见 `FrostShellConfig.toJson()`：`insns_key`、`key_shard4` 等；
- 若 native 需要独立解密，推荐把 `insns_key` 的十六进制导出到 `getenv("FROST_INSNS_KEY")` 并
  在 `loadLibrary` 后清除 env，避免 `strings` 直接暴露。

---

## 6. 验收清单（native 落地后）

- [ ] `protectApk()` 产出 APK 在设备上完成抽取方法运行时回填，无 `NoSuchMethodError`。
- [ ] `lib/{abi}/*.so` 的 `.bc` 被加密，解密后与原库字节一致（比对 SHA-256）。
- [ ] `IS_DECOY_KEY` 存在且为随机值，`IS_KEY_SHARD1..3` 拼接后能还原 RC4 key。
- [ ] 新增 ELF section 在 `readelf -S` 可见，且未出现 size 指纹。
- [ ] 字符串加密的 `sXXXXXXXX` helper 在关闭开关（`frost_string_encrypt=false`）时可被
      `obfuscate=false`（`smaller=true`）路径正确跳过，不影响壳 dex 结构。