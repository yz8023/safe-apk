# v9.10.8 发布说明

## 修复：勾选 FrostShell 加固后必闪退（真凶：字符串加密写坏主 dex）

### 问题根因（v9.10.7 实测定位）

用户实测 v9.10.7（含壳冲突豁免）后仍闪退，tombstone 显示：

```
signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0, pc=0, lr=0, 1 total frames
```

配合加固日志的 `WARNING: string encrypt classes.dex fail`，定位到真凶：

1. **FrostShell L1 字符串加密**对主 `classes.dex` 运行时重写失败：
   - 主 dex 的 `method_ids` 接近 0xFFFF（本例 65441，dex 039 前索引为 16 位 ushort）
   - DexPool 重建方法池时方法索引重排溢出，写入 `Unsigned short value out of range: 65698`
   - 复现：`viewModels$1.<init>` 写入 `code_item` 时抛 `Exception occurred while writing code_item`
2. **`DexFileFactory.writeDexFile` 非原子写**：失败后主 dex 被截断写坏（44MB → 18MB），损坏的 dex 仍被打包进产物
3. ART 加载损坏 dex 执行损坏 `code_item` → 空指针 SIGSEGV（`pc=0`），且发生在 Process uptime 13s 的启动期

### 修复方案

**字符串加密失败时恢复原始 dex，保证产物不携带损坏方法体：**

- `FrostStringEncryptor.process()` 在写 dex 前先备份原始文件
- `writeDexFile` 异常时恢复原始 dex，跳过该 dex 的字符串加密，继续后续 dex
- 主 dex（方法池逼近上限）跳过加密但保持完整，其余 dex 正常加密，强度基本保留
- 恢复/清理/日志操作全部 try 保护，不阻断异常传递（上层已捕获并继续流程）

### 验证

- 复现脚本确认：classes.dex 恢复到原始 44MB，md5 与备份一致，备份清理干净
- 完整构建通过，产物 APK 内 dex 完整
