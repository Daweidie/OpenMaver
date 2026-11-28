#!/usr/bin/env bash
# Build first. Driver/cache fixtures plus a surfaceless EGL pixel test.
set -euo pipefail
repo_dir="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$repo_dir"
out_dir="build/render-test-classes"
classpath="build/mc-classes:build/java-classes:build/mcstubs-classes"
mkdir -p "$out_dir"
mapfile -t sources < <(rg --files tools/render-test -g '*.java')
javac --release 8 -Xlint:-options -encoding UTF-8 -cp "$classpath" -d "$out_dir" "${sources[@]}"
java -Djava.awt.headless=true -Dfile.encoding=UTF-8 -cp "$out_dir:$classpath" PlayerXrayStateTest
java -Djava.awt.headless=true -Dfile.encoding=UTF-8 -cp "$out_dir:$classpath" EffectTagTest
java -Djava.awt.headless=true -Dfile.encoding=UTF-8 -cp "$out_dir:$classpath" PlayerEspOutlineTest
python3 tools/render-test/outline_gl_test.py
python3 tools/render-test/check_outline_runtime.py
python3 tools/render-test/check_runtime_members.py
