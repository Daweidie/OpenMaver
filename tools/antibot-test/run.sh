#!/usr/bin/env bash
# Build first with cmake --build build; no running Minecraft instance is needed.
set -euo pipefail
repo_dir="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$repo_dir"
out_dir="build/antibot-test-classes"
classpath="build/mc-classes:build/java-classes:build/mcstubs-classes"
mkdir -p "$out_dir"
javac --release 8 -Xlint:-options -encoding UTF-8 -cp "$classpath" -d "$out_dir" \
    tools/antibot-test/AntiBotTest.java
java -Djava.awt.headless=true -Dfile.encoding=UTF-8 -cp "$out_dir:$classpath" AntiBotTest
