#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$repo_dir"
out_dir="${OPENMAVER_TELLY_TEST_OUT:-build/telly-test-classes}"
classpath="build/mc-classes:build/java-classes:build/mcstubs-classes"
mkdir -p "$out_dir"
mapfile -t sources < <(rg --files tools/telly-test -g '*.java')
javac --release 8 -Xlint:-options -encoding UTF-8 -cp "$classpath" -d "$out_dir" "${sources[@]}" \
    tools/chest-test/support/net/minecraft/util/BlockPos.java \
    tools/movedisabler-test/support/org/lwjgl/input/Keyboard.java \
    tools/attackreduce-test/org/lwjgl/input/Mouse.java
java -Dfile.encoding=UTF-8 -cp "$out_dir:$classpath" TellyBehaviorTest
