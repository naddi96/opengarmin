#!/usr/bin/env python3
"""Extract embedded FileDescriptorProto schemas from a decompiled APK (smali).

protobuf-java generated classes embed the whole serialized FileDescriptorProto as
a Java string literal. We recover it, unescape it, parse it and render a .proto.
"""
from __future__ import annotations

import glob
import os
import re
import sys

from google.protobuf import descriptor_pb2

CONST_RE = re.compile(r'const-string v\d+, "((?:\\.|[^"\\])*)"')
TYPE = {1: "double", 2: "float", 3: "int64", 4: "uint64", 5: "int32", 6: "fixed64",
        7: "fixed32", 8: "bool", 9: "string", 10: "group", 11: "message", 12: "bytes",
        13: "uint32", 14: "enum", 15: "sfixed32", 16: "sfixed64", 17: "sint32", 18: "sint64"}
LABEL = {1: "optional", 2: "required", 3: "repeated"}


def java_unescape(s: str) -> str:
    out = []
    i = 0
    while i < len(s):
        c = s[i]
        if c == "\\" and i + 1 < len(s):
            n = s[i + 1]
            if n == "u" and i + 5 < len(s):
                out.append(chr(int(s[i + 2:i + 6], 16)))
                i += 6
            elif n == "n":
                out.append("\n"); i += 2
            elif n == "r":
                out.append("\r"); i += 2
            elif n == "t":
                out.append("\t"); i += 2
            elif n == "b":
                out.append("\b"); i += 2
            elif n == "f":
                out.append("\f"); i += 2
            elif n == '"':
                out.append('"'); i += 2
            elif n == "'":
                out.append("'"); i += 2
            elif n == "\\":
                out.append("\\"); i += 2
            else:
                out.append(n); i += 2
        else:
            out.append(c); i += 1
    return "".join(out)


def type_name(tn: str) -> str:
    return tn[1:] if tn.startswith(".") else tn


def render_enum(e, indent=0, out=None):
    out = out if out is not None else []
    pad = "  " * indent
    out.append(f"{pad}enum {e.name} {{")
    for v in e.value:
        out.append(f"{pad}  {v.name} = {v.number};")
    out.append(f"{pad}}}")
    return out


def render_message(m, indent=0, out=None):
    out = out if out is not None else []
    pad = "  " * indent
    out.append(f"{pad}message {m.name} {{")
    for e in m.enum_type:
        render_enum(e, indent + 1, out)
    for n in m.nested_type:
        render_message(n, indent + 1, out)
    oneofs = {o.name for o in m.oneof_decl}
    for f in m.field:
        if f.HasField("oneof_index") and f.oneof_index >= 0:
            continue  # rendered in oneof block below
        if f.type in (descriptor_pb2.FieldDescriptorProto.TYPE_MESSAGE,
                      descriptor_pb2.FieldDescriptorProto.TYPE_ENUM):
            t = type_name(f.type_name)
        else:
            t = TYPE.get(f.type, f"type{f.type}")
        label = ""
        if f.label == descriptor_pb2.FieldDescriptorProto.LABEL_REPEATED:
            label = "repeated "
        out.append(f"{pad}  {label}{t} {f.name} = {f.number};")
    for oi, o in enumerate(m.oneof_decl):
        out.append(f"{pad}  oneof {o.name} {{")
        for f in m.field:
            if f.HasField("oneof_index") and f.oneof_index == oi:
                if f.type in (descriptor_pb2.FieldDescriptorProto.TYPE_MESSAGE,
                              descriptor_pb2.FieldDescriptorProto.TYPE_ENUM):
                    t = type_name(f.type_name)
                else:
                    t = TYPE.get(f.type, f"type{f.type}")
                out.append(f"{pad}    {t} {f.name} = {f.number};")
        out.append(f"{pad}  }}")
    out.append(f"{pad}}}")
    return out


def render(fd) -> str:
    syntax = fd.syntax or "proto2"
    lines = [f'syntax = "{syntax}";', ""]
    if fd.package:
        lines.append(f"package {fd.package};")
        lines.append("")
    for dep in fd.dependency:
        lines.append(f'import "{dep}";')
    if fd.dependency:
        lines.append("")
    for e in fd.enum_type:
        render_enum(e, 0, lines)
        lines.append("")
    for m in fd.message_type:
        render_message(m, 0, lines)
        lines.append("")
    for ext in fd.extension:
        t = type_name(ext.type_name) if ext.type in (11, 14) else TYPE.get(ext.type, "?")
        lines.append(f"extend {type_name(ext.extendee)} {{")
        lines.append(f"  {t} {ext.name} = {ext.number};")
        lines.append("}")
        lines.append("")
    return "\n".join(lines)


def extract(path: str):
    text = open(path, encoding="utf-8", errors="replace").read()
    for mm in CONST_RE.finditer(text):
        try:
            raw = java_unescape(mm.group(1)).encode("latin-1")
        except (UnicodeEncodeError, ValueError):
            continue
        if b".proto" not in raw:
            continue
        fd = descriptor_pb2.FileDescriptorProto()
        try:
            fd.ParseFromString(raw)
        except Exception:
            continue
        if fd.name and fd.name.endswith(".proto"):
            yield fd


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else "apk/apktool_out"
    outdir = sys.argv[2] if len(sys.argv) > 2 else "docs/protos"
    os.makedirs(outdir, exist_ok=True)

    paths = glob.glob(os.path.join(root, "smali*", "com", "garmin", "proto", "generated", "GDI*.smali"))
    seen = set()
    for p in sorted(paths):
        base = os.path.basename(p)
        if "$" in base:
            continue
        for fd in extract(p):
            key = (fd.name, fd.package)
            if key in seen:
                continue
            seen.add(key)
            fname = os.path.join(outdir, fd.name.replace("/", "_"))
            with open(fname, "w", encoding="utf-8") as f:
                f.write(render(fd))
            print(f"{os.path.basename(p):40s} -> {fname}  ({len(fd.message_type)} messages, {len(fd.enum_type)} enums)")
    print(f"\n{len(seen)} proto descriptors extracted to {outdir}/")


if __name__ == "__main__":
    main()
