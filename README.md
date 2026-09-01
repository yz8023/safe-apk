# ADFXCBNM Android Hardening Tool

Android APK 加固工具，提供 15 项加固功能和 20 项保护功能。

## 功能

### 加固功能 (15项)
1. DEX加密加固 - AES-256-CBC 加密
2. DEX虚拟化 - 自定义字节码格式
3. SO库加固 - AES 加密
4. SO符号剥离 - ELF 符号表清零
5. SO加壳保护 - 压缩+AES加密
6. 资源文件加密 - AES-256-CBC
7. 签名校验 - SHA-256 哈希
8. 防调试检测 - 多重检测
9. 防Hook检测 - Xposed/Frida
10. 防注入保护 - 反编译陷阱
11. 防内存Dump - 内存保护
12. 代码混淆 - XOR 字符串池
13. 字符串加密 - ASCII 移位
14. 防反编译 - 无效操作码
15. 防代理检测 - 代理检测

### 保护功能 (20项)
16. 完整性校验 - SHA-256
17. 运行时保护 - JNI 检测
18. 内存保护 - 完整性标记
19. 网络安全 - SSL 固定
20. ROOT检测 - su 扫描
21. 模拟器检测 - 多维度
22. Xposed检测 - 类加载
23. Frida检测 - 端口扫描
24. Magisk检测 - 路径检测
25. 调试器检测 - JDWP
26. 代码注入检测 - 库扫描
27. 速度检测 - 时序分析
28. 多开检测 - 进程分析
29. SSL证书校验 - 证书固定
30. 数据防泄漏 - 保护标记
31. 日志保护 - 输出控制
32. 应用签名校验 - 哈希验证
33. WebView安全 - 配置保护
34. 文件访问控制 - 权限检测
35. 应用组件保护 - Manifest

## 构建

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=/opt/android-sdk
./gradlew assembleDebug
```

## 输出

加固后的 APK 保存到: `/storage/emulated/0/ADFXCBNM/`

## 联系方式

Telegram: @ADFXCBNM
