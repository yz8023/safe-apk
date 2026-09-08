# v9.10.17

## 运行时保护：降低检测轮询功耗

- **检测节奏优化**：native 运行时防破解检测线程轮询间隔从 1 秒拉长至 4 秒，CPU 唤醒频率降低 75%，减少电量与资源占用；检测覆盖面（Frida / 调试器 / Xposed / 注入）不变。
- 不影响函数抽取、字符串加密等加固能力。

## 回顾 v9.10.16

- **字符串加密重构**：string_ids 超 0xFFFF 的 dex 直接跳过加密（避免 DexPool 重写必然溢出回退）；加密器尊重排除规则，DrawScope 等 androidx 类彻底不触碰，修复仅开字符串加密时 IncompatibleClassChangeError 闪退。
