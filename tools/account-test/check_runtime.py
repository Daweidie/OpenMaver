#!/usr/bin/env python3
"""Link account/menu API calls and GUI overrides against real Minecraft 1.8.9 mappings."""
import importlib.util
import lzma
import os
from pathlib import Path
import re
from zipfile import ZipFile

repo = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("classfile", repo / "tools/attackreduce-test/check_runtime_fields.py")
parser = importlib.util.module_from_spec(spec)
spec.loader.exec_module(parser)
libraries = Path(os.environ.get("PRISM_ROOT", str(Path.home() / ".local/share/PrismLauncher"))) / "libraries"
minecraft = libraries / "com/mojang/minecraft/1.8.9/minecraft-1.8.9-client.jar"
forge = libraries / "net/minecraftforge/forge/1.8.9-11.15.1.2318-1.8.9/forge-1.8.9-11.15.1.2318-1.8.9-universal.jar"
with ZipFile(forge) as archive:
    mappings = [line.split() for line in lzma.decompress(archive.read("deobfuscation_data-1.8.9.lzma")).decode().splitlines()]
names = {p[1]: p[2] for p in mappings if p and p[0] == "CL:"}
methods = {(p[1], p[2]): p[3].rsplit("/", 1)[1] for p in mappings if p and p[0] == "MD:"}


def descriptor(value):
    return re.sub(r"L([^;]+);", lambda m: "L" + names.get(m[1], m[1]) + ";", value)


runtime = {}
for path in (minecraft, forge):
    with ZipFile(path) as archive:
        for filename in archive.namelist():
            if not filename.endswith(".class"):
                continue
            name = names.get(filename[:-6], filename[:-6])
            if not name.startswith(("net/minecraft/", "net/minecraftforge/")):
                continue
            cls = parser.ClassFile(archive.read(filename))
            runtime[name] = (names.get(cls.parent, cls.parent), {
                (methods.get((cls.name + "/" + method, desc), method), descriptor(desc))
                for method, desc in cls.methods})

artifacts = [repo / "build/openmaver-mc.jar"] + sorted((repo / "build").glob("openmaver-b2.2.0-????-??-??.jar"))[-1:]
for artifact in artifacts:
    with ZipFile(artifact) as archive:
        selected = [parser.ClassFile(archive.read(name)) for name in archive.namelist()
                    if name.endswith(".class") and name[:-6].split("$", 1)[0]
                    in {"GuiMaverAccounts", "GuiMaverMainMenu", "MaverAccounts", "MicrosoftAuth", "AuthJson"}]
        assert "openmaver/microsoft-auth.properties" in archive.namelist(), "Missing public application configuration"
    definitions = {cls.name: (cls.parent, cls.methods) for cls in selected}
    definitions.update(runtime)
    gui = next(cls for cls in selected if cls.name == "GuiMaverAccounts")
    required = {("func_73866_w_", "()V"), ("func_73863_a", "(IIF)V"),
                ("func_73864_a", "(III)V"), ("func_73869_a", "(CI)V"),
                ("func_146281_b", "()V"), ("func_73868_f", "()Z")}
    assert required <= gui.methods, "Account GUI lifecycle/input overrides were not reobfuscated"
    checked = 0
    for cls in selected:
        assert int.from_bytes(cls.data[6:8], "big") == 52, "Authentication must support Java 8"
        for owner, method, desc in cls.method_refs():
            current, seen = owner, set()
            while current in definitions and current not in seen:
                seen.add(current)
                current = definitions[current][0]
            if not any(name in runtime for name in seen):
                continue
            current = owner
            while current in definitions:
                parent, members = definitions[current]
                if (method, desc) in members:
                    break
                current = parent
            else:
                raise AssertionError(f"Unlinked account API: {artifact.name}!{cls.name}: {owner}.{method}{desc}")
            checked += 1
    print(f"account-runtime: {artifact.name}: {checked} API calls linked; GUI overrides and Java 8 verified")
