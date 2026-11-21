#!/usr/bin/env bash
# Reach 聊天调试（ReachDebug）回归测试。
#
# 验证：
#   - SWING / SKIP / HIT 行的字段与中文说明一致（cap、extra、border、yaw/pitch、anim）
#   - 聊天框每秒限流 10 行，HIT 等关键行不受限，日志历史不丢事件
#   - reach.debug 写入 openmaver.properties，热重载能同步开关与 Reach 参数
#
# 用法：tools/reach-test/run.sh
# 前置：先构建一次（需要 build/mc-classes、build/mcstubs-classes、build/java-classes）
#   cmake --build build -j
set -euo pipefail

repo_dir="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$repo_dir"

out_dir="${OPENMAVER_REACH_TEST_OUT:-build/reach-test-classes}"
if [[ ! -d build/mc-classes || ! -d build/mcstubs-classes || ! -d build/java-classes ]]; then
    echo "[reach-test] 先构建一次：cmake --build build -j" >&2
    exit 1
fi

classpath="build/mc-classes:build/java-classes:build/mcstubs-classes"
mkdir -p "$out_dir"
javac -encoding UTF-8 -cp "$classpath" -d "$out_dir" tools/reach-test/ReachDebugTest.java
java -cp "$out_dir:$classpath" ReachDebugTest
