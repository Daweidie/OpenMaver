#!/usr/bin/env python3
"""Link shipped Velocity code and reflected SRG symbols to real Minecraft 1.8.9.

Checks Java 8 class versions and prevents test/API/analysis classes in either JAR.
No client, game singleton or network connection is started.
"""
import lzma
import os
from pathlib import Path
import re
import struct
import sys
from zipfile import ZipFile

root = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(root / "tools/attackreduce-test"))
from check_runtime_fields import ClassFile

libraries = Path(os.environ.get("PRISM_ROOT", str(Path.home() / ".local/share/PrismLauncher"))) / "libraries"
client = libraries / "com/mojang/minecraft/1.8.9/minecraft-1.8.9-client.jar"
forge = libraries / "net/minecraftforge/forge/1.8.9-11.15.1.2318-1.8.9/forge-1.8.9-11.15.1.2318-1.8.9-universal.jar"
with ZipFile(forge) as jar:
    mapping = lzma.decompress(jar.read("deobfuscation_data-1.8.9.lzma")).decode().splitlines()
names, fields, methods = {}, {}, {}
for line in mapping:
    p = line.split()
    if p[0] == "CL:":
        names[p[1]] = p[2]
    elif p[0] == "FD:":
        fields[p[1]] = p[2].rsplit("/", 1)[1]
    elif p[0] == "MD:":
        methods[p[1] + p[2]] = p[3].rsplit("/", 1)[1]

def descriptor(value):
    return re.sub(r"L([^;]+);", lambda m: "L" + names.get(m[1], m[1]) + ";", value)

runtime = {}
with ZipFile(client) as jar:
    for entry in jar.namelist():
        if not entry.endswith(".class"):
            continue
        c = ClassFile(jar.read(entry))
        runtime[names.get(c.name, c.name)] = (
            [names.get(n, n) for n in [c.parent] + c.interfaces if n],
            {(fields.get(c.name + "/" + n, n), descriptor(d)) for n, d in c.fields},
            {(methods.get(c.name + "/" + n + d, n), descriptor(d)) for n, d in c.methods})

def resolves(owner, name, desc, kind, seen=None):
    seen = set() if seen is None else seen
    if owner not in runtime or owner in seen:
        return False
    seen.add(owner)
    parents, fs, ms = runtime[owner]
    if (name, desc) in (fs if kind == 1 else ms):
        return True
    return name != "<init>" and any(resolves(p, name, desc, kind, seen) for p in parents)

symbols = set()
for source in (root / "mc/antikb/augustus").glob("*.java"):
    symbols.update(re.findall(r'"((?:field_|func_)\w+)"', source.read_text()))
available = {n for _, fs, ms in runtime.values() for n, _ in fs | ms}
assert not symbols - available, "Unknown reflected SRG symbols: " + str(symbols - available)
# These three doubles are all valid fields; verify their axis, not only existence.
grim = (root / "mc/antikb/augustus/AugustusPacketModes.java").read_text()
for axis, srg in [("X", "field_175172_bI"), ("Y", "field_175166_bJ"), ("Z", "field_175167_bK")]:
    assert f'"lastReportedPos{axis}","{srg}"' in grim, "Grim previous position axis " + axis

artifacts = [root / "build/openmaver-mc.jar", sorted((root / "build").glob("openmaver-b2.2.0-????-??-??.jar"))[-1]]
for artifact in artifacts:
    count = checked = 0
    with ZipFile(artifact) as jar:
        for entry in jar.namelist():
            assert not entry.startswith(("net/minecraft/", "net/minecraftforge/", "org/objectweb/asm/", "ref/")), "API/analysis stub shipped: " + entry
            if not entry.endswith(".class"):
                continue
            data = jar.read(entry)
            assert struct.unpack_from(">H", data, 6)[0] <= 52, "Not Java 8: " + entry
            assert b"recovery$indy$" not in data and b"resolved_net_" not in data, "Analysis marker shipped: " + entry
            assert not entry.startswith(("VelocityTransformTest", "AugustusVelocityTest", "ZenAntiKBTest")), "Test class shipped: " + entry
            count += 1
            if not entry.startswith("Augustus"):
                continue
            c = ClassFile(data)
            for kind, refs in [(1, c.field_refs()), (2, c.method_refs())]:
                for owner, name, desc in refs:
                    if not owner.startswith("net/minecraft/"):
                        continue
                    assert resolves(owner, name, desc, kind), f"Unresolved runtime member: {entry}: {owner}.{name}{desc}"
                    checked += 1
        manifest = jar.read("META-INF/MANIFEST.MF").decode()
        if artifact.name != "openmaver-mc.jar":
            assert "FMLCorePlugin: VelocityLoadingPlugin" in manifest, "Missing coremod manifest"
    print(f"[velocity-runtime] PASS {artifact.name}: {len(symbols)} reflected symbols, {checked} direct references, {count} Java 8 classes, clean JAR")
