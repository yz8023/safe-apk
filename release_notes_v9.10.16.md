# v9.10.16

## 字符串加密：重构与加固（修复仅开字符串加密闪退）

- **string_ids 超限预检**：字符串池超过 0xFFFF 的 dex（如含 Compose 的 classes.dex / classes7.dex）直接跳过加密，不再进入 dexlib2 DexPool 重写路径。此类 dex 因 string 表重排必然触发 `Unsigned short value out of range` 写入失败，旧版依赖"写失败回退原文件"兜底，现改为前置跳过，更稳更快。
- **加密器尊重排除规则**：字符串加密阶段与函数抽取对齐，跳过 `androidx.*` 等框架类，DrawScope 这类 Compose 接口类从此彻底不会被加密器改写，规避壳内指令恢复错位导致的 `IncompatibleClassChangeError`（Found interface DrawScope, but class was expected）闪退。
- 普通 dex 加密行为不变，命中数量与 helper 注入逻辑一致。

## 回顾 v9.10.15

- 修复字符串加密参数搬移错位：多参数方法按反向槽位生成 move 导致参数读取错误寄存器类型（ART VerifyError 闪退），现按 Dalvik 参数位序正向计算并倒序插入。
- 签名生成日期自动预填标准格式（昨天 ~ 有效期末），快捷有效期点击同步刷新日期。