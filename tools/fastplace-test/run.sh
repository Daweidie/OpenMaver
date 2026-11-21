#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$repo_dir"
out_dir="${OPENMAVER_FASTPLACE_TEST_OUT:-build/fastplace-test-classes}"
classpath="build/mc-classes:build/java-classes:build/mcstubs-classes"
mkdir -p "$out_dir"
mapfile -t sources < <(rg --files tools/fastplace-test -g '*.java')
javac --release 8 -Xlint:-options -encoding UTF-8 -cp "$classpath" -d "$out_dir" "${sources[@]}"
java -Dfile.encoding=UTF-8 -cp "$out_dir:$classpath" FastPlaceTest "$out_dir"
