#!/usr/bin/env bash
# 真实 Netty 4.0.23 + 输入测试替身，覆盖窗口包序、队列共存和容器移动。
set -euo pipefail
repo_dir="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$repo_dir"
out_dir="${OPENMAVER_MOVEDISABLER_TEST_OUT:-build/movedisabler-test-classes}"
netty_jar="${NETTY_JAR:-${PRISM_ROOT:-$HOME/.local/share/PrismLauncher}/libraries/io/netty/netty-all/4.0.23.Final/netty-all-4.0.23.Final.jar}"
if [[ ! -f "$netty_jar" ]]; then
    echo '[movedisabler-test] Set NETTY_JAR to netty-all-4.0.23.Final.jar' >&2
    exit 1
fi
classpath="$netty_jar:build/mc-classes:build/java-classes:build/mcstubs-classes"
mkdir -p "$out_dir"
mapfile -t sources < <(rg --files tools/attackreduce-test tools/movedisabler-test -g '*.java')
javac -source 8 -target 8 -Xlint:-options -encoding UTF-8 -cp "$classpath" -d "$out_dir" "${sources[@]}"
java -Dfile.encoding=UTF-8 -cp "$out_dir:$classpath" MoveDisablerTest
java -Dfile.encoding=UTF-8 -cp "$out_dir:$classpath" InventoryMoveTest
java -Dfile.encoding=UTF-8 -cp "$out_dir:$classpath" IntaveMoveDebugTest
# MCP aliases masked production reflection errors. Repeat the packet tests with
# SRG-only classes generated independently from the installed Forge mapping.
srg_src="$out_dir/srg-src"
srg_out="$out_dir/srg-classes"
python3 tools/movedisabler-test/runtime_packets.py --out "$srg_src"
mkdir -p "$srg_out"
mapfile -t srg_sources < <(rg --files "$srg_src" -g '*.java')
javac -source 8 -target 8 -Xlint:-options -encoding UTF-8 -cp "$out_dir:$classpath" -d "$srg_out" "${srg_sources[@]}"
java -Dfile.encoding=UTF-8 -Dopenmaver.test.mapping=SRG -cp "$srg_out:$out_dir:$classpath" MoveDisablerTest
