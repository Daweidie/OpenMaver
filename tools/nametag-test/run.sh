#!/usr/bin/env bash
# Build first with cmake --build build; the renderer test uses compile-time GL stubs.
set -euo pipefail
repo_dir="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$repo_dir"
out_dir="build/nametag-test-classes"
classpath="build/mc-classes:build/java-classes:build/mcstubs-classes"
mkdir -p "$out_dir"
javac --release 8 -Xlint:-options -encoding UTF-8 -cp "$classpath" -d "$out_dir" \
    tools/nametag-test/support/org/lwjgl/opengl/GL11.java \
    tools/nametag-test/support/net/minecraft/client/Minecraft.java \
    tools/nametag-test/support/net/minecraft/client/multiplayer/WorldClient.java \
    tools/nametag-test/NameTagTest.java
java -Djava.awt.headless=true -Dfile.encoding=UTF-8 -cp "$out_dir:$classpath" NameTagTest
python3 tools/check_runtime_fields.py
