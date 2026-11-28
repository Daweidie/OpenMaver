#!/usr/bin/env python3
"""Check packaged title-screen inheritance, overrides, and APIs against real 1.8.9 jars."""
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
fields = {p[1]: p[2].rsplit("/", 1)[1] for p in mappings if p and p[0] == "FD:"}


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
                for method, desc in cls.methods}, {
                (fields.get(cls.name + "/" + field, field), descriptor(desc))
                for field, desc in cls.fields})

artifacts = [repo / "build/openmaver-mc.jar"] + sorted((repo / "build").glob("openmaver-b2.2.0-????-??-??.jar"))[-1:]
for artifact in artifacts:
    with ZipFile(artifact) as archive:
        gui = parser.ClassFile(archive.read("GuiMaverMainMenu.class"))
        assert not any(name.startswith("net/minecraft/") for name in archive.namelist()), "Packaged Minecraft stubs"
    assert gui.parent == "net/minecraft/client/gui/GuiMainMenu", "IAS cannot recognize Maver's title screen"
    required = {("func_73866_w_", "()V"), ("func_73863_a", "(IIF)V"),
                ("func_73864_a", "(III)V"), ("func_146284_a", "(Lnet/minecraft/client/gui/GuiButton;)V"),
                ("func_73869_a", "(CI)V"), ("func_73868_f", "()Z")}
    assert required <= gui.methods, "Title-screen lifecycle/input overrides were not reobfuscated"
    definitions = dict(runtime, GuiMaverMainMenu=(gui.parent, gui.methods, gui.fields))
    checked = 0
    for index, refs in ((1, gui.method_refs()), (2, gui.field_refs())):
        for owner, member, desc in refs:
            if owner not in definitions:
                continue
            current = owner
            while current in definitions:
                definition = definitions[current]
                if (member, desc) in definition[index]:
                    break
                current = definition[0]
            else:
                raise AssertionError(f"Unlinked menu API: {artifact.name}: {owner}.{member}{desc}")
            checked += 1
    print(f"mainmenu-runtime: {artifact.name}: {checked} API references linked; title-screen type and overrides verified")
