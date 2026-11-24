#!/usr/bin/env python3
"""Catch MCP member names accidentally left in the new runtime HUD class."""
import re
import subprocess

output = subprocess.check_output(
    ["javap", "-p", "-c", "-classpath", "build/mc-classes-reobf", "EffectTagHud"],
    text=True,
)
members = re.findall(r"// (?:Method|Field) net/minecraft/[^\s.]+\.([^:\s]+):", output)
assert members, "No Minecraft runtime references found"
unmapped = [name for name in members if not name.startswith(("func_", "field_"))
            and name != '"<init>"']
assert not unmapped, f"Unmapped Minecraft API in EffectTagHud: {unmapped}"
print(f"Effect Tag runtime mapping checks passed: {len(members)} references")
