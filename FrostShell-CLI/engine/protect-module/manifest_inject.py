#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
manifest_inject.py — AndroidManifest.xml 二进制注入器
======================================================
在已编译的二进制 AndroidManifest.xml（ResXMLTree 格式）中，向 <application> 元素
末尾注入 <provider> 声明（SecurityCheckProvider），用于挂载 ADFXCBNM 运行时保护模块。

逻辑移植自 AndroidHardeningTool 的 AndroidManifestModifier.kt（纯 Python 实现，
无任何 Android/Java 依赖，可直接在桌面环境运行）。
"""

import struct
import sys

RES_XML_TYPE = 0x0003
RES_STRING_POOL_TYPE = 0x0001
RES_XML_RESOURCE_ID_TYPE = 0x0180
RES_XML_START_NAMESPACE_TYPE = 0x0100
RES_XML_END_NAMESPACE_TYPE = 0x0101
RES_XML_START_ELEMENT_TYPE = 0x0102
RES_XML_END_ELEMENT_TYPE = 0x0103

TYPE_STRING = 0x03
TYPE_INT_BOOLEAN = 0x12
TYPE_INT_HEX = 0x11
TYPE_REFERENCE = 0x01
TYPE_INT_DEC = 0x10
TYPE_FLOAT = 0x04
TYPE_DIMENSION = 0x05
TYPE_FRACTION = 0x06
TYPE_DYNAMIC_REFERENCE = 0x1001
TYPE_ATTRIBUTE = 0x02

PROVIDER_CLASS = "com.adfxcbnm.protect.SecurityCheckProvider"
ANDROID_NS = "http://schemas.android.com/apk/res/android"


class ModResult:
    __slots__ = ("data", "modified")

    def __init__(self, data, modified):
        self.data = data
        self.modified = modified


class Reader:
    """带位置追踪的字节流读取器。"""

    def __init__(self, data):
        self.data = data
        self.pos = 0

    def read(self, n=1):
        if n <= 0:
            return b""
        chunk = self.data[self.pos:self.pos + n]
        self.pos += len(chunk)
        return chunk

    def u8(self):
        v = self.data[self.pos] if self.pos < len(self.data) else 0
        self.pos += 1
        return v

    def u16(self):
        v = struct.unpack_from("<H", self.data, self.pos)[0]
        self.pos += 2
        return v

    def u32(self):
        v = struct.unpack_from("<I", self.data, self.pos)[0]
        self.pos += 4
        return v

    def skip(self, n):
        n = max(n, 0)
        self.pos = min(self.pos + n, len(self.data))

    def available(self):
        return len(self.data) - self.pos


class StringPool:
    def __init__(self, strings, is_utf8):
        self.strings = strings
        self.is_utf8 = is_utf8

    def index_of(self, s):
        try:
            return self.strings.index(s)
        except ValueError:
            return -1

    def add(self, s):
        if s not in self.strings:
            self.strings.append(s)

    def encode(self):
        out = bytearray()
        offsets = []
        for s in self.strings:
            offsets.append(len(out))
            out += _encode_string(s, self.is_utf8)
        while len(out) % 4 != 0:
            out.append(0)
        strings_start = 28 + len(self.strings) * 4
        chunk_size = strings_start + len(out)
        head = bytearray()
        head += struct.pack("<HH", RES_STRING_POOL_TYPE, 28)
        head += struct.pack("<I", chunk_size)
        head += struct.pack("<I", len(self.strings))
        head += struct.pack("<I", 0)
        head += struct.pack("<I", 0x100 if self.is_utf8 else 0)
        head += struct.pack("<I", strings_start)
        head += struct.pack("<I", 0)
        for o in offsets:
            head += struct.pack("<i", o)
        return bytes(head) + bytes(out)


def _encode_string(s, utf8):
    if utf8:
        data = s.encode("utf-8")
        char_count, byte_count = len(s), len(data)
        raw = bytearray()
        raw += _enc_len(char_count, 7)
        raw += _enc_len(byte_count, 7)
        raw += data
        raw.append(0)
        return bytes(raw)
    else:
        data = s.encode("utf-16-le")
        char_count = len(s)
        raw = bytearray()
        if char_count < 0x8000:
            raw += struct.pack("<H", char_count)
            raw += data
            raw += b"\x00\x00"
        else:
            high = (char_count & 0x7FFF) | 0x8000
            low = (char_count >> 15) & 0xFFFF
            raw += struct.pack("<H", high)
            raw += struct.pack("<H", low)
            raw += data
            raw += b"\x00\x00"
        return bytes(raw)


def _enc_len(value, shift):
    if value < (1 << shift):
        return bytes([value])
    lo = value & ((1 << shift) - 1)
    hi = value >> shift
    return bytes([lo | (1 << shift), hi])


class Chunk:
    __slots__ = ("kind", "prefix", "uri", "name", "attrs", "is_start", "namespace", "ids")

    def __init__(self, kind):
        self.kind = kind
        self.prefix = None
        self.uri = None
        self.name = None
        self.attrs = None
        self.is_start = None
        self.namespace = None
        self.ids = None


class Attr:
    __slots__ = ("name", "value", "type", "data", "ns")

    def __init__(self, name, value, atype, data, ns=None):
        self.name = name
        self.value = value
        self.type = atype
        self.data = data
        self.ns = ns


def read_string_pool(r):
    base = r.pos
    chunk_type = r.u16()
    header_size = r.u16()
    chunk_size = r.u32()
    if chunk_type != RES_STRING_POOL_TYPE:
        r.skip(chunk_size - (r.pos - base))
        return None
    string_count = r.u32()
    style_count = r.u32()
    flags = r.u32()
    strings_start = r.u32()
    styles_start = r.u32()
    is_utf8 = (flags & 0x100) != 0

    offsets = [r.u32() for _ in range(string_count)]
    strings_abs = base + strings_start
    strings = []
    for off in offsets:
        r.pos = strings_abs + off
        if is_utf8:
            char_count = _read_utf8_len(r, 7)
            byte_count = _read_utf8_len(r, 7)
            if byte_count > 0:
                raw = r.read(byte_count)
                r.read(1)  # 0 padding
                try:
                    strings.append(raw.decode("utf-8"))
                except UnicodeDecodeError:
                    strings.append("")
            else:
                r.read(1)
                strings.append("")
        else:
            char_count = _read_utf16_len(r)
            byte_count = char_count * 2
            if byte_count > 0:
                raw = r.read(byte_count)
                r.read(2)
                strings.append(raw.decode("utf-16-le", errors="replace"))
            else:
                r.read(2)
                strings.append("")
    end_pos = base + chunk_size
    if end_pos > r.pos:
        r.pos = end_pos
    return StringPool(strings, is_utf8)


def _read_utf8_len(r, shift):
    raw = r.u8()
    if raw & (1 << shift):
        lo = raw & ((1 << shift) - 1)
        hi = r.u8()
        return (hi << shift) | lo
    return raw


def _read_utf16_len(r):
    raw = r.u16()
    if raw & 0x8000:
        lo = raw & 0x7FFF
        hi = r.u16()
        return (hi << 15) | lo
    return raw


def read_chunks(r, sp):
    chunks = []
    while r.available() > 0:
        base = r.pos
        ctype = r.u16()
        header_size = r.u16()
        csize = r.u32()
        if ctype == RES_XML_START_NAMESPACE_TYPE:
            line = r.u32()
            comment = r.u32()
            prefix = r.u32()
            uri = r.u32()
            c = Chunk("ns_start")
            c.prefix, c.uri = prefix, uri
            chunks.append(c)
        elif ctype == RES_XML_END_NAMESPACE_TYPE:
            r.skip(csize - (r.pos - base))
        elif ctype == RES_XML_START_ELEMENT_TYPE:
            line = r.u32()
            comment = r.u32()
            ns_idx = r.u32()
            name_idx = r.u32()
            attr_start = r.u16()
            attr_size = r.u16()
            attr_count = r.u16()
            id_index = r.u16()
            class_index = r.u16()
            style_index = r.u16()
            ns = sp.strings[ns_idx] if 0 <= ns_idx < len(sp.strings) else None
            name = sp.strings[name_idx] if 0 <= name_idx < len(sp.strings) else ""
            attrs = []
            for _ in range(attr_count):
                a_ns = r.u32()
                a_name = r.u32()
                a_raw = r.u32()
                a_size = r.u16()
                a_res0 = r.u8()
                a_type = r.u8()
                a_data = r.u32()
                ans = sp.strings[a_ns] if 0 <= a_ns < len(sp.strings) else None
                an = sp.strings[a_name] if 0 <= a_name < len(sp.strings) else ""
                av = ""
                if 0 <= a_raw < len(sp.strings):
                    av = sp.strings[a_raw]
                else:
                    av = _parse_attr_value(a_type, a_data, sp)
                attrs.append(Attr(an, av, a_type, a_data, ans))
            c = Chunk("element")
            c.name = name
            c.attrs = attrs
            c.is_start = True
            c.namespace = ns
            chunks.append(c)
        elif ctype == RES_XML_END_ELEMENT_TYPE:
            line = r.u32()
            comment = r.u32()
            ns_idx = r.u32()
            name_idx = r.u32()
            ns = sp.strings[ns_idx] if 0 <= ns_idx < len(sp.strings) else None
            name = sp.strings[name_idx] if 0 <= name_idx < len(sp.strings) else ""
            c = Chunk("element")
            c.name = name
            c.is_start = False
            c.namespace = ns
            chunks.append(c)
        elif ctype == RES_XML_RESOURCE_ID_TYPE:
            count = (csize - header_size) // 4
            ids = [r.u32() for _ in range(count)]
            c = Chunk("res_ids")
            c.ids = ids
            chunks.append(c)
        else:
            r.skip(csize - (r.pos - base))
    return chunks


def _parse_attr_value(atype, data, sp):
    if atype == TYPE_REFERENCE:
        return "@0x%x" % data
    if atype == TYPE_ATTRIBUTE:
        return "?0x%x" % data
    if atype == TYPE_INT_BOOLEAN:
        return "true" if data != 0 else "false"
    if atype == TYPE_INT_HEX:
        return "0x%x" % data
    if atype == TYPE_INT_DEC:
        return str(data)
    if atype == TYPE_FLOAT:
        import struct as _s
        return repr(_s.unpack("<f", _s.pack("<I", data))[0])
    if atype == TYPE_DIMENSION:
        return str(data)
    if atype == TYPE_FRACTION:
        return str(data)
    if atype == TYPE_STRING:
        return sp.strings[data] if 0 <= data < len(sp.strings) else ""
    return str(data)


def find_application_end(elements, start):
    depth = 0
    for i in range(start + 1, len(elements)):
        n = elements[i]
        if n.name == "application":
            if n.is_start:
                depth += 1
            else:
                depth -= 1
                if depth < 0:
                    return i
    return len(elements) - 1


def write_namespace(out, ctype, prefix, uri):
    out += struct.pack("<HH", ctype, 16)
    out += struct.pack("<I", 24)
    out += struct.pack("<iiii", 0, -1, prefix, uri)


def build_index_map(strings):
    return {s: i for i, s in enumerate(strings)}


def write_start_element(out, e, index_map):
    ns_idx = index_map.get(e.namespace, -1) if e.namespace else -1
    name_idx = index_map.get(e.name, -1)
    attr_bytes = bytearray()
    for attr in (e.attrs or []):
        a_ns = index_map.get(attr.ns, -1) if attr.ns else -1
        a_name = index_map.get(attr.name, -1)
        a_raw = index_map.get(attr.value, -1) if attr.type == TYPE_STRING else -1
        attr_bytes += struct.pack("<iii", a_ns, a_name, a_raw)
        attr_bytes += struct.pack("<H", 20)
        attr_bytes += b"\x00"
        attr_bytes += struct.pack("<B", attr.type & 0xFF)
        data_val = a_raw if attr.type == TYPE_STRING else attr.data
        attr_bytes += struct.pack("<I", data_val)
    chunk_size = 16 + 20 + len(attr_bytes)
    out += struct.pack("<HH", RES_XML_START_ELEMENT_TYPE, 16)
    out += struct.pack("<I", chunk_size)
    out += struct.pack("<ii", 0, -1)
    out += struct.pack("<ii", ns_idx, name_idx)
    out += struct.pack("<HHHHHH", 20, 20, len(e.attrs or []), 0, 0, 0)
    out += bytes(attr_bytes)


def write_end_element(out, e, index_map):
    ns_idx = index_map.get(e.namespace, -1) if e.namespace else -1
    name_idx = index_map.get(e.name, -1)
    out += struct.pack("<HH", RES_XML_END_ELEMENT_TYPE, 16)
    out += struct.pack("<I", 24)
    out += struct.pack("<iiii", 0, -1, ns_idx, name_idx)


def _validate(data):
    if len(data) < 8:
        return False
    xml_type, header_size = struct.unpack_from("<HH", data, 0)
    file_size = struct.unpack_from("<I", data, 4)[0]
    if xml_type != RES_XML_TYPE or header_size != 8 or file_size != len(data):
        return False
    r = Reader(data)
    r.skip(8)
    base = r.pos
    ctype = r.u16()
    if ctype != RES_STRING_POOL_TYPE:
        return False
    r.skip(2)
    sp_size = r.u32()
    r.pos = base + sp_size
    element_count = 0
    while r.available() > 0:
        pos = r.pos
        t = r.u16()
        r.skip(2)
        size = r.u32()
        if t in (RES_XML_START_ELEMENT_TYPE, RES_XML_END_ELEMENT_TYPE):
            element_count += 1
        r.pos = pos + size
    return element_count > 0


def modify_manifest(orig, target_package="", log=None):
    """注入 SecurityCheckProvider 到 AndroidManifest.xml，返回 ModResult。"""
    def dbg(msg):
        if log:
            log(msg)

    if len(orig) < 8:
        dbg("manifest too small: %d" % len(orig))
        return ModResult(orig, False)

    r = Reader(orig)
    xml_type = r.u16()
    header_size = r.u16()
    file_size = r.u32()
    if xml_type != RES_XML_TYPE or header_size != 8:
        dbg("bad xmlType=0x%x headerSize=%d" % (xml_type, header_size))
        return ModResult(orig, False)

    sp = read_string_pool(r)
    if sp is None or not sp.strings:
        dbg("string pool empty")
        return ModResult(orig, False)

    chunks = read_chunks(r, sp)
    namespaces = [(c.prefix, c.uri) for c in chunks if c.kind == "ns_start"]
    elements = [c for c in chunks if c.kind == "element"]

    dbg("parsed %d elements, %d ns, %d strings, utf8=%s" % (
        len(elements), len(namespaces), len(sp.strings), sp.is_utf8))

    app_idx = -1
    for i, e in enumerate(elements):
        if e.name == "application" and e.is_start:
            app_idx = i
            break
    if app_idx < 0:
        dbg("no application tag found")
        return ModResult(orig, False)

    authority = "com.adfxcbnm.authority" + (".%s" % target_package if target_package else "")

    needed = [PROVIDER_CLASS, authority, "name", "authorities",
              "exported", "false", "provider", ANDROID_NS]
    for s in needed:
        sp.add(s)

    ins_idx = find_application_end(elements, app_idx)
    provider_attrs = [
        Attr("name", PROVIDER_CLASS, TYPE_STRING, 0, ANDROID_NS),
        Attr("authorities", authority, TYPE_STRING, 0, ANDROID_NS),
        Attr("exported", "false", TYPE_INT_BOOLEAN, 0, ANDROID_NS),
    ]
    ps = Chunk("element")
    ps.name = "provider"
    ps.attrs = provider_attrs
    ps.is_start = True
    ps.namespace = None
    pe = Chunk("element")
    pe.name = "provider"
    pe.is_start = False
    pe.namespace = None

    elements.insert(ins_idx, ps)
    elements.insert(ins_idx + 1, pe)

    out = bytearray()
    out += sp.encode()
    res_ids = []
    for c in chunks:
        if c.kind == "res_ids":
            res_ids.extend(c.ids)
    if res_ids:
        out += struct.pack("<HH", RES_XML_RESOURCE_ID_TYPE, 8)
        out += struct.pack("<I", 8 + len(res_ids) * 4)
        for rid in res_ids:
            out += struct.pack("<I", rid)

    index_map = build_index_map(sp.strings)
    for prefix, uri in namespaces:
        write_namespace(out, RES_XML_START_NAMESPACE_TYPE, prefix, uri)
    for e in elements:
        if e.is_start:
            write_start_element(out, e, index_map)
        else:
            write_end_element(out, e, index_map)
    for prefix, uri in namespaces:
        write_namespace(out, RES_XML_END_NAMESPACE_TYPE, prefix, uri)

    result = bytearray()
    result += struct.pack("<HH", RES_XML_TYPE, 8)
    result += struct.pack("<I", 8 + len(out))
    result += out

    result_data = bytes(result)
    if not _validate(result_data):
        dbg("manifest validation failed, using original")
        return ModResult(orig, False)

    dbg("validate: OK")
    return ModResult(result_data, True)


def main():
    if len(sys.argv) < 3:
        print("用法: python3 manifest_inject.py <AndroidManifest.xml> <out.xml> [targetPackage]")
        sys.exit(1)
    src = sys.argv[1]
    dst = sys.argv[2]
    target_pkg = sys.argv[3] if len(sys.argv) > 3 else ""
    with open(src, "rb") as f:
        orig = f.read()
    res = modify_manifest(orig, target_package=target_pkg,
                          log=lambda m: print("[manifest]", m))
    with open(dst, "wb") as f:
        f.write(res.data)
    if res.modified:
        print("已注入 provider: %s (authority=com.adfxcbnm.authority%s)" % (
            PROVIDER_CLASS, ".%s" % target_pkg if target_pkg else ""))
    else:
        print("注入失败，产物为原始 manifest")
        sys.exit(2)


if __name__ == "__main__":
    main()
