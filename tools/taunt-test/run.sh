#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
out_dir=build/taunt-test-classes
classpath=build/mc-classes:build/java-classes:build/mcstubs-classes
mkdir -p "$out_dir"
javac --release 8 -Xlint:-options -encoding UTF-8 -cp "$classpath" -d "$out_dir" \
    tools/taunt-test/TauntTest.java
java -cp "$out_dir:$classpath" TauntTest
