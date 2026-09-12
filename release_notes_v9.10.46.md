# AndroidHardeningTool v9.10.46

## 修复：加固产物冷启动 VerifyError「invalid argument count exceeds outsSize」

### 根因（壳 so 反汇编精确定位）

壳 so 还原时按「方法记录的 method_index」直接索引池条目 vector（`0xa0868 vector[methodIndex]`），并非按池文件顺序消费，也不跳过共享 code_item。抽取侧此前按 class_data 序遍历生成紧实池，存在两类错位源：

1. **class_data 遍历 ≠ method_ids 全表**：method_ids 中存在大量未在任何 class_data 中声明的索引（palm classes.dex 65441 个 method_ids 中 4207 个缺失，classes7 65266 中 11195 个缺失），紧实池按 method_index 索引时错位，对齐率仅 9/62159。
2. **abstract/native/空指令方法无条目**：需以 size=0 占位条目补齐空洞。

### 修复（两遍结构）

- 第一遍按 class_data 顺序遍历所有方法：抽取指令、stub 写回（共享 code_item 用缓存原始指令、极小方法不 stub）、RC4 加密，按 method_index 存入 map。
- 第二遍按 `dex.methodIds()` 全表 `0..methodCount` 顺序出池条目：map 未命中的索引全部补 size=0 占位条目。

池条目数恒等于 method_ids 总数、按 method_index 天然升序、完全连续，`vector[methodIndex].methodIndex == methodIndex` 恒成立。

### 端到端验证

对 palm 全部 5 个 dex 执行真实抽取+加密+写池，独立实现 RC4 解密逐条对比原 dex 指令：

| dex | 池条目 | 解密命中 | 占位命中 | 不匹配 |
|-----|--------|----------|----------|--------|
| classes.dex | 65441 | 61234 | 4207 | 0 |
| classes7.dex | 65266 | 54071 | 11195 | 0 |
| classes4.dex | 2265 | 1671 | 594 | 0 |
| classes2.dex | 136 | 135 | 1 | 0 |
| classes8.dex | 682 | 338 | 344 | 0 |

全部池条目连续唯一、无错位、解密 100% 命中。待真机冷启动终验确认不再抛 VerifyError。
