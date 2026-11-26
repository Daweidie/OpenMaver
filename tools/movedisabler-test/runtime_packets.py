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
    package = "net/minecraft/network/play/client/"
    # Vanilla wire fields: C16.status, C0D.windowId and C0E.windowId are 'a'.
    wanted = {"C16PacketClientStatus": "status",
              "C0DPacketCloseWindow": "windowId",
              "C0EPacketClickWindow": "windowId"}
    with ZipFile(args.forge_jar) as jar:
        lines = lzma.decompress(jar.read("deobfuscation_data-1.8.9.lzma")).decode().splitlines()
    classes, fields = {}, {}
    for line in lines:
        parts = line.split()
        if parts and parts[0] == "CL:":
            classes[parts[2]] = parts[1]
        elif parts and parts[0] == "FD:":
            fields[parts[1]] = parts[2]
    for name, mcp in wanted.items():
        owner = package + name
        mapped = fields[classes[owner] + "/a"]
        if not mapped.startswith(owner + "/"):
            raise ValueError("Unexpected field owner: " + mapped)
        srg = mapped.rsplit("/", 1)[1]
        source = Path("tools/attackreduce-test") / (owner + ".java")
        body, count = re.subn(r"\b" + mcp + r"\b", srg, source.read_text())
        if count < 2:
            raise ValueError("Missing fixture field: " + str(source))
        target = args.out / (owner + ".java")
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(body)
        print("[runtime-packets] " + name + "." + mcp + " -> " + srg)


if __name__ == "__main__":
    main()
