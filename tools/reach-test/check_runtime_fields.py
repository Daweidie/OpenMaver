#!/usr/bin/env python3
"""Verify Reach's reflection aliases against actual Minecraft 1.8.9 + Forge jars."""
import argparse
import importlib.util
import lzma
from pathlib import Path
from zipfile import ZipFile


def main():
    repo = Path(__file__).resolve().parents[2]
    libraries = Path.home() / ".local/share/PrismLauncher/libraries"
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--minecraft-jar", type=Path, default=libraries / "com/mojang/minecraft/1.8.9/minecraft-1.8.9-client.jar")
    parser.add_argument("--forge-jar", type=Path, default=libraries / "net/minecraftforge/forge/1.8.9-11.15.1.2318-1.8.9/forge-1.8.9-11.15.1.2318-1.8.9-universal.jar")
    args = parser.parse_args()
    spec = importlib.util.spec_from_file_location("runtime_fields", repo / "tools/attackreduce-test/check_runtime_fields.py")
    helpers = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(helpers)
    with ZipFile(args.forge_jar) as jar:
        lines = lzma.decompress(jar.read("deobfuscation_data-1.8.9.lzma")).decode().splitlines()
    owner = "net/minecraft/client/entity/EntityPlayerSP"
    obf = next(line.split()[1] for line in lines if line.startswith("CL: ") and line.split()[2] == owner)
    fields = {line.split()[2].rsplit("/", 1)[1]: line.split()[1].rsplit("/", 1)[1]
              for line in lines if line.startswith("FD: " + obf + "/")}
    expected = {
        "lastReportedPosX": ("field_175172_bI", "bK", "D"),
        "lastReportedPosY": ("field_175166_bJ", "bL", "D"),
        "lastReportedPosZ": ("field_175167_bK", "bM", "D"),
        "lastReportedYaw": ("field_175164_bL", "bN", "F"),
        "lastReportedPitch": ("field_175165_bM", "bO", "F"),
    }
    with ZipFile(args.minecraft_jar) as jar:
        definition = helpers.ClassFile(jar.read(obf + ".class"))
    for mcp, (srg, alias, descriptor) in expected.items():
        assert fields[srg] == alias, (mcp, srg, fields[srg])
        assert (alias, descriptor) in definition.fields, (mcp, alias, descriptor)
    paths = [repo / "build/openmaver-mc.jar"]
    paths.extend(sorted((repo / "build").glob("openmaver-b2.2.0-????-??-??.jar"))[-1:])
    for path in paths:
        with ZipFile(path) as jar:
            cls = helpers.ClassFile(jar.read("OpenMaverFeatures.class"))
        strings = {entry[1] for entry in cls.pool if entry and entry[0] == 1}
        for mcp, (srg, alias, _) in expected.items():
            assert {mcp, srg, alias} <= strings, (path.name, mcp)
        print("[reach-runtime-fields] " + path.name + ": 5 reported pose fields match Minecraft 1.8.9 + Forge")


if __name__ == "__main__":
    main()
