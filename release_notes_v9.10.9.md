# v9.10.9 发布说明

## 新增 / 修复

### 1. 修复：加固成功后"大小变化"永远显示 +0

FrostShell 引擎路径的 `sizeDiff` 计算存在 bug：`realSize` 被错误赋值为源 APK 大小（`val realSize = sourceInputSize`），导致 `sizeDiff = realSize - sourceInputSize = 0`，永远显示 +0。

现改为读取实际输出文件大小：
- 输出为文件路径时直接取文件长度
- 输出为 content://（MediaStore 下载兜底）时通过 ContentResolver 查询真实大小

### 2. 新增：加固成功后一键"打开 APK"

成功对话框新增「打开 APK」按钮，直接调用系统安装器：
- 文件路径经 FileProvider 转为 content URI（`root-path` 已覆盖全部路径）
- content:// 输出直接使用
- 需要"安装未知应用"权限时系统会自动引导授权

## 说明

v9.10.8 的字符串加密写坏主 dex 修复已包含在本版本中（dex 失败恢复兜底 + 版本号继承）。
