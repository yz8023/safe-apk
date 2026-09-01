# Requirements Document

## Introduction

为 AndroidHardeningTool 增加三项能力：① 输出文件路径与默认路径可自定义配置；② 加固运行日志展示更清晰、可读；③ FrostShell 引擎的高级加固选项（保留部分类、瘦身、运行时验签、剔除 ABI）可在界面中选择。

当前代码基线：Kotlin 1.9.20 / Material3 / 单 Activity Compose UI，引擎 Kotlin 移植版已完整实现相关字段但未暴露到界面上。

## Glossary

- **System**: AndroidHardeningTool Android 应用
- **普通加固流程（processApk）**: 使用 `com.adfxcbnm.hardeningtool` 内置流程的加固路径
- **FrostShell 流程（processFrostShellApk）**: 使用 `com.adfxcbnm.frostshell` 引擎的加固路径
- **输出目录**: 加固结果 APK 写入的目录，当前硬编码为 `context.getExternalFilesDir(null)/ADFXCBNM/`
- **保留部分类（keep-classes）**: 官方 FrostShell `-K/--keep-classes`，跳过部分类的 Dex 加固以提升启动速度
- **瘦身（smaller）**: 官方 FrostShell `-S/--smaller`，以牺牲兼容性/性能换取更小产物
- **运行时验签（verify-sign）**: 官方 FrostShell `-vs/--verify-sign`，加固包运行时校验签名 SHA-256
- **剔除 ABI（exclude-abi）**: 官方 FrostShell `-e/--exclude-abi`，从产物中剔除指定 CPU 架构的原生库
- **来源位置**: 输出目标为所选 APK 所在目录（用户补充明确：输出途径与选择 APK 的途径一致，产物直接输出到 APK 所在位置）

## Requirements

### Requirement 1（输出路径自定义）

**User Story:** AS 加固工具使用者, I want 自定义输出文件路径与默认路径, so that 产物按我的习惯存放，而非固定写入应用私有目录

#### Acceptance Criteria

1. WHEN 用户打开"设置"，系统 SHALL 展示当前输出目录与"恢复默认路径"入口
2. WHEN 用户未自定义输出目录，系统 SHALL 将加固产物输出到所选 APK 所在目录（来源位置），与选择 APK 的途径一致
3. WHEN 用户修改输出路径并保存，后续加固流程 SHALL 将产物写入新路径
4. IF 指定路径无写入权限或创建失败，系统 SHALL 提示错误并回退到默认路径
5. WHEN 加固流程输出成功，系统 SHALL 在日志中展示产物的完整目标路径

### Requirement 2（来源位置作为默认输出）

**User Story:** AS 加固工具使用者, I want 产物默认跟随所选的 APK, so that 输出与输入在同一目录，方便管理

#### Acceptance Criteria

1. WHEN 用户通过文件选择器选定 APK，系统 SHALL 将该文件所在目录作为默认输出目录
2. WHEN 用户通过已安装应用列表选定 APK，系统 SHALL 将该应用私有文件中提取的 APK 所在目录作为默认输出目录
3. IF 用户已自定义输出目录，系统 SHALL 优先使用自定义目录输出，且日志中注明"已使用自定义路径"
4. WHEN 输出目录确定，系统 SHALL 在日志中明确展示目标路径，避免误以为产物在旧目录

### Requirement 3（运行日志清晰化）

**User Story:** AS 加固工具使用者, I want 看到分阶段、带状态与耗时汇总的日志, so that 我能快速判断加固走到哪一步、成功与否、耗时多少

#### Acceptance Criteria

1. WHEN 加固流程开始，系统 SHALL 输出一个带序号与名称的阶段标题
2. WHEN 每个阶段完成，系统 SHALL 输出该阶段成功/失败状态与耗时
3. IF 阶段失败，系统 SHALL 以错误样式突出显示失败阶段并给出原因摘要
4. WHILE 详细日志开关关闭，系统 SHALL 只显示阶段级摘要，不显示技术细节行
5. WHEN 全流程结束，系统 SHALL 汇总展示：总耗时、阶段数、成功数、失败数
6. WHEN 日志量超过上限，系统 SHALL 保留最近一条完整阶段汇总而非截断在行中

### Requirement 4（FrostShell 高级选项可配置）

**User Story:** AS FrostShell 引擎用户, I want 在界面中启用保留部分类、瘦身、运行时验签、剔除 ABI, so that 无需命令行也可按需选择官方加固策略

#### Acceptance Criteria

1. WHEN 用户启用 FrostShell 引擎，系统 SHALL 展示高级选项面板
2. WHEN 用户开启"保留部分类"，流程 SHALL 向引擎传入 keep-classes 选项（启动加速）
3. WHEN 用户开启"瘦身"，流程 SHALL 向引擎传入 smaller 选项
4. WHEN 用户开启"运行时验签"，流程 SHALL 向引擎传入 verify-sign 选项并展示签名 SHA-256
5. WHEN 用户开启"剔除 ABI"，系统 SHALL 提供可选 ABI 列表（如 arm64-v8a、armeabi-v7a、x86、x86_64），未勾选的 ABI SHALL 从产物中剔除
6. IF 剔除 ABI 后原生库被全部剔除，系统 SHALL 给出明确警告
7. WHEN 用户未启用任何高级选项，流程 SHALL 保持当前默认加固行为

### Requirement 5（配置持久化与迁移）

**User Story:** AS 加固工具使用者, I want 我的路径与选项选择被记住, so that 不需要每次重新配置

#### Acceptance Criteria

1. WHEN 用户保存输出路径或启用引擎选项，系统 SHALL 持久化到设置在下次启动恢复
2. WHEN 存在旧版本已保存的设置，系统 SHALL 保持向后兼容，不重置为默认