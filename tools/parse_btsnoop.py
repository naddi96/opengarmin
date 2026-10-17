#!/usr/bin/env python3
"""Parse an Android btsnoop HCI log and extract ATT traffic.

Focus: reconstruct the Garmin Multi-Link / GFDI stream exchanged between the
Garmin Connect app and the watch.

Usage:
  python3 tools/parse_btsnoop.py captures/btsnooz_hci.log            # summary
  python3 tools/parse_btsnoop.py captures/btsnooz_hci.log --dump     # all ATT ops
  python3 tools/parse_btsnoop.py captures/btsnooz_hci.log --gfdi     # decoded GFDI
"""
from __future__ import annotations

import argparse
import json
import struct
import sys
from dataclasses import dataclass, field

# --- our own protocol primitives (kept in sync with protocol/) -------------
CRC_TABLE = [0x0000, 0xCC01, 0xD801, 0x1400, 0xF001, 0x3C00, 0x2800, 0xE401,
             0xA001, 0x6C00, 0x7800, 0xB401, 0x5000, 0x9C01, 0x8801, 0x4400]


def crc16(data: bytes) -> int:
    crc = 0
    for b in data:
        crc = ((crc >> 4) & 0x0FFF) ^ CRC_TABLE[crc & 0x0F] ^ CRC_TABLE[b & 0x0F]
        crc = ((crc >> 4) & 0x0FFF) ^ CRC_TABLE[crc & 0x0F] ^ CRC_TABLE[(b >> 4) & 0x0F]
    return crc & 0xFFFF


def cobs_decode(data: bytes) -> bytes:
    if len(data) < 4 or data[0] != 0 or data[-1] != 0:
        return b""
    buf = data[1:-1]
    out = bytearray()
    i = 0
    while i < len(buf):
        code = buf[i]
        i += 1
        if code == 0:
            break
        n = code - 1
        out.extend(buf[i:i + n])
        i += n
        if code != 0xFF and i < len(buf):
            out.append(0)
    return bytes(out)


def parse_gfdi(data: bytes):
    if len(data) < 6:
        return None
    size = struct.unpack_from("<H", data, 0)[0]
    if size != len(data):
        return None
    if crc16(data[:size - 2]) != struct.unpack_from("<H", data, size - 2)[0]:
        return None
    raw_type = struct.unpack_from("<H", data, 2)[0]
    seq = None
    mtype = raw_type
    if raw_type & 0x8000:
        mtype = (raw_type & 0xFF) + 5000
        seq = (raw_type >> 8) & 0x1F
    return mtype, seq, data[4:size - 2]


@dataclass
class Att:
    ts: int
    sent: bool
    opcode: int
    handle: int
    value: bytes


@dataclass
class Reasm:
    pb: int
    buf: bytearray = field(default_factory=bytearray)


def read_records(path: str):
    with open(path, "rb") as f:
        blob = f.read()
    if blob[:8] != b"btsnoop\0":
        raise SystemExit("not a btsnoop file")
    version, datalink = struct.unpack_from(">II", blob, 8)
    if datalink != 1002:
        raise SystemExit(f"unsupported datalink {datalink} (expected 1002 HCI UART)")
    off = 16
    while off + 24 <= len(blob):
        orig_len, incl_len, flags, drops, ts = struct.unpack_from(">IIIIQ", blob, off)
        off += 24
        pkt = blob[off:off + incl_len]
        off += incl_len
        yield flags, ts, pkt


def parse(path: str):
    reasm: dict[int, Reasm] = {}
    out: list[Att] = []

    for flags, ts, pkt in read_records(path):
        if not pkt or pkt[0] != 0x02:  # HCI ACL
            continue
        if len(pkt) < 5:
            continue
        acl_handle_flags = struct.unpack_from("<H", pkt, 1)[0]
        acl_handle = acl_handle_flags & 0x0FFF
        pb = (acl_handle_flags >> 12) & 0x3
        dlen = struct.unpack_from("<H", pkt, 3)[0]
        data = pkt[5:5 + dlen]
        sent = (flags & 0x01) == 0  # bit0: 1 = received by host

        st = reasm.setdefault(acl_handle, Reasm(pb=pb))
        if pb in (0b00, 0b10):  # first fragment
            st.buf = bytearray(data)
            st.pb = pb
        elif pb == 0b01:  # continuation
            st.buf.extend(data)
        else:
            continue

        # ACL "BC" / boundaries: for LE, PB=0b10 first, 0b01 cont, 0b00? treat 0 as complete.
        if pb == 0b01:
            continue
        l2 = bytes(st.buf)
        if len(l2) < 4:
            continue
        l2len, cid = struct.unpack_from("<HH", l2, 0)
        if cid != 4:  # ATT
            continue
        att = l2[4:4 + l2len]
        if not att:
            continue
        op = att[0]
        if op in (0x12, 0x52, 0x1B, 0x1D) and len(att) >= 3:
            h = struct.unpack_from("<H", att, 1)[0]
            out.append(Att(ts, sent, op, h, att[3:]))
    return out


OPNAME = {
    0x12: "WriteReq", 0x52: "WriteCmd", 0x1B: "Notify", 0x1D: "Indicate",
    0x08: "ReadByTypeReq", 0x09: "ReadByTypeRsp",
    0x04: "FindInfoReq", 0x05: "FindInfoRsp",
    0x10: "ReadByGroupReq", 0x11: "ReadByGroupRsp",
    0x0A: "ReadReq", 0x0B: "ReadRsp",
}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("path")
    ap.add_argument("--dump", action="store_true")
    ap.add_argument("--gfdi", action="store_true")
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args()

    atts = parse(args.path)
    if args.json:
        print(json.dumps([a.__dict__ | {"value": a.value.hex()} for a in atts]))
        return

    if not args.dump and not args.gfdi:
        from collections import Counter
        c = Counter((a.sent, OPNAME.get(a.opcode, hex(a.opcode)), a.handle) for a in atts)
        print(f"total ATT value ops: {len(atts)}\n")
        for (sent, op, h), n in c.most_common(30):
            print(f"{'TX' if sent else 'RX'} {op:14s} handle=0x{h:04x}  n={n}")

    if args.dump:
        for a in atts:
            print(f"{'TX' if a.sent else 'RX'} {OPNAME.get(a.opcode, hex(a.opcode)):12s} h=0x{a.handle:04x} {a.value.hex()}")

    if args.gfdi:
        # Every ML packet is [ml_handle][data]. ml_handle==0 is control plane;
        # anything else is a COBS byte stream for that service.
        services = {1: "GFDI", 2: "NFC", 3: "HEALTH_SDK", 4: "REGISTRATION",
                    5: "CONNEXT", 6: "RT_HR", 22: "KEEP_ALIVE"}
        streams: dict[tuple[bool, int], bytearray] = {}
        print("\n--- Multi-Link reconstruction ---")
        for a in atts:
            if a.opcode not in (0x52, 0x12, 0x1B, 0x1D) or not a.value:
                continue
            direction = "TX" if a.sent else "RX"
            mlh = a.value[0]
            data = a.value[1:]

            if mlh == 0:
                if data:
                    t = data[0]
                    cid = int.from_bytes(data[1:9], "little") if len(data) >= 9 else 0
                    if t == 0 and len(data) >= 12:
                        svc = int.from_bytes(data[9:11], "little")
                        rel = data[11]
                        print(f"{direction} ML control: REGISTER svc={svc}({services.get(svc,'?')}) reliable={rel} client={cid}")
                    elif t == 1 and len(data) >= 13:
                        svc = int.from_bytes(data[9:11], "little")
                        print(f"{direction} ML control: REGISTER_RESP svc={svc}({services.get(svc,'?')}) status={data[11]} handle={data[12]}")
                    elif t == 5:
                        print(f"{direction} ML control: CLOSE_ALL client={cid}")
                    elif t == 6:
                        print(f"{direction} ML control: CLOSE_ALL_RESP client={cid}")
                    elif t == 2:
                        print(f"{direction} ML control: CLOSE_HANDLE")
                    elif t == 3:
                        print(f"{direction} ML control: CLOSE_HANDLE_RESP")
                continue

            buf = streams.setdefault((a.sent, mlh), bytearray())
            buf.extend(data)
            while True:
                start = buf.find(0x00)
                if start < 0:
                    break
                end = buf.find(0x00, start + 1)
                if end < 0:
                    break
                frame = bytes(buf[start:end + 1])
                del buf[:end + 1]
                decoded = cobs_decode(frame)
                parsed = parse_gfdi(decoded)
                if parsed:
                    mtype, seq, payload = parsed
                    print(f"{direction} h={mlh} svc={services.get(mlh,'?')} GFDI type={mtype} seq={seq} len={len(payload)} {payload.hex()}")


if __name__ == "__main__":
    main()
