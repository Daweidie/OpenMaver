#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
out_dir=build/blockhit-test-classes
classpath=build/mc-classes:build/java-classes:build/mcstubs-classes
mkdir -p "$out_dir"
javac --release 8 -Xlint:-options -encoding UTF-8 -cp "$classpath" -d "$out_dir" \
    tools/blockhit-test/net/minecraft/item/ItemSword.java \
    tools/blockhit-test/org/lwjgl/input/Mouse.java \
    tools/blockhit-test/BlockHitTest.java
java -cp "$out_dir:$classpath" BlockHitTest
