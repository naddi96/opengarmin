#!/usr/bin/env python3
"""Generate the Kotlin FIT profile from the public FIT SDK profile.

Requires the repo venv with fitdecode:

    tools/venv/bin/pip install fitdecode
    tools/venv/bin/python tools/gen_fit_profile.py

The generated file is protocol/src/main/kotlin/dev/opengarmin/protocol/FitProfile.kt.
"""
from __future__ import annotations

import pathlib

import fitdecode.profile as p

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "protocol/src/main/kotlin/dev/opengarmin/protocol/FitProfile.kt"


def escape(value: str | None) -> str:
    if value is None:
        return ""
    return value.replace("\\", "\\\\").replace('"', '\\"').replace("\n", " ").replace("\r", " ")


def number(value, default: str) -> str:
    if value is None:
        return default
    if isinstance(value, (int, float)):
        if float(value).is_integer():
            return f"{int(value)}.0"
        return repr(float(value))
    return default


def main() -> None:
    lines = [
        "package dev.opengarmin.protocol",
        "",
        "/**",
        " * FIT message/field names, units, scales and offsets.",
        " *",
        " * Generated from the public FIT SDK profile (via the fitdecode package).",
        " * Unknown messages/fields fall back to msg_<n> / field_<n>.",
        " * Regenerate with tools/gen_fit_profile.py.",
        " */",
        "object FitProfile {",
        "    data class FieldInfo(",
        "        val name: String,",
        "        val units: String? = null,",
        "        val scale: Double = 1.0,",
        "        val offset: Double = 0.0,",
        "    )",
        "    data class MessageInfo(val name: String, val fields: Map<Int, FieldInfo>)",
        "",
        "    val messages: Map<Int, MessageInfo> = mapOf(",
    ]
    for mesg_num, message in sorted(p.MESSAGE_TYPES.items()):
        lines.append(f'        {mesg_num} to MessageInfo("{escape(message.name)}", mapOf(')
        for field_num, field in sorted(message.fields.items()):
            name = escape(field.name)
            units = escape(field.units)
            scale = number(getattr(field, "scale", None), "1.0")
            offset = number(getattr(field, "offset", None), "0.0")
            units_literal = f'"{units}"' if units else "null"
            lines.append(
                f'            {field_num} to FieldInfo("{name}", {units_literal}, {scale}, {offset}),'
            )
        lines.append("        )),")
    lines += [
        "    )",
        "",
        "    fun message(number: Int): MessageInfo? = messages[number]",
        "    fun field(message: Int, number: Int): FieldInfo? = messages[message]?.fields?.get(number)",
        "}",
        "",
    ]
    OUT.write_text("\n".join(lines), encoding="utf-8")
    print(f"wrote {OUT} ({len(lines)} lines)")


if __name__ == "__main__":
    main()
