#!/usr/bin/env python3
"""Generate SRG-only packet fixtures from the installed Forge 1.8.9 mapping.

Use Forge's actual field names, not names copied from MoveDisablerFeature. The
same Netty tests then run with no MCP field aliases on these packet classes.
"""

import argparse
import lzma
import os
from pathlib import Path
import re
from zipfile import ZipFile


def main():
    libraries = Path(os.environ.get(
        "PRISM_ROOT", str(Path.home() / ".local/share/PrismLauncher"))) / "libraries"
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--forge-jar", type=Path, default=libraries / (
        "net/minecraftforge/forge/1.8.9-11.15.1.2318-1.8.9/"
        "forge-1.8.9-11.15.1.2318-1.8.9-universal.jar"))
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    package = "net/minecraft/network/play/"
    # Obfuscated declaration fields independently confirmed with vanilla javap.
    wanted = {
        "client/C16PacketClientStatus": {"a": "status"},
        "client/C0DPacketCloseWindow": {"a": "windowId"},
        "client/C0EPacketClickWindow": {"a": "windowId", "d": "actionNumber"},
        "server/S32PacketConfirmTransaction": {"a": "windowId", "b": "actionNumber", "c": "accepted"},
        "server/S30PacketWindowItems": {"a": "windowId"},
        "server/S2FPacketSetSlot": {"a": "windowId", "b": "slot"},
        "client/C03PacketPlayer": {"g": "moving", "h": "rotating", "d": "yaw", "e": "pitch"},
    }
    with ZipFile(args.forge_jar) as jar:
        lines = lzma.decompress(jar.read("deobfuscation_data-1.8.9.lzma")).decode().splitlines()
    classes, fields = {}, {}
    for line in lines:
        parts = line.split()
        if parts and parts[0] == "CL:":
            classes[parts[2]] = parts[1]
        elif parts and parts[0] == "FD:":
            fields[parts[1]] = parts[2]
    for name, aliases in wanted.items():
        owner = package + name
        source = Path("tools/attackreduce-test") / (owner + ".java")
        body = source.read_text()
        for obf, mcp in aliases.items():
            mapped = fields[classes[owner] + "/" + obf]
            if not mapped.startswith(owner + "/"):
                raise ValueError("Unexpected field owner: " + mapped)
            srg = mapped.rsplit("/", 1)[1]
            body, count = re.subn(r"\b" + mcp + r"\b", srg, body)
            if count < 2:
                raise ValueError("Missing fixture field: " + str(source))
            print("[runtime-packets] " + name + "." + mcp + " -> " + srg)
        target = args.out / (owner + ".java")
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(body)


if __name__ == "__main__":
    main()
