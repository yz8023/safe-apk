# v9.10.11

## 新功能：签名工具支持外部密钥（PK8/PEM）与格式转换

### 1. 彻底修复 JKS 无法读取的问题
- Android 系统不提供 JKS (Java KeyStore) provider，导致 **jks/.keystore 文件此前在手机上完全读不了**（打开即失败）。
- 内置纯 Java 实现的 JKS v2 解析器 + KeyProtector 私钥解密，不依赖任何系统 provider，**jks 文件现在可以正常读取、查看与用作加固签名**。
- 支持 JDK 9+ 生成的 JKS v2 格式，算法完全复刻（UTF-16BE 密码、SHA1 迭代密钥等），实测可解出 6144-bit RSA 私有密钥。

### 2. 新增 PK8/PEM 平台签名支持
- 加固 APK 时可直接选用 **AOSP 平台签名密钥**（如 `platform.pk8` + `platform.x509.pem`）。
- 私钥支持两种形式：
  - 未加密 PKCS8（DER/PEM）
  - 加密 PKCS8（EncryptedPrivateKeyInfo，PBES2 / PBEWithMD5 系列，需输入密钥密码）
- 证书文件自动按同名配对（`xxx.pk8` ↔ `xxx.x509.pem`），或直接从 PEM 证书文件加载。

### 3. 签名工具新增「格式转换」
- **转 P12**：任意 jks / pk8 / pem / p12 → PKCS12（统一为各设备稳定可读的标准格式），转换后一键设为当前签名密钥。
- **导出 PK8/PEM**：从任意 keystore 导出 `xxx.pk8`（私钥）+ `xxx.x509.pem`（证书），供外部加固/平台签名使用。
- 全程兼容你的 Forinxy 密钥：实测 6144-bit RSA 在 jks → p12 → pk8/pem 间任意互转，私钥字节级一致。

### 4. 兼容性
- 查看签名信息、加固签名均支持 p12/pfx/jks/keystore/ks/bks/pk8/key/pem。
- targetSdk 34，向下兼容 Android 8.0+。

## 修复
- 修复签名工具在目标设备（无 JKS provider 的 ROM）上读取 jks 静默失败的问题。