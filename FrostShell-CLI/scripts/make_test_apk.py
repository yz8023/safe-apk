#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""构造最小可加固测试 APK（无 Android SDK 依赖）。

用法:
    python3 make_test_apk.py [输出目录]   # 默认输出到本脚本旁的 build/test_app.apk
"""
import argparse
import os
import struct
import subprocess
import sys
import zipfile
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
BASE = SCRIPT_DIR.parent

argp = argparse.ArgumentParser(description="构造最小可加固测试 APK")
argp.add_argument("outdir", nargs="?", default=str(SCRIPT_DIR / "build"))
args = argp.parse_args()

OUT = Path(args.outdir)
OUT.mkdir(parents=True, exist_ok=True)

sys.path.insert(0, str(BASE / "engine" / "protect-module"))
import manifest_inject as mi

def build_empty_arsc():
    """构造合法的最小空资源表 resources.arsc。

    PackageManager 解析空/损坏的 resources.arsc 会报"安装包损坏/解析失败"，
    此处生成结构完整的空 RES_TABLE（含空 string pool 与一个空 package 块）。
    """
    # ResStringPool_header(28B): type,headerSize,size,stringCount,styleCount,flags,stringsStart,stylesStart
    pool = struct.pack("<HHIIIIII", 0x0001, 0x001C, 0x001C, 0, 0, 0, 0, 0)
    # ResTable_package header: type=0x0200, headerSize=0x0144, size=0x0144
    #   + id(4B) + name[128] char16(256B) + typeStrings pool(28B) + keyStrings pool(28B)
    pkg_hdr = struct.pack("<HHII", 0x0200, 0x0144, 0x0144, 0x7F)
    pkg = pkg_hdr + (b"\x00" * 256) + pool + pool
    # ResTable header: type=0x0002, headerSize=0x000C, size, packageCount
    tbl = struct.pack("<HHII", 0x0002, 0x000C, 0x000C + len(pkg), 1)
    return tbl + pkg


PKG = "com.example.test"
CLASS_DEX = OUT / "classes.dex"
MANIFEST = OUT / "AndroidManifest.xml"
APK = OUT / "test_app.apk"

# 1) dx 编译 classes.dex
JAR = BASE / "engine" / "ironshell.jar"
SRC_JAVA = OUT / "src" / "TestApp.java"
CLS_DIR = OUT / "classes"
CLS_DIR.mkdir(parents=True, exist_ok=True)
CLASSES = list((CLS_DIR / "com" / "example" / "test").glob("*.class"))
if not CLASSES:
    SRC_JAVA.parent.mkdir(parents=True, exist_ok=True)
    SRC_JAVA.write_text(
        "package com.example.test;\n"
        "public class TestApp {\n"
        "    public static int add(int a, int b) { return a + b; }\n"
        "    public static String hello() { return \"frostshell\"; }\n"
        "    private static int secret() { int x = 0; for (int i = 0; i < 10; i++) x += i; return x; }\n"
        "}\n")
    javac = subprocess.run(["javac", "-source", "8", "-target", "8",
                            "-d", str(CLS_DIR), str(SRC_JAVA)],
                           capture_output=True, text=True)
    if javac.returncode != 0:
        print(javac.stdout); print(javac.stderr); sys.exit("javac failed")
    CLASSES = list((CLS_DIR / "com" / "example" / "test").glob("*.class"))
    if not CLASSES:
        sys.exit("javac produced no class files")
rel = "com/example/test/TestApp.class"
r = subprocess.run(
    ["java", "-cp", str(JAR), "com.android.dx.command.Main",
     "--dex", "--output=" + str(CLASS_DEX), rel],
    capture_output=True, text=True, cwd=str(OUT / "classes"))
if r.returncode != 0:
    print(r.stdout); print(r.stderr); sys.exit("dx failed")
print("dex:", CLASS_DEX.stat().st_size, "bytes")

# 2) 构造二进制 AndroidManifest.xml
strs = mi.StringPool([], True)
add = strs.add
PKG_URI = "http://schemas.android.com/apk/res/android"
for s in ["manifest", "application", "activity", "intent-filter", "action", "category",
          "package", "name", "versionCode", "versionName", "label",
          "android", PKG_URI, PKG, "com.example.test.TestApp",
          "android.intent.action.MAIN", "android.intent.category.LAUNCHER",
          "MAIN", "LAUNCHER", "1", "1.0", "TestApp", "false", "exported"]:
    add(s)

out = bytearray()
out += strs.encode()
res_ids = [
    0x01010210,  # package (manifest)
    0x0101021b,  # versionCode
    0x0101021c,  # versionName
    0x0101023f,  # label
    0x01010001,  # theme?
    0x01010003,  # name (android:name)
    0x01010010,  # exported? use 0x01010010
]
out += struct.pack("<HH", mi.RES_XML_RESOURCE_ID_TYPE, 8)
out += struct.pack("<I", 8 + len(res_ids) * 4)
for rid in res_ids:
    out += struct.pack("<I", rid)

im = mi.build_index_map(strs.strings)

def S(name, ns=None):
    e = mi.Chunk("element")
    e.name = name
    e.namespace = ns
    e.attrs = []
    e.is_start = True
    return e

# namespace start
mi.write_namespace(out, mi.RES_XML_START_NAMESPACE_TYPE,
                   strs.index_of("android"), strs.index_of(PKG_URI))

# <manifest>
m = S("manifest")
m.attrs = [
    mi.Attr("package", PKG, mi.TYPE_STRING, 0, None),
    mi.Attr("versionCode", "1", mi.TYPE_INT_DEC, 1, "android"),
    mi.Attr("versionName", "1.0", mi.TYPE_STRING, 0, "android"),
]
mi.write_start_element(out, m, im)

# <application>
a = S("application")
a.attrs = [
    mi.Attr("label", "TestApp", mi.TYPE_STRING, 0, "android"),
    mi.Attr("name", PKG, mi.TYPE_STRING, 0, "android"),
]
mi.write_start_element(out, a, im)

# <activity android:name=... android:exported=false>
act = S("activity")
act.attrs = [
    mi.Attr("name", "com.example.test.TestApp", mi.TYPE_STRING, 0, "android"),
    mi.Attr("exported", "false", mi.TYPE_INT_BOOLEAN, 0, "android"),
]
mi.write_start_element(out, act, im)

# <intent-filter>
inf = S("intent-filter")
mi.write_start_element(out, inf, im)
# <action android:name=.../>
action = S("action")
action.attrs = [mi.Attr("name", "android.intent.action.MAIN", mi.TYPE_STRING, 0, "android")]
mi.write_start_element(out, action, im)
mi.write_end_element(out, action, im)
# <category android:name=.../>
cat = S("category")
cat.attrs = [mi.Attr("name", "android.intent.category.LAUNCHER", mi.TYPE_STRING, 0, "android")]
mi.write_start_element(out, cat, im)
mi.write_end_element(out, cat, im)
mi.write_end_element(out, inf, im)  # /intent-filter

mi.write_end_element(out, act, im)  # /activity
mi.write_end_element(out, a, im)    # /application
mi.write_end_element(out, m, im)    # /manifest

mi.write_namespace(out, mi.RES_XML_END_NAMESPACE_TYPE,
                   strs.index_of("android"), strs.index_of(PKG_URI))

data = bytearray()
data += struct.pack("<HH", mi.RES_XML_TYPE, 8)
data += struct.pack("<I", 8 + len(out))
data += out
MANIFEST.write_bytes(bytes(data))
print("manifest:", MANIFEST.stat().st_size, "bytes")

# 3) 打包 APK（zip，无签名，稍后由加固流程签名）
with zipfile.ZipFile(APK, "w", zipfile.ZIP_DEFLATED) as z:
    z.write(MANIFEST, "AndroidManifest.xml")
    z.write(CLASS_DEX, "classes.dex")
    z.writestr("resources.arsc", build_empty_arsc())
print("apk:", APK.stat().st_size, "bytes")
