# AndroidHardeningTool v9.10.47

## 修复：加固产物冷启动 VerifyError（多 dex 池块级联错位）

### 背景

v9.10.46 两遍结构修复后，palm（5 dex）端到端验证全部 PASS，但 gitapp（9+ dex、R8 混淆）真机冷启动仍复现 MainActivity 全部方法 `invalid argument count exceeds outsSize`（整类整体取错指令，非单方法问题）。

### 根因（壳 so 反汇编精确定位）

壳 so 池解析（`0x9ec94`）按 `dexCodesIndex[i]` 顺序为每个 dex 建立独立 512KB vector（`vector[i] = 第 i 块`），还原时用运行时从 dex 标识解析出的序号 w21（`0x42880`：dex 文件名尾部数字 -1，如 classes2.dex → w21=1）经哈希/红黑树定位 vector。因此契约还要求 **「池内第 k 块 == dexNo k 的方法」**。

原 `makeMultiDexCode` 存在两个多 dex 错位源：

1. **缺失 dexNo 时跳过（continue）**：抽取失败或 entry 丢失时，缺失 dexNo 及其之后所有 dex 的池块整体前移错位（级联）。
2. **`dexCount = map.size` + HashMap 并发 put**：阶段③各 dex 并行写 HashMap，并发 put 有低频丢 key 风险；`dexCount` 亦未按最大 dexNo + 1 计算。

palm 恰因 5 个 dex 全成功抽取得以 PASS，gitapp 任一 dexNo 缺失即必现整体错位。

### 修复

- `makeMultiDexCode`：改为按 `0..maxDexNo` 显式遍历，缺失 dexNo 写 **methodCount=0 空块占位**，`dexCount = maxDexNo + 1`，不再依赖 map size / 迭代序。
- 阶段③抽取 mapping 由 `HashMap` 改为 `ConcurrentHashMap`，消除并发 put 丢 key。

### 验证

- `MultiDexVerify.java` 构造缺失 dexNo=3 的 9 dex 场景（模拟抽取失败）：
  - 修复前：5 块级联错位（dexNo 3 之后全部取错块）。
  - 修复后：0 错位（8 块正确 + 1 空块占位）。

### 待终验

gitapp / palm 真机冷启动不再抛 VerifyError。若仍崩，剩余变量集中于 so 端 w21 输入字符串语义与抽取端 dexNo 的一致性。