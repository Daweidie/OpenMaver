#!/usr/bin/env python3
"""Run against the installed 1.8.9 client and Forge ASM; never starts Minecraft."""
import lzma
import os
from pathlib import Path
import subprocess
import shutil
import tempfile
from zipfile import ZipFile

root = Path(__file__).resolve().parents[2]
libraries = Path(os.environ.get("PRISM_ROOT", str(Path.home() / ".local/share/PrismLauncher"))) / "libraries"
client = libraries / "com/mojang/minecraft/1.8.9/minecraft-1.8.9-client.jar"
forge = libraries / "net/minecraftforge/forge/1.8.9-11.15.1.2318-1.8.9/forge-1.8.9-11.15.1.2318-1.8.9-universal.jar"
asm = libraries / "org/ow2/asm/asm-all/5.0.3/asm-all-5.0.3.jar"
with tempfile.TemporaryDirectory(prefix="maver-velocity-transform-") as temp:
    out = Path(temp)
    with ZipFile(forge) as jar:
        (out / "srg.txt").write_bytes(lzma.decompress(jar.read("deobfuscation_data-1.8.9.lzma")))
    cp = os.pathsep.join(map(str, [asm, root / "build/mc-classes", root / "build/mcstubs-classes"]))
    subprocess.run(["javac", "-source", "8", "-target", "8", "-Xlint:-options", "-cp", cp, "-d", temp,
                    str(root / "tools/augustus-velocity/VelocityTransformTest.java")], check=True)
    jdk = Path(shutil.which("javac")).resolve().parents[1]
    native = out / "velocity-jvmti-test.so"
    subprocess.run(["c++", "-shared", "-fPIC", "-std=c++17", "-I" + str(jdk / "include"),
                    "-I" + str(jdk / "include/linux"), str(root / "tools/augustus-velocity/VelocityJvmtiTest.cpp"),
                    "-o", str(native)], check=True)
    subprocess.run(["java", "-Xverify:all", "-cp", temp + os.pathsep + cp, "VelocityTransformTest",
                    str(client), str(out / "srg.txt"), str(libraries), str(native)], check=True)
