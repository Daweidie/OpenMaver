#!/usr/bin/env python3
"""Launch the locally installed Forge 1.8.9 instance without a launcher UI."""

import configparser
import json
import os
import shutil
import subprocess
import sys
import time
import zipfile
from pathlib import Path


REPO = Path(__file__).resolve().parent
PRISM_ROOT = Path(os.environ.get(
    "PRISM_ROOT", Path.home() / ".local/share/PrismLauncher")).expanduser()
INSTANCE_ID = os.environ.get("OPENMAVER_INSTANCE", "1.8.9(1)")
INSTANCE = Path(os.environ.get(
    "OPENMAVER_INSTANCE_DIR", PRISM_ROOT / "instances" / INSTANCE_ID)).expanduser()
GAME_DIR = INSTANCE / "minecraft"
LIBRARIES = PRISM_ROOT / "libraries"
ASSETS = PRISM_ROOT / "assets"
FORGE = "11.15.1.2318"


def fail(message):
    raise SystemExit("[OpenMaver] " + message)


def read_json(path):
    try:
        with path.open(encoding="utf-8") as stream:
            return json.load(stream)
    except (OSError, ValueError) as exc:
        fail("cannot read {}: {}".format(path, exc))


def maven_path(coordinate):
    parts = coordinate.split(":")
    if len(parts) not in (3, 4):
        fail("unsupported Maven coordinate: " + coordinate)
    group, artifact, version = parts[:3]
    classifier = "-" + parts[3] if len(parts) == 4 else ""
    filename = "{}-{}{}.jar".format(artifact, version, classifier)
    return LIBRARIES / Path(*group.split(".")) / artifact / version / filename


def library_is_enabled(library):
    rules = library.get("rules")
    if not rules:
        return True
    enabled = False
    for rule in rules:
        os_rule = rule.get("os", {})
        if os_rule and os_rule.get("name") != "linux":
            continue
        enabled = rule.get("action") == "allow"
    return enabled


def account_arguments():
    username_override = os.environ.get("OPENMAVER_USERNAME")
    accounts_file = PRISM_ROOT / "accounts.json"
    accounts = read_json(accounts_file).get("accounts", []) if accounts_file.exists() else []
    active = next((item for item in accounts if item.get("active")), None)
    if active:
        profile = active.get("profile") or {}
        name = username_override or profile.get("name")
        profile_id = profile.get("id")
        session = active.get("ygg") or {}
        token = session.get("token")
        if name and profile_id and token:
            return name, profile_id, token, "mojang"
        fail("the active account has no cached game session; sign in with Prism once, or set OPENMAVER_USERNAME for offline mode")

    name = username_override or "OpenMaver"
    return name, "00000000-0000-0000-0000-000000000000", "0", "legacy"


def mod_jar_path():
    return GAME_DIR / "mods/OpenMaver.jar"


def disabled_mod_jar_path():
    return GAME_DIR / "mods/OpenMaver.jar.disabled"


def legacy_mod_jar_path():
    """Build artifact some users copy directly into mods/."""
    return GAME_DIR / "mods/openmaver-mod.jar"


def legacy_disabled_mod_jar_path():
    return GAME_DIR / "mods/openmaver-mod.jar.disabled"


def disable_duplicate(target, disabled):
    """Forge rejects two jars with the same modid; keep only the canonical file."""
    if target.exists():
        if disabled.exists():
            disabled.unlink()
        os.replace(target, disabled)
        return disabled
    return None


def install_mod():
    source = REPO / "build/openmaver-mod.jar"
    if not source.is_file():
        fail("missing build/openmaver-mod.jar; run ./launch-openmaver.sh")
    mods_dir = GAME_DIR / "mods"
    mods_dir.mkdir(parents=True, exist_ok=True)
    target = mod_jar_path()
    disabled = disabled_mod_jar_path()
    if disabled.exists():
        disabled.unlink()
    # Forge 1.8.9 aborts when both openmaver-mod.jar and OpenMaver.jar declare
    # the same modid, so move a directly-copied build artifact out of the way.
    disable_duplicate(legacy_mod_jar_path(), legacy_disabled_mod_jar_path())
    if not target.exists() or source.read_bytes() != target.read_bytes():
        shutil.copy2(source, target)
    return target


def disable_mod():
    """Keep mod mode and injection mode isolated inside the same instance."""
    disabled = []
    path = disable_duplicate(mod_jar_path(), disabled_mod_jar_path())
    if path is not None:
        disabled.append(path)
    path = disable_duplicate(legacy_mod_jar_path(), legacy_disabled_mod_jar_path())
    if path is not None:
        disabled.append(path)
    return disabled


def extract_natives(forge_meta):
    natives = INSTANCE / ".openmaver/natives"
    natives.mkdir(parents=True, exist_ok=True)
    native_jars = []
    for library in forge_meta["libraries"]:
        name = library.get("name", "")
        if name.startswith("org.lwjgl.lwjgl:lwjgl-platform:"):
            native_jars.append(maven_path(name + ":natives-linux"))
    if not native_jars:
        native_jars.append(LIBRARIES / "org/lwjgl/lwjgl/lwjgl-platform/2.9.4-nightly-20150209/lwjgl-platform-2.9.4-nightly-20150209-natives-linux.jar")
    for jar_path in native_jars:
        if not jar_path.is_file():
            fail("missing LWJGL native library: " + str(jar_path))
        with zipfile.ZipFile(jar_path) as archive:
            for member in archive.infolist():
                if member.is_dir() or "/" in member.filename or not member.filename.endswith((".so", ".dll", ".dylib")):
                    continue
                with archive.open(member) as src, (natives / member.filename).open("wb") as dst:
                    shutil.copyfileobj(src, dst)
    return natives


def wait_for_game_ready(process, old_mtime, timeout=90):
    """Wait until Forge has loaded far enough to accept the runtime agent."""
    log_path = GAME_DIR / "logs/latest.log"
    start = time.time()
    deadline = start + timeout
    while time.time() < deadline:
        if process.poll() is not None:
            return False
        try:
            fresh = log_path.stat().st_mtime > old_mtime
            text = log_path.read_text(encoding="utf-8", errors="ignore") if fresh else ""
        except OSError:
            text = ""
        if "Reloading ResourceManager" in text and time.time() - start >= 3.0:
            # Forge has registered its event bus and the LaunchClassLoader is ready.
            time.sleep(1.5)
            return True
        time.sleep(0.5)
    return False


def inject_game(pid):
    injector = REPO / "build/bin/openmaver_injector"
    agent_so = REPO / "build/lib/libopenmaver_core.so"
    core_jar = REPO / "build/openmaver-core.jar"
    mc_jar = REPO / "build/openmaver-mc.jar"
    for artifact in (injector, agent_so, core_jar, mc_jar):
        if not artifact.is_file():
            fail("missing injection artifact: {}; run ./launch-openmaver.sh".format(artifact))
    command = [
        str(injector),
        "--re-self",
        "--pid", str(pid),
        "--so", str(agent_so),
        "--options", "jar=" + str(core_jar),
        "--mcjar", str(mc_jar),
    ]
    print("[OpenMaver] injecting runtime UI into pid={}".format(pid))
    return subprocess.call(command, cwd=str(REPO)) == 0


def launch_injected(args):
    os.chdir(GAME_DIR)
    log_path = GAME_DIR / "logs/latest.log"
    try:
        old_mtime = log_path.stat().st_mtime
    except OSError:
        old_mtime = 0.0
    print("[OpenMaver] launching without mods/OpenMaver.jar (injection mode)")
    process = subprocess.Popen(args, cwd=str(GAME_DIR))
    print("[OpenMaver] game pid={}; waiting for Forge to finish loading...".format(
        process.pid))
    if not wait_for_game_ready(process, old_mtime):
        if process.poll() is None:
            fail("Minecraft is still starting but injection readiness timed out; "
                 "the game was left running, attach later with ./inject-prism.sh")
        return process.wait()
    if not inject_game(process.pid):
        fail("runtime injection failed; the game is still running, check "
             "/tmp/openmaver-agent-{}.log".format(process.pid))
    try:
        return process.wait()
    except KeyboardInterrupt:
        return 130


def main():
    argv = sys.argv[1:]
    dry_run = "--dry-run" in argv
    mode = "mod"
    for argument in argv:
        if argument == "--inject":
            mode = "inject"
        elif argument == "--mod":
            mode = "mod"
        elif argument in ("-h", "--help"):
            print("usage: launch-openmaver.py [--mod|--inject] [--dry-run]")
            print("  --mod      launch with mods/OpenMaver.jar (default)")
            print("  --inject   launch without the mod, then attach the runtime agent")
            return
        elif argument != "--dry-run":
            fail("unknown argument: " + argument)
    if not GAME_DIR.is_dir():
        fail("Minecraft instance not found: {} (set OPENMAVER_INSTANCE_DIR)".format(GAME_DIR))
    base_meta = read_json(PRISM_ROOT / "meta/net.minecraft/1.8.9.json")
    forge_meta = read_json(PRISM_ROOT / "meta/net.minecraftforge/11.15.1.2318.json")
    instance_cfg = configparser.ConfigParser()
    cfg_path = INSTANCE / "instance.cfg"
    instance_cfg.read(cfg_path, encoding="utf-8")
    java = Path(instance_cfg.get("General", "JavaPath", fallback="")).expanduser()
    if not java.is_file():
        java = Path(shutil.which("java") or "")
    if not java.is_file():
        fail("Java 8 was not found; set JavaPath in instance.cfg or put Java 8 on PATH")

    if not dry_run:
        if mode == "inject":
            disabled = disable_mod()
            for path in disabled:
                print("[OpenMaver] disabled mod jar for injection mode: {}".format(path))
        else:
            install_mod()
    classpath = []
    for library in base_meta["libraries"] + forge_meta["libraries"]:
        if not library_is_enabled(library):
            continue
        jar_path = maven_path(library["name"])
        if not jar_path.is_file():
            fail("missing library: {} (expected {})".format(library["name"], jar_path))
        if jar_path not in classpath:
            classpath.append(jar_path)
    client = LIBRARIES / "com/mojang/minecraft/1.8.9/minecraft-1.8.9-client.jar"
    if not client.is_file():
        fail("missing Minecraft client jar: " + str(client))
    legacy_jars = (
        LIBRARIES / "org/lwjgl/lwjgl/lwjgl/2.9.4-nightly-20150209/lwjgl-2.9.4-nightly-20150209.jar",
        LIBRARIES / "org/lwjgl/lwjgl/lwjgl_util/2.9.4-nightly-20150209/lwjgl_util-2.9.4-nightly-20150209.jar",
    )
    for jar_path in legacy_jars:
        if not jar_path.is_file():
            fail("missing LWJGL 2 library: " + str(jar_path))
        classpath.append(jar_path)
    classpath.append(client)

    native_jar = LIBRARIES / "org/lwjgl/lwjgl/lwjgl-platform/2.9.4-nightly-20150209/lwjgl-platform-2.9.4-nightly-20150209-natives-linux.jar"
    if not native_jar.is_file():
        fail("missing LWJGL native library: " + str(native_jar))
    natives = INSTANCE / ".openmaver/natives"
    if not dry_run:
        natives = extract_natives(forge_meta)
    username, uuid, token, user_type = account_arguments()
    args = [
        str(java), "-Xms{}M".format(instance_cfg.getint("General", "MinMemAlloc", fallback=512)),
        "-Xmx{}M".format(instance_cfg.getint("General", "MaxMemAlloc", fallback=4096)),
        "-Dopenmaver.features=" + str(GAME_DIR / "config/openmaver.properties"),
        "-Djava.library.path=" + str(natives),
        "-Dorg.lwjgl.librarypath=" + str(natives),
        "-Djava.net.preferIPv4Stack=true",
        "-cp", os.pathsep.join(map(str, classpath)),
        forge_meta["mainClass"],
        "--username", username,
        "--version", "Forge {}".format(FORGE),
        "--gameDir", str(GAME_DIR),
        "--assetsDir", str(ASSETS),
        "--assetIndex", base_meta["assetIndex"]["id"],
        "--uuid", uuid,
        "--accessToken", token,
        "--userType", user_type,
        "--userProperties", "{}",
    ]
    for tweaker in forge_meta.get("+tweakers", []):
        args.extend(["--tweakClass", tweaker])

    if dry_run:
        print("Mode:", mode)
        print("Java:", java)
        print("Game directory:", GAME_DIR)
        print("Classpath entries:", len(classpath))
        print("Main class:", forge_meta["mainClass"])
        print("Tweakers:", ", ".join(forge_meta.get("+tweakers", [])))
        if mode == "inject":
            print("OpenMaver mod:", disabled_mod_jar_path(), "(disabled for injection)")
            print("Runtime jars:", REPO / "build/openmaver-core.jar",
                  REPO / "build/openmaver-mc.jar")
        else:
            print("OpenMaver mod:", mod_jar_path(), "(installed at launch)")
        print("Account:", username, "(credentials omitted)")
        return
    if mode == "inject":
        raise SystemExit(launch_injected(args))
    os.chdir(GAME_DIR)
    os.execv(str(java), args)


if __name__ == "__main__":
    main()
