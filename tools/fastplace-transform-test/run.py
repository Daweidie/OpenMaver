#!/usr/bin/env python3
"""Verify the cooldown hook against real 1.8.9 mappings and an executing JVM fixture."""
import lzma
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
from zipfile import ZipFile

root = Path(__file__).resolve().parents[2]
libraries = Path(os.environ.get("PRISM_ROOT", str(Path.home() / ".local/share/PrismLauncher"))) / "libraries"
client = libraries / "com/mojang/minecraft/1.8.9/minecraft-1.8.9-client.jar"
forge = libraries / "net/minecraftforge/forge/1.8.9-11.15.1.2318-1.8.9/forge-1.8.9-11.15.1.2318-1.8.9-universal.jar"
asm = libraries / "org/ow2/asm/asm-all/5.0.3/asm-all-5.0.3.jar"
with tempfile.TemporaryDirectory(prefix="maver-fastplace-transform-") as temp:
    out = Path(temp)
    with ZipFile(forge) as jar:
        (out / "srg.txt").write_bytes(lzma.decompress(jar.read("deobfuscation_data-1.8.9.lzma")))
    mc_classes = Path(os.environ.get("OPENMAVER_FASTPLACE_MC_CP", str(root / "build/mc-classes")))
    cp = os.pathsep.join(map(str, [asm, mc_classes, root / "build/java-classes",
                                  root / "build/mcstubs-classes"]))
    fixtures = sorted((root / "tools/fastplace-test").rglob("*.java"))
    subprocess.run(["javac", "--release", "8", "-Xlint:-options", "-cp", cp, "-d", temp,
                    str(root / "tools/fastplace-transform-test/FastPlaceTransformTest.java"),
                    *map(str, fixtures)], check=True)
    jdk = Path(shutil.which("javac")).resolve().parents[1]
    native = out / "fastplace-jvmti-test.so"
    subprocess.run(["c++", "-shared", "-fPIC", "-std=c++17", "-I" + str(jdk / "include"),
                    "-I" + str(jdk / "include/linux"), str(root / "tools/fastplace-transform-test/FastPlaceJvmtiTest.cpp"),
                    "-o", str(native)], check=True)
    subprocess.run(["java", "-Xverify:all", "-cp", temp + os.pathsep + cp, "FastPlaceTransformTest",
                    str(client), str(out / "srg.txt"), str(libraries), str(native)], check=True)
