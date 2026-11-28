#!/usr/bin/env python3
"""Reproduce and fix the connection queue race using installed 1.8.9 bytecode, without starting the game."""
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
dependencies = [
    "org/ow2/asm/asm-all/5.0.3/asm-all-5.0.3.jar",
    "io/netty/netty-all/4.0.23.Final/netty-all-4.0.23.Final.jar",
    "com/google/guava/guava/17.0/guava-17.0.jar",
    "org/apache/logging/log4j/log4j-api/2.0-beta9-fixed/log4j-api-2.0-beta9-fixed.jar",
    "org/apache/logging/log4j/log4j-core/2.0-beta9-fixed/log4j-core-2.0-beta9-fixed.jar",
]
with tempfile.TemporaryDirectory(prefix="maver-network-test-") as temp:
    out = Path(temp)
    with ZipFile(forge) as jar:
        (out / "srg.txt").write_bytes(lzma.decompress(jar.read("deobfuscation_data-1.8.9.lzma")))
    cp = os.pathsep.join(map(str, [*(libraries / path for path in dependencies),
                                  root / "build/mc-classes-reobf", root / "build/mcstubs-classes"]))
    subprocess.run(["javac", "--release", "8", "-Xlint:-options", "-cp", cp, "-d", temp,
                    str(root / "tools/network-test/NetworkManagerTransformTest.java")], check=True)
    jdk = Path(shutil.which("javac")).resolve().parents[1]
    native = out / "network-jvmti-test.so"
    subprocess.run(["c++", "-shared", "-fPIC", "-std=c++17", "-I" + str(jdk / "include"),
                    "-I" + str(jdk / "include/linux"), str(root / "tools/network-test/NetworkJvmtiTest.cpp"),
                    "-o", str(native)], check=True)
    subprocess.run([os.environ.get("OPENMAVER_TEST_JAVA", "java"), "-Xverify:all", "-cp", temp + os.pathsep + cp, "NetworkManagerTransformTest",
                    str(client), str(out / "srg.txt"), str(libraries), str(native)], check=True)
