#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
merge_protection.py — 将 ADFXCBNM 运行时保护模块注入 APK
=========================================================
在已加固（或任意）APK 基础上，追加注入 ADFXCBNM 运行时检测/保护层：

    * AndroidManifest.xml   注入 SecurityCheckProvider ContentProvider
    * classesN.dex          追加注入 security_check.dex（保护检测字节码）
    * lib/<abi>/            注入 libsecurity_check.so（四 ABI，native 检测）
    * assets/features.cfg   写入功能配置 + 签名证书 SHA-256 基线 + dex CRC 基线

随后用 ironshell.jar 内置 apksig 重新签名（v1+v2），无需 Android SDK。

该模块与 FrostShell 函数抽取壳分层工作、互不冲突：FrostShell 负责抽取原 dex
方法体，本模块是独立追加的 dex + so + provider，二者不覆盖彼此。

用法:
    python3 merge_protection.py -f app.apk -o out/ [签名参数]
"""

import argparse
import hashlib
import json
import os
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile
import zlib
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
MODULE_NAME = "protect-module"
FEATURE_VERSION = "9.4.27"

# 从 AndroidHardeningTool MainActivity.featureMap 提取的功能标识
HARDENING_FEATURES = [
    "sig_verify", "anti_debug", "anti_hook", "anti_inject",
    "anti_dump", "anti_proxy",
]
PROTECTION_FEATURES = [
    "integrity", "runtime_protect", "mem_protect", "net_secure",
    "root_detect", "emu_detect", "xposed_detect", "frida_detect",
    "magisk_detect", "debugger_detect", "code_inject", "speed_check",
    "multi_instance", "ssl_pinning", "data_leak", "log_protect",
    "app_sig", "webview_secure", "file_control", "component_protect",
    "env_testkeys", "env_selinux",
]
ALL_FEATURES = HARDENING_FEATURES + PROTECTION_FEATURES

ABIS = ["arm64-v8a", "armeabi-v7a", "x86", "x86_64"]
SO_FILE = "libsecurity_check.so"
DEX_FILE = "security_check.dex"


# ── 颜色输出（与 protect.py 保持一致） ──
class C:
    G = "\033[92m"; Y = "\033[93m"; R = "\033[91m"; B = "\033[94m"; DIM = "\033[2m"; END = "\033[0m"

    @staticmethod
    def off():
        C.G = C.Y = C.R = C.B = C.DIM = C.END = ""


if os.environ.get("NO_COLOR") or not sys.stdout.isatty():
    C.off()


def info(m): print(f"{C.B}[*]{C.END} {m}")
def ok(m):   print(f"{C.G}[✓]{C.END} {m}")
def warn(m): print(f"{C.Y}[!]{C.END} {m}")
def err(m):  print(f"{C.R}[✗]{C.END} {m}")


# ── 定位模块 ──
def find_module_dir():
    candidates = [
        SCRIPT_DIR.parent / "engine" / MODULE_NAME,
        SCRIPT_DIR / MODULE_NAME,
        SCRIPT_DIR.parent / MODULE_NAME,
    ]
    for d in candidates:
        if (d / "dex" / DEX_FILE).is_file() and (d / "libs").is_dir():
            return d
    return None


# ── 手动构造 zip（支持 4/4096 字节对齐） ──
def _dos_datetime(ts):
    y, mo, d, h, mi, s = ts
    dt = ((y - 1980) << 9) | (mo << 5) | d
    tm = (h << 11) | (mi << 5) | (s // 2)
    return tm, dt


class ZipBuilder:
    """轻量 zip 写入器，支持 STORED 条目对齐（zipalign 语义）。"""

    def __init__(self, path):
        self.f = open(path, "wb")
        self.offset = 0
        self.centrals = []
        self._closed = False

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        if not self._closed:
            self.finish()
        return False

    def write_entry(self, name, data, method, ts=None, align=None):
        if ts is None:
            ts = (1980, 1, 1, 0, 0, 0)
        tm, dt = _dos_datetime(ts)
        name_b = name.encode("utf-8")
        crc = zlib.crc32(data) & 0xFFFFFFFF
        if method == zipfile.ZIP_STORED:
            cdata = data
        else:
            co = zlib.compressobj(6, zlib.DEFLATED, -15)
            cdata = co.compress(data) + co.flush()
            if len(cdata) >= len(data):
                method = zipfile.ZIP_STORED
                cdata = data
        if method == zipfile.ZIP_STORED and align is None:
            align = 4
        extra = b""
        if align is not None:
            pad = (align - ((self.offset + 30 + len(name_b) + 4) % align)) % align
            extra = struct.pack("<HH", 0x0000, pad) + b"\x00" * pad
        local = struct.pack("<IHHHHHIIIHH", 0x04034B50, 20, 0, method,
                            tm, dt, crc, len(cdata), len(data),
                            len(name_b), len(extra))
        self.f.write(local)
        self.f.write(name_b)
        self.f.write(extra)
        self.f.write(cdata)
        self.centrals.append((name_b, method, tm, dt, crc, len(cdata),
                              len(data), self.offset, extra))
        self.offset += len(local) + len(name_b) + len(extra) + len(cdata)

    def finish(self):
        if self._closed:
            return
        self._closed = True
        cd_start = self.offset
        for (name_b, method, tm, dt, crc, csize, usize, offset, extra) in self.centrals:
            cent = struct.pack("<IHHHHHHIIIHHHHHII", 0x02014B50, 20, 20, 0,
                               method, tm, dt, crc, csize, usize,
                               len(name_b), len(extra), 0, 0, 0, 0x20000, offset)
            self.f.write(cent)
            self.f.write(name_b)
            self.f.write(extra)
            self.offset += len(cent) + len(name_b) + len(extra)
        cd_size = self.offset - cd_start
        eocd = struct.pack("<IHHHHIIH", 0x06054B50, 0, 0,
                           len(self.centrals), len(self.centrals),
                           cd_size, cd_start, 0)
        self.f.write(eocd)
        self.f.close()


def read_apk_entries(apk_path):
    """读取 APK 全部条目（跳过 META-INF 旧签名与目录条目）。"""
    entries = []
    with zipfile.ZipFile(apk_path) as z:
        for zi in z.infolist():
            if zi.is_dir():
                continue
            if zi.filename.startswith("META-INF/"):
                continue
            data = z.read(zi)
            entries.append({
                "name": zi.filename,
                "data": data,
                "method": zipfile.ZIP_STORED if zi.compress_type == zipfile.ZIP_STORED else zipfile.ZIP_DEFLATED,
                "ts": zi.date_time,
            })
    return entries


def extract_package_from_manifest(data):
    """从二进制 manifest 提取 package 属性。"""
    try:
        import importlib.util
        spec = importlib.util.spec_from_file_location(
            "manifest_inject", str(SCRIPT_DIR.parent / "engine" / MODULE_NAME / "manifest_inject.py"))
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)
        r = mod.Reader(data)
        r.u16(); r.u16(); r.u32()
        sp = mod.read_string_pool(r)
        if sp is None:
            return ""
        chunks = mod.read_chunks(r, sp)
        for c in chunks:
            if c.kind == "element" and c.is_start and c.name == "manifest":
                for a in c.attrs or []:
                    if a.name == "package" and a.ns is None:
                        return a.value
                break
    except Exception:
        pass
    return ""


def modify_manifest(orig, target_pkg, log=None):
    import importlib.util
    spec = importlib.util.spec_from_file_location(
        "manifest_inject", str(SCRIPT_DIR.parent / "engine" / MODULE_NAME / "manifest_inject.py"))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod.modify_manifest(orig, target_package=target_pkg, log=log)


def build_features_cfg(features, cert_sha256, dex_crcs):
    lines = [
        f"# ADFXCBNM Feature Configuration v{FEATURE_VERSION}",
        f"version={FEATURE_VERSION}",
        "timestamp=%d" % int(__import__("time").time() * 1000),
        "count=%d" % len(features),
        "features=%s" % ",".join(features),
    ]
    for f in features:
        lines.append(f"{f}=enabled")
    if cert_sha256:
        lines.append(f"signature_sha256={cert_sha256}")
    for name, crc in dex_crcs.items():
        lines.append(f"dex_crc_entry={name}:{crc}")
    return ("\n".join(lines) + "\n").encode("utf-8")


def compute_crc(data):
    return zlib.crc32(data) & 0xFFFFFFFF


def find_java():
    for c in [shutil.which("java"), os.environ.get("JAVA_HOME")]:
        if not c:
            continue
        if os.path.isdir(c):
            cand = Path(c) / "bin" / "java"
            if cand.is_file():
                return str(cand)
        elif shutil.which(c):
            return c
    return None


def inject(apk_path, out_dir, features, engine_dir, module_dir,
           ks, alias, storepass, keypass, java_bin=None):
    src = Path(apk_path)
    if not src.is_file():
        err(f"APK 不存在: {src}")
        return False
    Path(out_dir).mkdir(parents=True, exist_ok=True)

    if features is None:
        features = ALL_FEATURES
    features = [f for f in features if f in ALL_FEATURES]
    if not features:
        err("未选择任何有效功能。可用: " + ",".join(ALL_FEATURES))
        return False

    engine_dir = Path(engine_dir)
    module_dir = Path(module_dir)
    if java_bin is None:
        java_bin = find_java()
    if not java_bin:
        err("未检测到 Java，无法重签名。请先安装 JDK 17+ 或用 --java 指定。")
        return False

    dex_bytes = (module_dir / "dex" / DEX_FILE).read_bytes()
    if not dex_bytes.startswith(b"dex\n"):
        err(f"{DEX_FILE} 不是有效 dex")
        return False

    info(f"读取 APK: {src.name}")
    entries = read_apk_entries(src)
    name_set = {e["name"] for e in entries}
    info(f"条目数: {len(entries)}")

    # 1. manifest 修改
    manifest_orig = None
    for e in entries:
        if e["name"] == "AndroidManifest.xml":
            manifest_orig = e["data"]
            break
    if manifest_orig is None:
        err("APK 内没有 AndroidManifest.xml")
        return False

    target_pkg = extract_package_from_manifest(manifest_orig)
    info(f"目标包名: {target_pkg or '(未识别)'}")

    def mlog(m):
        info("  manifest: " + m)
    mres = modify_manifest(manifest_orig, target_pkg, log=mlog)
    if not mres.modified:
        err("AndroidManifest.xml 注入失败，中止。")
        return False
    ok("Manifest: 已注入 SecurityCheckProvider")

    # 2. 新 dex 命名
    max_n = 0
    for e in entries:
        n = e["name"]
        if n.startswith("classes") and n.endswith(".dex"):
            try:
                num = int(n[7:-4]) if n != "classes.dex" else 1
                max_n = max(max_n, num)
            except ValueError:
                pass
    new_dex_name = f"classes{max_n + 1}.dex"
    if max_n == 0:
        new_dex_name = "classes2.dex"

    # 3. dex CRC 基线（含注入的 dex）
    dex_crcs = {}
    for e in entries:
        n = e["name"]
        if n.startswith("classes") and n.endswith(".dex"):
            dex_crcs[n] = compute_crc(e["data"])
    dex_crcs[new_dex_name] = compute_crc(dex_bytes)

    # 4. 证书 SHA-256（cert-only 预取）
    sha = ""
    helper_src = SCRIPT_DIR / "SignerHelper.java"
    sha_file = Path(out_dir) / "cert.sha256"
    with tempfile.TemporaryDirectory(prefix="frost-certsig-") as td:
        td = Path(td)
        jar = engine_dir / "ironshell.jar"
        javac = Path(java_bin).with_name("javac")
        if not javac.is_file():
            javac = shutil.which("javac")
        if not javac:
            err("未找到 javac")
            return False
        r = subprocess.run([javac, "-cp", str(jar), "-d", str(td), str(helper_src)],
                           capture_output=True, text=True)
        if r.returncode != 0:
            err("SignerHelper 编译失败:\n" + r.stderr)
            return False
        # cert-only: keystore, alias, storepass, keypass, dummy, dummy, shaout, cert-only
        run_cmd = [java_bin, "-cp", str(jar) + os.pathsep + str(td),
                   "SignerHelper", ks, alias, storepass, keypass,
                   str(src), str(src), str(sha_file), "cert-only"]
        r = subprocess.run(run_cmd, capture_output=True, text=True)
        if r.returncode != 0:
            warn("证书 SHA-256 获取失败（签名基线将留空）:\n" + r.stdout + r.stderr)
            sha = ""
        else:
            sha = sha_file.read_text().strip() if sha_file.is_file() else ""
            if sha:
                ok(f"签名基线: {sha[:16]}...")

    # 5. 构造 features.cfg
    cfg_bytes = build_features_cfg(features, sha, dex_crcs)
    ok(f"features.cfg: {len(features)} 项功能")

    # 6. 重打包（对齐写入）
    pre_apk = Path(out_dir) / (src.stem + "_plus_unsign.apk")
    info("重打包（zipalign 对齐）...")
    with ZipBuilder(pre_apk) as zb:
        for e in entries:
            name = e["name"]
            align = None
            method = e["method"]
            if name.endswith(".so") and (name.startswith("lib/") or name.startswith("lib\\")):
                align = 4096
                method = zipfile.ZIP_STORED
            elif method == zipfile.ZIP_STORED:
                align = 4
            if name == "AndroidManifest.xml":
                zb.write_entry(name, mres.data, zipfile.ZIP_DEFLATED, ts=e["ts"])
            elif name == "assets/features.cfg":
                continue
            else:
                zb.write_entry(name, e["data"], method, ts=e["ts"], align=align)
        # 注入 features.cfg（无条件覆盖/新增）
        zb.write_entry("assets/features.cfg", cfg_bytes, zipfile.ZIP_DEFLATED)
        ok(f"注入 features.cfg: assets/features.cfg ({len(cfg_bytes)} 字节)")
        # 注入 dex
        zb.write_entry(new_dex_name, dex_bytes, zipfile.ZIP_DEFLATED)
        info(f"注入 DEX: {new_dex_name} ({len(dex_bytes)} 字节)")
        # 注入 so
        injected_abis = []
        for abi in ABIS:
            so = module_dir / "libs" / abi / SO_FILE
            if not so.is_file():
                continue
            target_name = f"lib/{abi}/{SO_FILE}"
            if target_name in name_set:
                warn(f"跳过 {target_name}（已存在同名）")
                continue
            so_data = so.read_bytes()
            zb.write_entry(target_name, so_data, zipfile.ZIP_STORED, align=4096)
            injected_abis.append(abi)
        if injected_abis:
            ok(f"注入 SO: {','.join(injected_abis)}")
        else:
            warn("无 SO 注入（各 ABI 均已存在或缺失资产）")
    ok(f"重打包完成: {pre_apk.name}")

    # 7. 重新签名
    final_apk = Path(out_dir) / (src.stem + "_plus_signed.apk")
    if final_apk.exists():
        final_apk.unlink()
    info("重新签名 (v1+v2) ...")
    helper_src = SCRIPT_DIR / "SignerHelper.java"
    with tempfile.TemporaryDirectory(prefix="frost-sign2-") as td:
        td = Path(td)
        jar = engine_dir / "ironshell.jar"
        javac = Path(java_bin).with_name("javac")
        if not javac.is_file():
            javac = shutil.which("javac")
        r = subprocess.run([javac, "-cp", str(jar), "-d", str(td), str(helper_src)],
                           capture_output=True, text=True)
        if r.returncode != 0:
            err("SignerHelper 编译失败:\n" + r.stderr)
            return False
        run_cmd = [java_bin, "-cp", str(jar) + os.pathsep + str(td),
                   "SignerHelper", ks, alias, storepass, keypass,
                   str(pre_apk), str(final_apk), str(sha_file)]
        r = subprocess.run(run_cmd, capture_output=True, text=True)
        if r.returncode != 0 or not final_apk.is_file():
            err("重新签名失败:\n" + r.stdout + r.stderr)
            return False
    ok(f"签名完成: {final_apk.name}")

    # 8. 验证
    return verify(final_apk, new_dex_name, injected_abis if injected_abis else [],
                  target_pkg, features)


def verify(apk, new_dex, injected_abis, target_pkg, features):
    print(f"{C.DIM}── 产物校验 ──{C.END}")
    ok_flag = True
    try:
        with zipfile.ZipFile(apk) as z:
            names = set(z.namelist())
            # manifest provider
            try:
                mdata = z.read("AndroidManifest.xml")
                found = (b"SecurityCheckProvider" in mdata
                         or "SecurityCheckProvider".encode("utf-16-le") in mdata)
            except KeyError:
                found = False
            print(("  ✓ " if found else "  ✗ ") + "Manifest: SecurityCheckProvider")
            ok_flag = ok_flag and found
            # dex
            dex_ok = new_dex in names
            print(("  ✓ " if dex_ok else "  ✗ ") + f"DEX: {new_dex}")
            ok_flag = ok_flag and dex_ok
            # so
            for abi in injected_abis:
                so_ok = f"lib/{abi}/libsecurity_check.so" in names
                print(("  ✓ " if so_ok else "  ✗ ") + f"SO: lib/{abi}/libsecurity_check.so")
                ok_flag = ok_flag and so_ok
            # features.cfg
            try:
                cfg = z.read("assets/features.cfg").decode("utf-8")
                cfg_ok = cfg.startswith("# ADFXCBNM") and f"signature_sha256=" in cfg
            except KeyError:
                cfg_ok = False
                cfg = ""
            print(("  ✓ " if cfg_ok else "  ✗ ") + "features.cfg: 含签名基线")
            ok_flag = ok_flag and cfg_ok
            # 签名文件
            sig_ok = any(n.startswith("META-INF/") and (n.endswith(".RSA") or n.endswith(".DSA") or n.endswith(".EC"))
                         for n in names) or any(n.startswith("META-INF/") and n.endswith(".SF") for n in names)
            print(("  ✓ " if sig_ok else "  ✗ ") + "签名: META-INF v1 签名存在")
            ok_flag = ok_flag and sig_ok
    except Exception as e:
        err(f"校验异常: {e}")
        return False
    print(f"{C.DIM}── 校验{'通过' if ok_flag else '存在异常'} ──{C.END}")
    return ok_flag


def main():
    p = argparse.ArgumentParser(
        description="将 ADFXCBNM 运行时保护模块注入 APK（FrostShell 二合一加固第二步）",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="示例:\n  python3 merge_protection.py -f app.apk -o out/ \\\n"
               "      --keystore my.jks --alias key0 --storepass 123456\n"
               "  python3 merge_protection.py -f app.apk -o out/ --features root_detect,frida_detect",
    )
    p.add_argument("-f", "--input", required=True, help="输入 APK（建议为 FrostShell 已加固产物）")
    p.add_argument("-o", "--output", default="frost-out", help="输出目录")
    p.add_argument("--engine", default=None, help="engine 目录（含 ironshell.jar）")
    p.add_argument("--java", default=None, help="java 可执行文件路径")
    p.add_argument("--features", default=None, help="逗号分隔功能列表（默认全部 26 项）")
    p.add_argument("--keystore", required=True, help="签名 keystore")
    p.add_argument("--alias", default="key0", help="keystore 别名")
    p.add_argument("--storepass", default="android", help="keystore 密码")
    p.add_argument("--keypass", default="android", help="key 密码")
    args = p.parse_args()

    print(f"{C.B}ADFXCBNM 保护模块注入{C.END}\n")

    module_dir = find_module_dir()
    if module_dir is None:
        err(f"找不到 protect-module 目录（{MODULE_NAME}/dex/{DEX_FILE}）")
        sys.exit(2)
    ok(f"保护模块: {module_dir}")

    engine_dir = args.engine
    if engine_dir is None:
        engine_dir = module_dir.parent
    engine_dir = Path(engine_dir)
    if not (engine_dir / "ironshell.jar").is_file():
        err(f"engine 目录无效（缺 ironshell.jar）: {engine_dir}")
        sys.exit(2)

    if not Path(args.keystore).is_file():
        err(f"keystore 不存在: {args.keystore}")
        sys.exit(3)

    features = None
    if args.features:
        features = [f.strip() for f in args.features.split(",") if f.strip()]

    ok_flag = inject(args.input, args.output, features, engine_dir, module_dir,
                     args.keystore, args.alias, args.storepass, args.keypass,
                     args.java)
    sys.exit(0 if ok_flag else 1)


if __name__ == "__main__":
    main()
