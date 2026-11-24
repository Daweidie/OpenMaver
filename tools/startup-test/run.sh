#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
out_dir=build/startup-test-classes
classpath=build/mc-classes:build/java-classes:build/mcstubs-classes
mkdir -p "$out_dir"
javac --release 8 -Xlint:-options -encoding UTF-8 -cp "$classpath" -d "$out_dir" \
    tools/startup-test/net/minecraft/client/Minecraft.java tools/startup-test/ClientStartupTest.java
java -Djava.awt.headless=true -cp "$out_dir:$classpath" ClientStartupTest
python3 tools/check_runtime_fields.py
