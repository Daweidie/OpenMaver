#!/usr/bin/env bash
# Scaffold 放置时序回归测试（Grim RotationPlace）。
#
# 验证三条不变量（见 design/SCAFFOLD_ROTATIONPLACE_HANDOFF.md）：
#   1. 只有「服务端已收到的 aim + rayTrace 命中」才允许发 C08；
#   2. 角度对不上时重新瞄准下一 tick 再放，重试有上限；
#   3. flushPendingPlace / tick() 里不再存在「拿新角度立即发 C08」的分支。
#
# 用法：tools/scaffold-test/run.sh
# 前置：先构建一次（需要 build/mc-classes 与 build/mcstubs-classes）
#   cmake --build build -j
set -euo pipefail

repo_dir="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$repo_dir"

out_dir="${OPENMAVER_SCAFFOLD_TEST_OUT:-build/scaffold-test-classes}"
if [[ ! -d build/mc-classes || ! -d build/mcstubs-classes || ! -d build/java-classes ]]; then
    echo "[scaffold-test] 先构建一次：cmake --build build -j" >&2
    exit 1
fi

classpath="build/mc-classes:build/java-classes:build/mcstubs-classes"
mkdir -p "$out_dir"
javac -encoding UTF-8 -cp "$classpath" -d "$out_dir" tools/scaffold-test/ScaffoldPlaceOrderTest.java
java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -cp "$out_dir:$classpath" ScaffoldPlaceOrderTest
