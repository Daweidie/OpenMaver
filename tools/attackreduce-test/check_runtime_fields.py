#!/usr/bin/env python3
"""Link packaged MovementInput field references against real 1.8.9 + Forge jars.

The MCP/stub state-machine tests never load SRG bytecode. Read Forge's own
mapping and vanilla field descriptors so incorrect reobf names/types fail here.
Uses only Python's standard library; PRISM_ROOT or explicit jar paths work.
"""

import argparse
import lzma
import os
from pathlib import Path
import re
import struct
import sys
from zipfile import ZipFile


class ClassFile:
    def __init__(self, data):
        if data[:4] != b"\xca\xfe\xba\xbe":
            raise ValueError("Invalid class file")
        self.data, self.pos = data, 8
        self.pool = [None] * self.u2()
        index = 1
        while index < len(self.pool):
            tag = self.take(1)[0]
            if tag == 1:
                value = self.take(self.u2()).decode("utf-8", errors="replace")
            elif tag in (7, 8, 16, 19, 20):
                value = self.u2()
            elif tag in (9, 10, 11, 12, 17, 18):
                value = (self.u2(), self.u2())
            elif tag in (3, 4, 5, 6, 15):
                value = self.take({3: 4, 4: 4, 5: 8, 6: 8, 15: 3}[tag])
            else:
                raise ValueError("Unknown constant pool tag: " + str(tag))
            self.pool[index] = (tag, value)
            index += 2 if tag in (5, 6) else 1
        self.u2()  # access flags
        self.name = self.class_name(self.u2())
        parent = self.u2()
        self.parent = self.class_name(parent) if parent else None
        self.take(2 * self.u2())  # interfaces
        self.fields = set()
        for _ in range(self.u2()):
            self.u2()  # access flags
            self.fields.add((self.utf(self.u2()), self.utf(self.u2())))
            for _ in range(self.u2()):
                self.u2()  # attribute name
                self.take(struct.unpack(">I", self.take(4))[0])

    def take(self, size):
        start = self.pos
        self.pos += size
        return self.data[start:self.pos]

    def u2(self):
        return struct.unpack(">H", self.take(2))[0]

    def utf(self, index):
        return self.pool[index][1]

    def class_name(self, index):
        return self.utf(self.pool[index][1])

    def field_refs(self):
        for entry in self.pool:
            if entry and entry[0] == 9:
                owner, nat = entry[1]
                name, descriptor = self.pool[nat][1]
                yield self.class_name(owner), self.utf(name), self.utf(descriptor)


def runtime_inputs(minecraft, forge):
    with ZipFile(forge) as jar:
        lines = lzma.decompress(jar.read("deobfuscation_data-1.8.9.lzma")).decode().splitlines()
    classes, fields = {}, {}
    for line in lines:
        parts = line.split()
        if not parts:
            continue
        if parts[0] == "CL:":
            classes[parts[1]] = parts[2]
        elif parts[0] == "FD:":
            fields[parts[1]] = parts[2].rsplit("/", 1)[1]
    inputs = {}
    with ZipFile(minecraft) as jar:
        for obf, name in classes.items():
            if name not in ("net/minecraft/util/MovementInput", "net/minecraft/util/MovementInputFromOptions"):
                continue
            definition = ClassFile(jar.read(obf + ".class"))
            parent = classes.get(definition.parent, definition.parent)
            members = {
                (fields.get(obf + "/" + field, field),
                 re.sub(r"L([^;]+);", lambda m: "L" + classes.get(m[1], m[1]) + ";", descriptor))
                for field, descriptor in definition.fields
            }
            inputs[name] = (parent, members)
    if len(inputs) != 2:
        raise ValueError("Expected both 1.8.9 MovementInput classes in Forge mapping")
    return inputs


def check_artifact(path, runtime):
    with ZipFile(path) as jar:
        classes = [ClassFile(jar.read(name)) for name in jar.namelist() if name.endswith(".class")]
    definitions = {c.name: (c.parent, c.fields) for c in classes}
    if definitions.keys() & runtime.keys():
        raise ValueError(str(path) + " packages Minecraft API stubs")
    definitions.update(runtime)
    owners = set(runtime)
    while True:
        children = {name for name, (parent, _) in definitions.items() if parent in owners}
        if children <= owners:
            break
        owners.update(children)
    checked, errors = 0, []
    for cls in classes:
        for owner, field, descriptor in cls.field_refs():
            if owner not in owners:
                continue
            checked += 1
            current = owner
            while current in definitions:
                parent, members = definitions[current]
                if (field, descriptor) in members:
                    break
                current = parent
            else:
                errors.append(f"{cls.name}: {owner}.{field}:{descriptor}")
    if errors:
        for error in errors:
            print(f"[runtime-fields] FAIL {path}!{error}", file=sys.stderr)
        return False
    if not checked:
        raise ValueError(str(path) + " has no MovementInput field references")
    print(f"[runtime-fields] PASS {path}: {checked} field references match Minecraft 1.8.9 + Forge")
    return True


def main():
    libraries = Path(os.environ.get("PRISM_ROOT", str(Path.home() / ".local/share/PrismLauncher"))) / "libraries"
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--minecraft-jar", type=Path, default=libraries / "com/mojang/minecraft/1.8.9/minecraft-1.8.9-client.jar")
    parser.add_argument("--forge-jar", type=Path, default=libraries / "net/minecraftforge/forge/1.8.9-11.15.1.2318-1.8.9/forge-1.8.9-11.15.1.2318-1.8.9-universal.jar")
    parser.add_argument("artifacts", nargs="*", type=Path, default=[Path("build/openmaver-mc.jar"), Path("build/openmaver-mod.jar")])
    args = parser.parse_args()
    runtime = runtime_inputs(args.minecraft_jar, args.forge_jar)
    results = [check_artifact(path, runtime) for path in args.artifacts]
    return 0 if all(results) else 1


if __name__ == "__main__":
    sys.exit(main())
