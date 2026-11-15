#!/usr/bin/env python3
"""安装 Maver 自带的 2pimp v2 [16x] 材质包。

用法：
    python3 tools/resourcepack.py [--game-dir <minecraft 目录>]

行为：
1. 把仓库内 ``mc/resources/openmaver/resourcepack/2pimp-v2-16x.zip``
   复制到 ``<game-dir>/resourcepacks/``（文件名保留 § 颜色代码）。
2. 第一次安装时把它加入 ``options.txt`` 的 ``resourcePacks`` 列表，
   这样启动游戏就是启用状态；之后不再修改列表，尊重玩家的手动开关。
3. 写入 ``<game-dir>/.openmaver/resourcepack.installed`` 标记，避免重复启用。
"""

import json
import os
import shutil
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
SOURCE = REPO / "mc/resources/openmaver/resourcepack/2pimp-v2-16x.zip"
PACK_NAME = "2pimp v2 [16x].zip"
# 1.8.9 的 resourcePacks 里就是纯文件名；"file/" 前缀是更高版本才有的。
PACK_ID = PACK_NAME
# 原始文件名带 § 颜色代码。部分 1.8.9 的 JVM 以 ASCII 解码文件名（sun.jnu.encoding
# = ANSI_X3.4-1968）时会把 § 变成 ?/替换字符，导致永远匹配不上，所以落地文件名
# 统一用 ASCII，并在迁移时清掉旧条目。
ORIGINAL_PACK_NAME = "\u00a7c2\u00a75pimp \u00a7cv\u00a752 \u00a78[16x].zip"
MARKER = Path(".openmaver/resourcepack.installed")


def _strip_codes(name):
    """去掉颜色代码/被 ASCII 解码破坏的字符，用于识别旧文件名。"""
    return "".join(ch for ch in name if ch not in "\u00a7?\ufffd")


_ORIGINAL_KEY = _strip_codes(ORIGINAL_PACK_NAME)


def _is_legacy_pack(name):
    base = name[5:] if name.startswith("file/") else name
    return _strip_codes(base) == _ORIGINAL_KEY



def _parse_packs(raw):
    try:
        value = json.loads(raw)
    except ValueError:
        return None
    if not isinstance(value, list):
        return None
    return [item for item in value if isinstance(item, str)]


def _dump_packs(packs):
    # ensure_ascii=True：非 ASCII 字符写成 \uXXXX，不依赖 options.txt 的本地编码。
    return json.dumps(packs, ensure_ascii=True, separators=(",", ":"))


def enable_options(options, add_pack):
    """修正/写入 options.txt 的 resourcePacks；返回是否成功处理了该行。"""
    if not options.is_file():
        return False
    try:
        text = options.read_text(encoding="utf-8", errors="surrogateescape")
    except OSError:
        return False
    lines = text.splitlines()
    found = False
    changed = False
    for index, line in enumerate(lines):
        if line.startswith("resourcePacks:"):
            packs = _parse_packs(line.split(":", 1)[1])
            if packs is None:
                continue
            found = True
            migrated = False
            filtered = []
            for item in packs:
                if _is_legacy_pack(item):
                    migrated = True
                elif item not in filtered:
                    filtered.append(item)
            if migrated and PACK_ID not in filtered:
                filtered.append(PACK_ID)
            if add_pack and PACK_ID not in filtered:
                filtered.append(PACK_ID)
            if filtered != packs:
                changed = True
            lines[index] = "resourcePacks:" + _dump_packs(filtered)
        elif line.startswith("incompatibleResourcePacks:"):
            packs = _parse_packs(line.split(":", 1)[1])
            if packs is None:
                continue
            filtered = [item for item in packs
                        if item != PACK_ID and not _is_legacy_pack(item)]
            if len(filtered) != len(packs):
                lines[index] = "incompatibleResourcePacks:" + _dump_packs(filtered)
                changed = True
    if not found:
        return False
    if changed:
        temp = options.with_name(options.name + ".openmaver.tmp")
        try:
            temp.write_text("\n".join(lines) + "\n", encoding="utf-8",
                            errors="surrogateescape")
            os.replace(str(temp), str(options))
        except OSError as exc:
            print("[OpenMaver] 写入 options.txt 失败: {}".format(exc), file=sys.stderr)
            try:
                temp.unlink()
            except OSError:
                pass
            return False
        print("[OpenMaver] 已更新 options.txt 中的自带材质包")
    return True


def install(game_dir):
    game_dir = Path(game_dir).expanduser()
    if not SOURCE.is_file():
        print("[OpenMaver] 未找到内置材质包: {}".format(SOURCE), file=sys.stderr)
        return None
    pack_dir = game_dir / "resourcepacks"
    try:
        pack_dir.mkdir(parents=True, exist_ok=True)
    except OSError as exc:
        print("[OpenMaver] 无法创建 resourcepacks 目录: {}".format(exc), file=sys.stderr)
        return None

    target = pack_dir / PACK_NAME
    try:
        source_size = SOURCE.stat().st_size
        if not target.is_file() or target.stat().st_size != source_size:
            temp = pack_dir / (PACK_NAME + ".openmaver.tmp")
            shutil.copyfile(str(SOURCE), str(temp))
            os.replace(str(temp), str(target))
            print("[OpenMaver] 已安装自带材质包: {}".format(target))
        # 旧版用 § 颜色代码做文件名，在这个实例的 ASCII JVM 下会变成乱码并
        # 永远匹配不上；确认大小一致（就是同一个包）后删掉，避免列表里重复。
        legacy = pack_dir / ORIGINAL_PACK_NAME
        if legacy.is_file() and legacy.stat().st_size == source_size:
            legacy.unlink()
    except OSError as exc:
        print("[OpenMaver] 复制材质包失败: {}".format(exc), file=sys.stderr)
        return None

    marker = game_dir / MARKER
    managed = marker.is_file()
    if enable_options(game_dir / "options.txt", not managed) and not managed:
        try:
            marker.parent.mkdir(parents=True, exist_ok=True)
            marker.write_text("1\n", encoding="utf-8")
        except OSError:
            pass
    return target


def default_game_dir():
    instance_dir = os.environ.get("OPENMAVER_INSTANCE_DIR")
    if instance_dir:
        return Path(instance_dir).expanduser() / "minecraft"
    prism_root = Path(os.environ.get(
        "PRISM_ROOT", Path.home() / ".local/share/PrismLauncher")).expanduser()
    instance = os.environ.get("OPENMAVER_INSTANCE", "1.8.9(1)")
    return prism_root / "instances" / instance / "minecraft"


def main(argv):
    game_dir = None
    index = 0
    while index < len(argv):
        argument = argv[index]
        if argument in ("-h", "--help"):
            print("usage: resourcepack.py [--game-dir <minecraft dir>]")
            return 0
        if argument == "--game-dir":
            index += 1
            if index >= len(argv):
                print("缺少 --game-dir 参数", file=sys.stderr)
                return 2
            game_dir = argv[index]
        else:
            print("unknown argument: " + argument, file=sys.stderr)
            return 2
        index += 1
    install(game_dir or default_game_dir())
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
