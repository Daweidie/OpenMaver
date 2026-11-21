#!/usr/bin/env python3
"""Exercise mod installation and launch ordering without starting Minecraft."""

import hashlib
import importlib.util
import io
import json
import os
import sys
import tempfile
import unittest
import zipfile
from contextlib import redirect_stdout
from pathlib import Path
from unittest import mock


REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO))
from tools import viaforge


class ViaForgeTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="openmaver via test ")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.game = self.root / "instance/minecraft"
        self.source = self.root / viaforge.JAR_NAME
        with zipfile.ZipFile(self.source, "w") as archive:
            archive.writestr("mcmod.info", json.dumps([{"modid": "viaforge"}]))
        self.target = self.game / "mods" / viaforge.JAR_NAME
        digest = hashlib.sha256(self.source.read_bytes()).hexdigest()
        self.enterContext(mock.patch.object(viaforge, "SHA256", digest))
        self.enterContext(mock.patch.dict(os.environ, {
            "OPENMAVER_VIAFORGE": "1",
            "OPENMAVER_VIAFORGE_JAR": str(self.source),
        }))
        self.enterContext(redirect_stdout(io.StringIO()))

    def test_install_and_reuse_without_download(self):
        self.assertEqual(self.target, viaforge.install(self.game))
        self.assertEqual(self.source.read_bytes(), self.target.read_bytes())
        before = self.target.stat().st_mtime_ns
        self.source.unlink()
        self.assertEqual(self.target, viaforge.install(self.game))
        self.assertEqual(before, self.target.stat().st_mtime_ns)

    def test_dry_run_creates_no_instance(self):
        self.assertEqual(self.target, viaforge.install(self.game, dry_run=True))
        self.assertFalse(self.game.exists())

    def test_missing_source_stops_before_instance_changes(self):
        self.source.unlink()
        with self.assertRaisesRegex(viaforge.InstallError, "missing ViaForge"):
            viaforge.install(self.game)
        self.assertFalse(self.game.exists())

    def test_corrupt_source_stops_even_in_dry_run(self):
        self.source.write_bytes(b"truncated download")
        with self.assertRaisesRegex(viaforge.InstallError, "SHA-256 mismatch"):
            viaforge.install(self.game, dry_run=True)
        self.assertFalse(self.game.exists())

    def test_renamed_version_directory_jar_is_reused(self):
        installed = self.game / "mods/1.8.9/CustomName.jar"
        installed.parent.mkdir(parents=True)
        installed.write_bytes(self.source.read_bytes())
        self.source.unlink()
        self.assertEqual(installed, viaforge.install(self.game))
        self.assertFalse(self.target.exists())

    def test_duplicates_stop_without_renaming_or_overwriting(self):
        viaforge.install(self.game)
        duplicate = self.game / "mods/1.8.9/CustomName.jar"
        duplicate.parent.mkdir()
        duplicate.write_bytes(self.source.read_bytes())
        with self.assertRaisesRegex(viaforge.InstallError, "multiple ViaForge"):
            viaforge.install(self.game)
        self.assertTrue(duplicate.is_file())
        self.assertEqual(self.source.read_bytes(), self.target.read_bytes())

    def test_conflicting_installed_version_is_preserved(self):
        viaforge.install(self.game)
        self.target.write_bytes(b"another ViaForge version")
        with self.assertRaisesRegex(viaforge.InstallError, "SHA-256 mismatch"):
            viaforge.install(self.game)
        self.assertEqual(b"another ViaForge version", self.target.read_bytes())

    def test_failed_copy_leaves_no_partial_mod(self):
        def truncated_copy(source, target):
            Path(target).write_bytes(b"partial copy")

        with mock.patch.object(viaforge.shutil, "copy2", truncated_copy):
            with self.assertRaisesRegex(viaforge.InstallError, "SHA-256 mismatch"):
                viaforge.install(self.game)
        self.assertEqual([], list(self.target.parent.iterdir()))

    def test_opt_out_preserves_existing_mod(self):
        viaforge.install(self.game)
        self.source.unlink()
        with mock.patch.dict(os.environ, {"OPENMAVER_VIAFORGE": "0"}):
            self.assertIsNone(viaforge.install(self.game))
        self.assertTrue(self.target.is_file())

    def launcher(self):
        spec = importlib.util.spec_from_file_location(
            "openmaver_launcher", REPO / "launch-openmaver.py")
        launcher = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(launcher)
        launcher.PRISM_ROOT = self.root / "prism"
        launcher.INSTANCE = self.game.parent
        launcher.GAME_DIR = self.game
        launcher.LIBRARIES = self.root / "libraries"
        launcher.REPO = self.root / "repo"
        self.game.mkdir(parents=True)
        metadata = launcher.PRISM_ROOT / "meta"
        (metadata / "net.minecraft").mkdir(parents=True)
        (metadata / "net.minecraftforge").mkdir()
        (metadata / "net.minecraft/1.8.9.json").write_text(json.dumps({
            "libraries": [], "assetIndex": {"id": "1.8"},
        }))
        (metadata / "net.minecraftforge/11.15.1.2318.json").write_text(json.dumps({
            "libraries": [], "mainClass": "net.minecraft.launchwrapper.Launch",
            "+tweakers": ["net.minecraftforge.fml.common.launcher.FMLTweaker"],
        }))
        (launcher.INSTANCE / "instance.cfg").write_text(
            "[General]\nJavaPath={}\n".format(sys.executable))
        native_jar = None
        for name in (
            "com/mojang/minecraft/1.8.9/minecraft-1.8.9-client.jar",
            "org/lwjgl/lwjgl/lwjgl/2.9.4-nightly-20150209/lwjgl-2.9.4-nightly-20150209.jar",
            "org/lwjgl/lwjgl/lwjgl_util/2.9.4-nightly-20150209/lwjgl_util-2.9.4-nightly-20150209.jar",
            "org/lwjgl/lwjgl/lwjgl-platform/2.9.4-nightly-20150209/lwjgl-platform-2.9.4-nightly-20150209-natives-linux.jar",
        ):
            path = launcher.LIBRARIES / name
            path.parent.mkdir(parents=True, exist_ok=True)
            with zipfile.ZipFile(path, "w"):
                pass
            native_jar = path
        with zipfile.ZipFile(native_jar, "w") as archive:
            archive.writestr("libtest.so", b"test native")
        build = launcher.REPO / "build"
        build.mkdir(parents=True)
        (build / "openmaver-mod.jar").write_bytes(b"test OpenMaver mod")
        return launcher

    def test_mod_mode_installs_viaforge_before_java(self):
        launcher = self.launcher()

        def exec_java(java, args):
            self.assertTrue(self.target.is_file())
            self.assertTrue(launcher.mod_jar_path().is_file())
            raise SystemExit(0)

        with mock.patch.object(sys, "argv", ["launch-openmaver.py", "--mod"]), \
                mock.patch.object(launcher.os, "execv", side_effect=exec_java), \
                mock.patch.object(launcher.os, "chdir"):
            with self.assertRaises(SystemExit) as result:
                launcher.main()
        self.assertEqual(0, result.exception.code)

    def test_injection_keeps_viaforge_and_disables_only_openmaver(self):
        launcher = self.launcher()
        launcher.mod_jar_path().parent.mkdir()
        launcher.mod_jar_path().write_bytes(b"old OpenMaver mod")

        def launch_injected(args):
            self.assertTrue(self.target.is_file())
            self.assertFalse(launcher.mod_jar_path().exists())
            self.assertTrue(launcher.disabled_mod_jar_path().is_file())
            return 0

        with mock.patch.object(sys, "argv", ["launch-openmaver.py", "--inject"]), \
                mock.patch.object(launcher, "launch_injected", side_effect=launch_injected):
            with self.assertRaises(SystemExit) as result:
                launcher.main()
        self.assertEqual(0, result.exception.code)

    def test_both_dry_run_modes_leave_game_files_untouched(self):
        launcher = self.launcher()
        launcher.mod_jar_path().parent.mkdir()
        launcher.mod_jar_path().write_bytes(b"existing OpenMaver mod")
        before = {str(p.relative_to(self.game)): p.read_bytes()
                  for p in self.game.rglob("*") if p.is_file()}
        for mode in ("--mod", "--inject"):
            with mock.patch.object(sys, "argv", ["launch-openmaver.py", mode, "--dry-run"]):
                launcher.main()
        after = {str(p.relative_to(self.game)): p.read_bytes()
                 for p in self.game.rglob("*") if p.is_file()}
        self.assertEqual(before, after)
        self.assertFalse(self.target.exists())
        self.assertFalse((launcher.INSTANCE / ".openmaver").exists())


if __name__ == "__main__":
    unittest.main()
