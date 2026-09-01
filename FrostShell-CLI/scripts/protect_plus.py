#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
protect_plus.py — FrostShell 二合一加固（函数抽取 + ADFXCBNM 运行时保护）
========================================================================
完整流程分两阶段，两层保护互不冲突：

  阶段一（FrostShell）：对原始 APK 做函数抽取加固 + 签名
        java -jar ironshell.jar -f app.apk -c cfg.json -o out
  阶段二（ADFXCBNM）：向加固产物注入运行时检测/保护模块并重新签名
        merge_protection.py -f out/xxx_signed.apk ...

产物：out/xxx_plus_signed.apk，同时具备
      * FrostShell：dex 函数抽取、native 回填、反 dump/反调试/反 Frida/CRC 自校验
      * ADFXCBNM ：root/Magisk/Frida/Xposed/模拟器/注入/dump/代理等 26 项运行时检测

用法:
    python3 protect_plus.py app.apk
    python3 protect_plus.py app.apk --keystore my.jks --alias key0 --storepass 123456
    python3 protect_plus.py app.apk --features root_detect,frida_detect --noisy-log
    python3 protect_plus.py app.apk --skip-frost          # 仅注入保护模块（输入已是加固产物）
"""

import argparse
import importlib.util
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
PROTECT_PY = SCRIPT_DIR / "protect.py"
MERGE_PY = SCRIPT_DIR / "merge_protection.py"


def load_sibling(name, path):
    spec = importlib.util.spec_from_file_location(name, str(path))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


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


def main():
    p = argparse.ArgumentParser(
        description="FrostShell 二合一加固：函数抽取 + ADFXCBNM 运行时保护",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="示例:\n  python3 protect_plus.py app.apk\n"
               "  python3 protect_plus.py app.apk --keystore my.jks --alias key0 --storepass 123456\n"
               "  python3 protect_plus.py app.apk --skip-frost\n",
    )
    p.add_argument("input", nargs="?", help="待加固的 APK/AAB 文件")
    p.add_argument("-o", "--output", default="frost-out", help="输出目录 (默认: frost-out)")
    p.add_argument("-c", "--config", help="protect-config.json（指定后忽略 keystore 参数）")
    p.add_argument("--keystore", help="签名 keystore 路径（不指定则自动生成 debug 签名）")
    p.add_argument("--alias", default="key0", help="keystore 别名")
    p.add_argument("--storepass", default="android", help="keystore 密码")
    p.add_argument("--keypass", default="android", help="key 密码")
    p.add_argument("--features", default=None,
                   help="ADFXCBNM 保护功能列表（逗号分隔，默认全部 26 项）")
    p.add_argument("--skip-frost", action="store_true",
                   help="跳过 FrostShell 函数抽取，仅注入 ADFXCBNM 保护模块（输入应为已加固产物）")
    p.add_argument("--local-jdk", help="指定本地 JDK 目录（含 bin/java）")
    p.add_argument("--auto-setup", action="store_true", help="环境不全时自动下载 JDK")
    p.add_argument("--check", action="store_true", help="只检测环境不加固")
    p.add_argument("extra", nargs="*", help="透传给 ironshell.jar 的额外参数，如 -vs --disable-frida-detect")

    args = p.parse_args()

    print(f"{C.B}FrostShell 二合一加固{C.END}  {C.DIM}protect_plus.py{C.END}\n")

    protect = load_sibling("protect", PROTECT_PY)
    merge = load_sibling("merge_protection", MERGE_PY)

    if args.check:
        try:
            protect.check_env(args.auto_setup, args.local_jdk)
            merge.find_module_dir() and None
            ok("环境完整，可以二合一加固。")
        except SystemExit as e:
            sys.exit(e.code)
        return

    if not args.input:
        p.print_help()
        sys.exit(1)

    src = Path(args.input)
    if not src.is_file():
        err(f"输入文件不存在: {src}")
        sys.exit(5)

    Path(args.output).mkdir(parents=True, exist_ok=True)

    # ── 确定 keystore ──
    java_bin, engine = protect.check_env(args.auto_setup, args.local_jdk)
    if args.config:
        with open(args.config, "r", encoding="utf-8") as f:
            cfg = json.load(f)
        ks = cfg["signature"]["keystore"]
        alias = cfg["signature"].get("alias", args.alias)
        sp = cfg["signature"].get("storepass", args.storepass)
        kp = cfg["signature"].get("keypass", args.keypass)
    else:
        ks, alias, sp, kp = protect.ensure_keystore(java_bin, args)

    frost_out = None

    # ── 阶段一：FrostShell 函数抽取加固 ──
    if not args.skip_frost:
        print(f"{C.DIM}── 阶段一：FrostShell 函数抽取加固 ──{C.END}")
        p1_args = argparse.Namespace(
            input=args.input, output=args.output,
            config=args.config, keystore=args.keystore,
            alias=args.alias, storepass=args.storepass, keypass=args.keypass,
            local_jdk=args.local_jdk, auto_setup=args.auto_setup,
            check=False, extra=args.extra,
        )
        try:
            protect.protect(p1_args)
        except SystemExit as e:
            if e.code != 0:
                err("FrostShell 函数抽取加固失败，已中止。")
                sys.exit(e.code)
        candidates = sorted(Path(args.output).glob("*_signed.apk"), key=lambda f: f.stat().st_mtime)
        if candidates:
            frost_out = candidates[-1]
            if frost_out.stat().st_size == 0:
                warn(f"{frost_out.name} 为空（引擎内部签名失败），回退使用未签名产物")
                frost_out = None
        if frost_out is None:
            candidates = sorted(Path(args.output).glob("*_unsign.apk"), key=lambda f: f.stat().st_mtime)
            if candidates and candidates[-1].stat().st_size > 0:
                frost_out = candidates[-1]
        if frost_out is None:
            err("未找到 FrostShell 加固产物（*_signed.apk / *_unsign.apk）。")
            sys.exit(6)
        ok(f"阶段一产物: {frost_out.name}")
    else:
        # 跳过函数抽取：输入本身即为加固产物
        frost_out = src
        info(f"跳过函数抽取，直接注入保护模块: {src.name}")

    # ── 阶段二：ADFXCBNM 保护模块注入 ──
    print(f"{C.DIM}── 阶段二：ADFXCBNM 运行时保护注入 ──{C.END}")
    features = None
    if args.features:
        features = [f.strip() for f in args.features.split(",") if f.strip()]

    ok_flag = merge.inject(
        frost_out, args.output, features, engine, merge.find_module_dir(),
        ks, alias, sp, kp, java_bin,
    )

    if ok_flag:
        finals = sorted(Path(args.output).glob("*_plus_signed.apk"), key=lambda f: f.stat().st_mtime)
        print(f"{C.G}二合一加固完成！{C.END}")
        if finals:
            ok(f"最终产物: {finals[-1]}")
    else:
        err("二合一加固失败。")
        sys.exit(1)


if __name__ == "__main__":
    main()
