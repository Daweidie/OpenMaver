#!/usr/bin/env bash
# Aim Assist 目标过滤、锁定更新与落点抖动验证。
#
# 用法：tools/aim-test/run.sh
# 前置：先构建一次（需要 build/mc-classes 与 build/mcstubs-classes）
#   cmake --build build -j
set -euo pipefail

repo_dir="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$repo_dir"

out_dir="${OPENMAVER_AIM_TEST_OUT:-build/aim-test-classes}"
if [[ ! -d build/mc-classes || ! -d build/mcstubs-classes || ! -d build/java-classes ]]; then
    echo "[aim-test] 先构建一次：cmake --build build -j" >&2
    exit 1
fi

classpath="build/mc-classes:build/java-classes:build/mcstubs-classes"
mkdir -p "$out_dir"
javac -encoding UTF-8 -cp "$classpath" -d "$out_dir" \
    tools/aim-test/AimJitterTest.java tools/aim-test/AimTargetTest.java
java -Djava.awt.headless=true -cp "$out_dir:$classpath" AimTargetTest
java -cp "$out_dir:$classpath" AimJitterTest
