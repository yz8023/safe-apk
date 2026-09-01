#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
FrostShell 快捷加固脚本 (protect.py)
====================================
一条命令完成 APK/AAB 加固。自动检测运行环境，缺失时给出可执行的修复指引；
可选 --auto-setup 自动下载缺失的 JDK。

用法示例:
    python3 protect.py app.apk
    python3 protect.py app.apk -o out/ --keystore my.jks --alias key0 --storepass 123456
    python3 protect.py app.apk --config protect-config.json
    python3 protect.py --check          # 只检测环境
    python3 protect.py app.apk --auto-setup   # 环境不全时自动下载 JDK

作者: FrostShell  |  许可: 见 LICENSE
"""

import argparse
import json
import os
import platform
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.request
import zipfile
from pathlib import Path

# ── 常量 ──
SCRIPT_DIR = Path(__file__).resolve().parent
# engine 目录：脚本同级的 ../engine，或同级 engine
ENGINE_CANDIDATES = [SCRIPT_DIR / "engine", SCRIPT_DIR.parent / "engine", SCRIPT_DIR]
JAR_NAME = "ironshell.jar"
SHELL_FILES_DIR = "shell-files"
MIN_JDK = 17

# Adoptium (Eclipse Temurin) JDK 21 直链，按平台选择
TEMURIN = {
    ("Linux", "x86_64"): "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.5%2B11/OpenJDK21U-jdk_x64_linux_hotspot_21.0.5_11.tar.gz",
    ("Linux", "aarch64"): "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.5%2B11/OpenJDK21U-jdk_aarch64_linux_hotspot_21.0.5_11.tar.gz",
    ("Darwin", "x86_64"): "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.5%2B11/OpenJDK21U-jdk_x64_mac_hotspot_21.0.5_11.tar.gz",
    ("Darwin", "arm64"): "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.5%2B11/OpenJDK21U-jdk_aarch64_mac_hotspot_21.0.5_11.tar.gz",
    ("Windows", "AMD64"): "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.5%2B11/OpenJDK21U-jdk_x64_windows_hotspot_21.0.5_11.zip",
}


# ── 颜色输出 ──
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


# ── 环境检测 ──
def find_engine_dir():
    """定位含 ironshell.jar + shell-files 的 engine 目录。"""
    for d in ENGINE_CANDIDATES:
        if (d / JAR_NAME).is_file() and (d / SHELL_FILES_DIR).is_dir():
            return d
    return None


def get_java(local_jdk=None):
    """返回可用的 java 可执行文件路径，找不到返回 None。"""
    if local_jdk:
        cand = Path(local_jdk) / "bin" / ("java.exe" if os.name == "nt" else "java")
        if cand.is_file():
            return str(cand)
    # 环境已装的 java
    j = shutil.which("java")
    if j:
        return j
    # 本脚本此前下载的 JDK
    bundled = SCRIPT_DIR / ".jdk"
    if bundled.is_dir():
        for sub in bundled.glob("*/bin/java*"):
            return str(sub)
        for sub in bundled.glob("*/Contents/Home/bin/java*"):  # macOS
            return str(sub)
    return None


def java_version(java_bin):
    """返回主版本号 (int)，失败返回 0。"""
    try:
        out = subprocess.run([java_bin, "-version"], capture_output=True, text=True).stderr
        # 形如: openjdk version "21.0.12"  或  "1.8.0_..."
        import re
        m = re.search(r'version "(\d+)(?:\.(\d+))?', out)
        if not m:
            return 0
        major = int(m.group(1))
        if major == 1 and m.group(2):  # 1.8 老式
            major = int(m.group(2))
        return major
    except Exception:
        return 0


def download_jdk():
    """按平台下载 Temurin JDK 到脚本目录 .jdk/。返回 java 路径或 None。"""
    key = (platform.system(), platform.machine())
    url = TEMURIN.get(key)
    if not url:
        err(f"没有匹配当前平台 {key} 的 JDK 直链，请手动安装 JDK {MIN_JDK}+。")
        return None
    dest = SCRIPT_DIR / ".jdk"
    dest.mkdir(exist_ok=True)
    info(f"下载 JDK 21 ({key[0]}/{key[1]}) ...")
    info(f"  来源: {url}")
    fname = url.split("/")[-1].replace("%2B", "+")
    archive = dest / fname
    try:
        def _hook(count, bsize, total):
            if total > 0:
                pct = min(100, count * bsize * 100 // total)
                print(f"\r    {pct}%", end="", flush=True)
        urllib.request.urlretrieve(url, archive, _hook)
        print()
    except Exception as e:
        err(f"下载失败: {e}")
        return None
    info("解压 ...")
    if fname.endswith(".zip"):
        with zipfile.ZipFile(archive) as z:
            z.extractall(dest)
    else:
        with tarfile.open(archive) as t:
            t.extractall(dest)
    archive.unlink(missing_ok=True)
    j = get_java()
    if j:
        ok(f"JDK 就绪: {j}")
    return j


def check_env(auto_setup=False, local_jdk=None):
    """返回 (java_bin, engine_dir) 或抛出 SystemExit。"""
    print(f"{C.DIM}── 环境检测 ──{C.END}")

    engine = find_engine_dir()
    if engine:
        ok(f"加固引擎: {engine / JAR_NAME}")
        ok(f"壳资产:   {engine / SHELL_FILES_DIR}")
    else:
        err(f"找不到 {JAR_NAME} + {SHELL_FILES_DIR}/。")
        err("请确认 engine/ 目录与本脚本在同一发布包内。")
        raise SystemExit(2)

    java_bin = get_java(local_jdk)
    ver = java_version(java_bin) if java_bin else 0
    if java_bin and ver >= MIN_JDK:
        ok(f"Java: {java_bin} (JDK {ver})")
    else:
        if java_bin:
            warn(f"Java 版本过低: JDK {ver}，需要 JDK {MIN_JDK}+。")
        else:
            warn("未检测到 Java。")
        if auto_setup:
            java_bin = download_jdk()
            if not java_bin or java_version(java_bin) < MIN_JDK:
                err("自动安装 JDK 失败。")
                raise SystemExit(3)
        else:
            err(f"请安装 JDK {MIN_JDK}+，或加 --auto-setup 让脚本自动下载。")
            err("  Ubuntu/Debian:  sudo apt install openjdk-21-jdk")
            err("  macOS:          brew install openjdk@21")
            err("  或手动:         https://adoptium.net/")
            raise SystemExit(3)
    return java_bin, engine


# ── keystore ──
def ensure_keystore(java_bin, args):
    """确保有可用 keystore；未指定则在输出目录生成一个 debug keystore。"""
    if args.keystore:
        ks = Path(args.keystore)
        if not ks.is_file():
            err(f"指定的 keystore 不存在: {ks}")
            raise SystemExit(4)
        return str(ks), args.alias, args.storepass, args.keypass

    # 自动生成 debug keystore（keytool 与 java 同目录）
    keytool = Path(java_bin).with_name("keytool" + (".exe" if os.name == "nt" else ""))
    if not keytool.is_file():
        keytool = shutil.which("keytool")
    if not keytool:
        err("未找到 keytool，无法自动生成签名，请用 --keystore 指定。")
        raise SystemExit(4)

    ks_path = Path(args.output) / "debug.jks"
    Path(args.output).mkdir(parents=True, exist_ok=True)
    if not ks_path.is_file():
        warn("未指定 keystore，自动生成 debug 签名（勿用于正式发布）。")
        subprocess.run([
            str(keytool), "-genkeypair", "-v", "-keystore", str(ks_path),
            "-alias", "frostdebug", "-keyalg", "RSA", "-keysize", "2048",
            "-validity", "10950", "-storepass", "frostshell", "-keypass", "frostshell",
            "-dname", "CN=FrostShell Debug, O=FrostShell, C=CN",
        ], check=True, capture_output=True)
        ok(f"已生成: {ks_path}")
    return str(ks_path), "frostdebug", "frostshell", "frostshell"


def write_config(args, ks, alias, storepass, keypass):
    """生成临时 protect-config.json。"""
    cfg = {
        "shellPkgName": "<random>",
        "signature": {
            "keystore": ks,
            "alias": alias,
            "storepass": storepass,
            "keypass": keypass,
        },
    }
    fd, path = tempfile.mkstemp(suffix=".json", prefix="frost-cfg-")
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        json.dump(cfg, f, ensure_ascii=False, indent=2)
    return path


# ── 主加固流程 ──
def protect(args):
    java_bin, engine = check_env(args.auto_setup, args.local_jdk)

    src = Path(args.input)
    if not src.is_file():
        err(f"输入文件不存在: {src}")
        raise SystemExit(5)

    Path(args.output).mkdir(parents=True, exist_ok=True)

    # 配置：优先用户 --config，否则自动生成
    tmp_cfg = None
    if args.config:
        cfg_path = args.config
    else:
        ks, alias, sp, kp = ensure_keystore(java_bin, args)
        cfg_path = tmp_cfg = write_config(args, ks, alias, sp, kp)

    cmd = [java_bin, "-jar", str(engine / JAR_NAME),
           "-f", str(src), "-c", cfg_path, "-o", str(args.output)]
    for flag in (args.extra or []):
        cmd.append(flag)

    print(f"{C.DIM}── 开始加固 ──{C.END}")
    info(" ".join(cmd))
    # jar 靠自身所在目录定位 shell-files，用 cwd=engine 保证
    rc = subprocess.run(cmd, cwd=str(engine)).returncode
    if tmp_cfg:
        os.unlink(tmp_cfg)

    if rc == 0:
        out = list(Path(args.output).glob("*_signed.apk")) or list(Path(args.output).glob("*.apk"))
        ok("加固完成！")
        if out:
            ok(f"产物: {out[-1]}")
    else:
        err(f"加固失败 (退出码 {rc})，请查看上方日志。")
        raise SystemExit(rc)


def main():
    p = argparse.ArgumentParser(
        description="FrostShell 快捷加固脚本",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="示例:\n  python3 protect.py app.apk\n"
               "  python3 protect.py app.apk --keystore my.jks --alias key0 --storepass 123456\n"
               "  python3 protect.py --check\n",
    )
    p.add_argument("input", nargs="?", help="待加固的 APK/AAB 文件")
    p.add_argument("-o", "--output", default="frost-out", help="输出目录 (默认: frost-out)")
    p.add_argument("-c", "--config", help="自定义 protect-config.json（指定后忽略 keystore 参数）")
    p.add_argument("--keystore", help="签名 keystore 路径（不指定则自动生成 debug 签名）")
    p.add_argument("--alias", default="key0", help="keystore 别名")
    p.add_argument("--storepass", default="android", help="keystore 密码")
    p.add_argument("--keypass", default="android", help="key 密码")
    p.add_argument("--local-jdk", help="指定本地 JDK 目录（含 bin/java）")
    p.add_argument("--auto-setup", action="store_true", help="环境不全时自动下载 JDK")
    p.add_argument("--check", action="store_true", help="只检测环境不加固")
    p.add_argument("--plus", action="store_true",
                   help="二合一加固：FrostShell 函数抽取 + ADFXCBNM 运行时保护（转交 protect_plus.py）")
    p.add_argument("--features", default=None,
                   help="与 --plus 搭配：ADFXCBNM 保护功能列表（逗号分隔，默认全部 26 项）")
    p.add_argument("extra", nargs="*", help="透传给 ironshell.jar 的额外参数，如 -vs --disable-frida-detect")

    args = p.parse_args()

    # 二合一加固：转交 protect_plus.py，移除 --plus/--features 后透传其余参数
    if args.plus:
        plus_script = SCRIPT_DIR / "protect_plus.py"
        sub_cmd = [sys.executable, str(plus_script)]
        argv = sys.argv[1:]
        argv = [a for a in argv if a != "--plus"]
        # 转交 --features（protect_plus 支持）
        sub_cmd += argv
        return subprocess.run(sub_cmd).returncode
    if args.features and not args.plus:
        print(f"{C.Y}[!]{C.END} --features 需要搭配 --plus 使用。")

    print(f"{C.B}FrostShell 快捷加固{C.END}  {C.DIM}protect.py{C.END}\n")

    if args.check:
        try:
            check_env(args.auto_setup, args.local_jdk)
            ok("环境完整，可以加固。")
        except SystemExit as e:
            sys.exit(e.code)
        return

    if not args.input:
        p.print_help()
        sys.exit(1)

    protect(args)


if __name__ == "__main__":
    main()
