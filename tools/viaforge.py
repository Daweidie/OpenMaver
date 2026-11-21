#!/usr/bin/env python3
"""Install the pinned ViaForge release before Forge starts loading coremods."""

import hashlib
import json
import os
import shutil
import tempfile
import zipfile
from pathlib import Path


VERSION = "4.3.2"
JAR_NAME = "viaforge-mc189-{}.jar".format(VERSION)
SHA256 = "8cace53f370b13b9be25a9ff3107882dcbc0f9b374b383fa919e7324d0fab4cd"


class InstallError(Exception):
    """An actionable failure that should stop launch before Java starts."""


def _verify(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    if digest.hexdigest() != SHA256:
        raise InstallError("ViaForge SHA-256 mismatch: {} (expected {})".format(
            path, SHA256))


def _is_viaforge(path):
    if path.name.lower().startswith("viaforge"):
        return True
    try:
        with zipfile.ZipFile(path) as archive:
            info = json.loads(archive.read("mcmod.info").decode("utf-8"))
        if isinstance(info, dict):
            info = info.get("modList", [info])
        return isinstance(info, list) and any(
            isinstance(item, dict) and item.get("modid") == "viaforge"
            for item in info)
    except (OSError, KeyError, ValueError, zipfile.BadZipFile):
        return False


def _installed_jars(mods):
    result = []
    for directory in (mods, mods / "1.8.9"):
        if not directory.is_dir():
            continue
        for path in sorted(directory.iterdir()):
            if (path.is_file() and path.suffix.lower() in (".jar", ".zip")
                    and _is_viaforge(path)):
                result.append(path)
    return result


def install(game_dir, dry_run=False):
    """Validate or install ViaForge; dry runs leave the instance untouched.

    OPENMAVER_VIAFORGE_JAR overrides the source file, not the pinned version.
    OPENMAVER_VIAFORGE=0 skips management; existing mods still load normally.
    """
    enabled = os.environ.get("OPENMAVER_VIAFORGE", "1").strip().lower()
    if enabled in ("0", "false", "no", "off"):
        print("[OpenMaver] ViaForge auto-install skipped; existing mods are unchanged")
        return None
    if enabled not in ("1", "true", "yes", "on"):
        raise InstallError("OPENMAVER_VIAFORGE must be 1 or 0")

    source = Path(os.environ.get(
        "OPENMAVER_VIAFORGE_JAR", Path.home() / "Downloads" / JAR_NAME)).expanduser()
    mods = Path(game_dir) / "mods"
    target = mods / JAR_NAME
    try:
        existing = _installed_jars(mods)
        if len(existing) > 1:
            raise InstallError("multiple ViaForge mods found; keep one: {}".format(
                ", ".join(map(str, existing))))
        if existing:
            target = existing[0]
            _verify(target)
            print("[OpenMaver] ViaForge {} verified: {}".format(VERSION, target))
            return target
        if not source.is_file():
            raise InstallError(
                "missing ViaForge jar: {}; set OPENMAVER_VIAFORGE_JAR to {} "
                "or OPENMAVER_VIAFORGE=0 to skip auto-install".format(source, JAR_NAME))
        _verify(source)
        if dry_run:
            print("[OpenMaver] ViaForge {}: {} -> {} (install at launch)".format(
                VERSION, source, target))
            return target

        mods.mkdir(parents=True, exist_ok=True)
        # A partial copy must never be visible to Forge as a loadable mod jar.
        with tempfile.NamedTemporaryFile(
                dir=str(mods), prefix=".viaforge-", suffix=".tmp", delete=False) as stream:
            temporary = Path(stream.name)
        try:
            shutil.copy2(str(source), str(temporary))
            _verify(temporary)
            os.replace(str(temporary), str(target))
        finally:
            if temporary.exists():
                temporary.unlink()
        print("[OpenMaver] ViaForge {} installed: {}".format(VERSION, target))
        return target
    except OSError as exc:
        raise InstallError("ViaForge setup failed: {}".format(exc)) from exc
