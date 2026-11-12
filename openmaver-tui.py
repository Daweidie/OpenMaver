#!/usr/bin/env python3
"""Terminal settings for OpenMaver feature switches."""

import curses
import os
from pathlib import Path
import tempfile


def settings_path():
    instance = Path(os.environ.get(
        "OPENMAVER_INSTANCE_DIR",
        Path.home() / ".local/share/PrismLauncher/instances/1.8.9(1)"))
    return instance.expanduser() / "minecraft/config/openmaver.properties"


def read_settings(path):
    values = {"aim": False, "esp": False}
    try:
        for line in path.read_text(encoding="utf-8").splitlines():
            key, separator, value = line.partition("=")
            if separator and key.strip() in values:
                values[key.strip()] = value.strip().lower() == "true"
    except FileNotFoundError:
        pass
    return values


def save_settings(path, values):
    path.parent.mkdir(parents=True, exist_ok=True)
    content = "aim={}\nesp={}\n".format(
        str(values["aim"]).lower(), str(values["esp"]).lower())
    with tempfile.NamedTemporaryFile(
            "w", encoding="utf-8", dir=str(path.parent), delete=False) as stream:
        stream.write(content)
        temporary = Path(stream.name)
    os.replace(str(temporary), str(path))


def run_tui(screen, path):
    curses.curs_set(0)
    screen.keypad(True)
    values = read_settings(path)
    status = "Loaded {}".format(path)
    while True:
        screen.erase()
        screen.addstr(0, 0, "OpenMaver settings")
        screen.addstr(2, 0, "1  Aim assist: {}".format("ON" if values["aim"] else "OFF"))
        screen.addstr(3, 0, "2  Entity ESP:  {}".format("ON" if values["esp"] else "OFF"))
        screen.addstr(5, 0, "Changes are saved immediately and apply within one second.")
        screen.addstr(7, 0, "Press 1/2 to toggle, r to reload, q to quit.")
        screen.addstr(9, 0, status[:max(0, curses.COLS - 1)])
        screen.refresh()
        key = screen.getch()
        if key in (ord("q"), ord("Q"), 27):
            return
        if key in (ord("1"), ord("2")):
            feature = "aim" if key == ord("1") else "esp"
            values[feature] = not values[feature]
            save_settings(path, values)
            status = "Saved; {} is {}".format(feature, "ON" if values[feature] else "OFF")
        elif key in (ord("r"), ord("R")):
            values = read_settings(path)
            status = "Reloaded {}".format(path)


def run_basic(path):
    values = read_settings(path)
    print("OpenMaver settings")
    print("1  Aim assist: {}".format("ON" if values["aim"] else "OFF"))
    print("2  Entity ESP:  {}".format("ON" if values["esp"] else "OFF"))
    print("Enter 1 or 2 to toggle, q to quit.")
    while True:
        choice = input("> ").strip().lower()
        if choice == "q":
            return
        if choice in ("1", "2"):
            feature = "aim" if choice == "1" else "esp"
            values[feature] = not values[feature]
            save_settings(path, values)
            print("{}: {}".format(feature, "ON" if values[feature] else "OFF"))


def main():
    path = settings_path()
    try:
        curses.wrapper(run_tui, path)
    except curses.error:
        run_basic(path)


if __name__ == "__main__":
    main()
