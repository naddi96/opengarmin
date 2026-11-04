#!/usr/bin/env python3
"""Inspect a Garmin `.gsp` update package without extracting firmware.

A `.gsp` is usually a ZIP with `package.xml`, `manifest.xml` and `bundle.gsp`.
The manifest is readable and identifies the product and every component
version. `bundle.gsp` itself is an opaque, high-entropy Garmin container; this
tool reports its header and alignment instead of pretending it is a GCD file.

Usage:
    python3 tools/inspect_gsp.py path/to/gupdate.gsp
"""

from __future__ import annotations

import argparse
import hashlib
import math
import struct
import sys
import zipfile
from collections import Counter
from pathlib import Path
from xml.etree import ElementTree


GSP_BUNDLE_MAGIC = bytes.fromhex("b1b5bf96")


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def entropy(data: bytes) -> float:
    if not data:
        return 0.0
    counts = Counter(data)
    total = len(data)
    return -sum((n / total) * math.log2(n / total) for n in counts.values())


def first_zip_local_header(data: bytes) -> int | None:
    return data.find(b"PK\x03\x04")


def parse_manifest(xml_bytes: bytes) -> dict:
    root = ElementTree.fromstring(xml_bytes)
    updates = []
    for node in root.findall("update"):
        updates.append(
            {
                "filename": node.findtext("filename"),
                "version": node.findtext("version"),
                "part_number": node.findtext("partNumber"),
                "force_version": node.findtext("forceVersion"),
                "nonvol_clear_version": node.findtext("nonvolClearVersion"),
            }
        )
    return {
        "part_number": root.findtext("partNumber"),
        "version": root.findtext("version"),
        "updates": updates,
    }


def printable_strings(data: bytes, minimum: int = 6) -> list[str]:
    out = []
    current = bytearray()
    for byte in data:
        if 32 <= byte < 127:
            current.append(byte)
        else:
            if len(current) >= minimum:
                out.append(current.decode("ascii"))
            current.clear()
    if len(current) >= minimum:
        out.append(current.decode("ascii"))
    return out


def classify_bundle(bundle: bytes) -> list[str]:
    notes = []
    if bundle[:4] == GSP_BUNDLE_MAGIC:
        notes.append(
            "starts with the Garmin bundle magic b1b5bf96; the next four bytes "
            "vary between firmware versions (nonce/checksum field, not a size)"
        )
    if bundle[:2] == b"PK":
        notes.append("starts with a ZIP local header: bundle is an ordinary ZIP")
    if bundle[:2] in (b"\x1f\x8b", b"\x78\x9c", b"\x78\xda"):
        notes.append("starts with a gzip/zlib header: bundle may be compressed")
    if len(bundle) % 16 == 8:
        notes.append(
            "length is 8 + a multiple of 16, consistent with an 8-byte header "
            "followed by block-cipher ciphertext"
        )
    if entropy(bundle) > 7.99:
        notes.append("entropy is ~8 bits/byte: the payload has no readable structure")
    if not notes:
        notes.append("no known container signature matched")
    return notes


def format_table(rows: list[dict]) -> str:
    if not rows:
        return "  (none)"
    headers = ["filename", "version", "part_number", "force", "nonvol_clear"]
    keys = ["filename", "version", "part_number", "force_version", "nonvol_clear_version"]
    widths = [len(h) for h in headers]
    for row in rows:
        for i, key in enumerate(keys):
            widths[i] = max(widths[i], len(str(row.get(key) or "")))
    out = []
    out.append("  " + "  ".join(h.ljust(widths[i]) for i, h in enumerate(headers)))
    out.append("  " + "  ".join("-" * w for w in widths))
    for row in rows:
        out.append(
            "  "
            + "  ".join(str(row.get(key) or "").ljust(widths[i]) for i, key in enumerate(keys))
        )
    return "\n".join(out)


def inspect(path: Path) -> int:
    raw = path.read_bytes()
    print(f"file:       {path}")
    print(f"size:       {len(raw)} bytes")
    print(f"sha256:     {sha256(raw)}")

    prefix = first_zip_local_header(raw)
    if prefix is not None:
        print(f"zip starts: offset {prefix} (prepended header/signature: {prefix} bytes)")
        if prefix:
            print(f"prefix hex: {raw[:min(prefix, 64)].hex()}")
    else:
        print("zip starts: no ZIP local header found")
        return 2

    try:
        with zipfile.ZipFile(path) as archive:
            names = archive.namelist()
            print(f"entries:    {', '.join(names)}")
            package = archive.read("package.xml") if "package.xml" in names else b""
            manifest = archive.read("manifest.xml") if "manifest.xml" in names else b""
            if package:
                print(f"package:    {package.decode('utf-8', errors='replace')}")
            if manifest:
                parsed = parse_manifest(manifest)
                print(f"part:       {parsed['part_number']}  version {parsed['version']}")
                print("components:")
                print(format_table(parsed["updates"]))
            if "bundle.gsp" not in names:
                print("bundle:     no bundle.gsp entry")
                return 0
            bundle = archive.read("bundle.gsp")
    except (KeyError, zipfile.BadZipFile, ElementTree.ParseError) as exc:
        print(f"error:      {exc}")
        return 2

    print()
    print("bundle.gsp")
    print(f"  size:      {len(bundle)} bytes")
    print(f"  sha256:    {sha256(bundle)}")
    print(f"  entropy:   {entropy(bundle):.6f} bits/byte")
    print(f"  header:    {bundle[:32].hex()}")
    print(f"  length:    mod 16 = {len(bundle) % 16}, mod 8 = {len(bundle) % 8}")
    strings = printable_strings(bundle)
    markers = [
        b"GARMIN",
        b"GFDI",
        b"Multi-Link",
        b"FIT_TYPE",
        b"Forerunner",
        b"GUPDATE",
        b".GCD",
        b"<data>",
        b"PK\x03\x04",
    ]
    hits = [marker.decode("ascii", errors="replace") for marker in markers if marker in bundle]
    print(f"  printable: {len(strings)} ASCII run(s) of >= 6 chars (random-looking)")
    print(f"  markers:   {', '.join(hits) if hits else 'none'}")
    if len(bundle) >= 8:
        print(f"  magic:     {bundle[:4].hex()}  field: {bundle[4:8].hex()}")
        print(
            "  header fields: "
            + ", ".join(
                f"le32[{i}]={struct.unpack_from('<I', bundle, i)[0]}"
                for i in (0, 4)
            )
        )
    print("  notes:")
    for note in classify_bundle(bundle):
        print(f"    - {note}")
    print()
    print(
        "The bundle is not a GCD/RGN image and contains no readable protocol\n"
        "strings. Firmware-level protocol verification needs a decryption key\n"
        "from the device bootloader; use the Garmin Connect APK, captured BLE\n"
        "frames, or an unencrypted GCD from an older device for that."
    )
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("package", type=Path, help="path to gupdate.gsp")
    args = parser.parse_args()
    if not args.package.is_file():
        print(f"not a file: {args.package}", file=sys.stderr)
        return 2
    return inspect(args.package)


if __name__ == "__main__":
    raise SystemExit(main())
