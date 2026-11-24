#!/usr/bin/env python3
"""Check all packaged Minecraft/Forge field references against real 1.8.9 jars.

Unlike compilation against mcstubs, this checks the field descriptor as well as
its SRG name. A field declared WorldClient cannot be linked as World.
"""
import argparse
import importlib.util
import lzma
import os
from pathlib import Path
import re
import sys
from zipfile import ZipFile


def main():
    repo = Path(__file__).resolve().parents[1]
    spec = importlib.util.spec_from_file_location(
        "field_helpers", repo / "tools/attackreduce-test/check_runtime_fields.py")
    helpers = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(helpers)
    libraries = Path(os.environ.get("PRISM_ROOT", str(Path.home() / ".local/share/PrismLauncher"))) / "libraries"
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--minecraft-jar", type=Path, default=libraries / "com/mojang/minecraft/1.8.9/minecraft-1.8.9-client.jar")
    parser.add_argument("--forge-jar", type=Path, default=libraries / "net/minecraftforge/forge/1.8.9-11.15.1.2318-1.8.9/forge-1.8.9-11.15.1.2318-1.8.9-universal.jar")
    parser.add_argument("artifacts", nargs="*", type=Path)
    args = parser.parse_args()
    artifacts = args.artifacts or [repo / "build/openmaver-mc.jar"] + sorted(
        (repo / "build").glob("openmaver-b2.2.0-????-??-??.jar"))[-1:]
    runtime = {}
    with ZipFile(args.forge_jar) as forge, ZipFile(args.minecraft_jar) as minecraft:
        mapping = lzma.decompress(forge.read("deobfuscation_data-1.8.9.lzma")).decode().splitlines()
        names = {p[1]: p[2] for line in mapping if (p := line.split()) and p[0] == "CL:"}
        fields = {p[1]: p[2].rsplit("/", 1)[1] for line in mapping if (p := line.split()) and p[0] == "FD:"}

        def descriptor(value):
            return re.sub(r"L([^;]+);", lambda m: "L" + names.get(m[1], m[1]) + ";", value)

        for jar in (minecraft, forge):
            for path in jar.namelist():
                if not path.endswith(".class"):
                    continue
                name = names.get(path[:-6], path[:-6])
                if not name.startswith(("net/minecraft/", "net/minecraftforge/")):
                    continue
                cls = helpers.ClassFile(jar.read(path))
                members = {(fields.get(cls.name + "/" + field, field), descriptor(desc))
                           for field, desc in cls.fields}
                runtime[name] = (names.get(cls.parent, cls.parent), members)

    errors = []
    for artifact in artifacts:
        with ZipFile(artifact) as jar:
            classes = [helpers.ClassFile(jar.read(p)) for p in jar.namelist() if p.endswith(".class")]
        definitions = {c.name: (c.parent, c.fields) for c in classes}
        if definitions.keys() & runtime.keys():
            raise ValueError(str(artifact) + " packages Minecraft/Forge API stubs")
        definitions.update(runtime)
        checked = 0
        for cls in classes:
            for owner, field, desc in cls.field_refs():
                # Include subclass-owned references to inherited Minecraft fields.
                current = owner
                visited = set()
                related = owner.startswith(("net/minecraft/", "net/minecraftforge/"))
                found = False
                while current in definitions and current not in visited:
                    visited.add(current)
                    related |= current in runtime
                    parent, members = definitions[current]
                    if (field, desc) in members:
                        found = True
                        break
                    current = parent
                if not related:
                    continue
                checked += 1
                if not found:
                    errors.append(f"{artifact.name}!{cls.name}: {owner}.{field}:{desc}")
        print(f"[runtime-fields] {artifact.name}: checked {checked} Minecraft/Forge field references")
    for error in errors:
        print("[runtime-fields] FAIL " + error, file=sys.stderr)
    if errors:
        return 1
    print("[runtime-fields] PASS all names and descriptors match Minecraft 1.8.9 + Forge")
    return 0


if __name__ == "__main__":
    sys.exit(main())
