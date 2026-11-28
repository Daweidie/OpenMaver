#!/usr/bin/env python3
"""Link the new rendering calls against the installed 1.8.9 and LWJGL jars."""
import importlib.util
import lzma
import os
from pathlib import Path
import subprocess
from zipfile import ZipFile

repo = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("class_reader", repo / "tools/attackreduce-test/check_runtime_fields.py")
reader = importlib.util.module_from_spec(spec)
spec.loader.exec_module(reader)
libraries = Path(os.environ.get("PRISM_ROOT", str(Path.home() / ".local/share/PrismLauncher"))) / "libraries"
forge_path = libraries / "net/minecraftforge/forge/1.8.9-11.15.1.2318-1.8.9/forge-1.8.9-11.15.1.2318-1.8.9-universal.jar"
client_path = libraries / "com/mojang/minecraft/1.8.9/minecraft-1.8.9-client.jar"
with ZipFile(forge_path) as forge:
    mappings = [line.split() for line in lzma.decompress(forge.read("deobfuscation_data-1.8.9.lzma")).decode().splitlines()]
classes = {p[1]: p[2] for p in mappings if p[0] == "CL:"}
fields = {p[2]: p[1] for p in mappings if p[0] == "FD:"}
methods = {(p[1], p[2]): (p[3], p[4]) for p in mappings if p[0] == "MD:"}
reverse_classes = {v: k for k, v in classes.items()}
gsm = "net/minecraft/client/renderer/GlStateManager"
with ZipFile(client_path) as client:
    definition = reader.ClassFile(client.read(reverse_classes[gsm] + ".class"))
    actual = {methods.get((definition.name + "/" + name, desc), (name, desc))
              for name, desc in definition.methods}
cls = reader.ClassFile((repo / "build/mc-classes-reobf/PlayerEspOutline.class").read_bytes())
checked = 0
for owner, name, desc in cls.method_refs():
    if owner == gsm:
        assert (owner + "/" + name, desc) in actual, f"Unresolved runtime call: {owner}.{name}{desc}"
        checked += 1
assert checked >= 7

# These field aliases are used by the interpolation/camera reflection path.
feature_source = (repo / "mc/OpenMaverFeatures.java").read_text()
for owner, mcp, srg, obf in [
    ("RenderManager", "viewerPosX", "field_78725_b", "o"),
    ("RenderManager", "viewerPosY", "field_78726_c", "p"),
    ("RenderManager", "viewerPosZ", "field_78723_d", "q"),
    ("Entity", "lastTickPosX", "field_70142_S", "P"),
    ("Entity", "lastTickPosY", "field_70137_T", "Q"),
    ("Entity", "lastTickPosZ", "field_70136_U", "R"),
    ("Entity", "prevRotationYaw", "field_70126_B", "A"),
    ("Entity", "ticksExisted", "field_70173_aa", "W"),
]:
    full_owner = "net/minecraft/" + ("entity/Entity" if owner == "Entity" else "client/renderer/entity/RenderManager")
    assert fields[full_owner + "/" + srg] == reverse_classes[full_owner] + "/" + obf
    assert f'if ("{srg}".equals(srg)) return new String[] {{mcp, srg, "{obf}"}};' in feature_source
    checked += 1
for owner, mcp, srg, desc in [
    ("net/minecraft/client/renderer/entity/RenderManager", "getEntityRenderObject", "func_78713_a", "(Lnet/minecraft/entity/Entity;)Lnet/minecraft/client/renderer/entity/Render;"),
    ("net/minecraft/client/renderer/entity/Render", "doRender", "func_76986_a", "(Lnet/minecraft/entity/Entity;DDDFF)V"),
]:
    assert (owner + "/" + srg, desc) in methods.values()
    assert f'"{mcp}", "{srg}", "a"' in feature_source
    checked += 1

# The local compile stubs expose fewer overloads than real LWJGL 2. Compile the
# production renderer with the real jar first to catch ambiguous null pointers.
lwjgl = next((libraries / "org/lwjgl/lwjgl/lwjgl").glob("2.9.4*/lwjgl-*.jar"))
out = repo / "build/esp-outline-lwjgl-check"
out.mkdir(exist_ok=True)
classpath = os.pathsep.join(map(str, [lwjgl, repo / "build/mc-classes", repo / "build/java-classes", repo / "build/mcstubs-classes"]))
subprocess.run(["javac", "--release", "8", "-Xlint:-options", "-cp", classpath, "-d", str(out),
                str(repo / "mc/PlayerEspOutline.java")], check=True)
print(f"Player ESP runtime mapping checks passed: {checked}; real LWJGL 2 compile passed")
