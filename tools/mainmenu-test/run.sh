#!/usr/bin/env bash
# Build first with cmake --build build; no Minecraft window or network required.
set -euo pipefail
cd "$(dirname "$0")/../.."
out_dir=build/mainmenu-test-classes
classpath=build/mc-classes:build/java-classes:build/mcstubs-classes
mkdir -p "$out_dir"
mapfile -t sources < <(rg --files tools/mainmenu-test -g '*.java')
javac --release 8 -Xlint:-options -encoding UTF-8 -cp "$classpath" -d "$out_dir" \
    "${sources[@]}" tools/account-test/support/HudFont.java \
    tools/account-test/support/net/minecraft/client/Minecraft.java mc/GuiMaverMainMenu.java
java -Djava.awt.headless=true -Dfile.encoding=UTF-8 -cp "$out_dir:$classpath" MainMenuCompatibilityTest
python3 tools/mainmenu-test/check_runtime.py
