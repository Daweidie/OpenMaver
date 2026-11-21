#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# AttackReduce 包层测试：用真实 netty-all 4.0.23.Final（MC 1.8.9 自带版本）的
# EmbeddedChannel 驱动 AttackReducePacketHook，验证：
#   - 默认 SLAP ATTACK：幅度窗口、单次 .6 减速、重复攻击、动作闸门与 Aura 协作
#   - SPRINT HIT：S12 原样、vanilla 应用后开窗、原版攻击减速、失败条件
#   - SIMPLE 百分比缩放；他人包、关闭状态、移动/事务包正常放行
#   - 模式及百分比持久化和输入边界
#   - 真实 Netty pipeline 的安装位置、重连与自愈
#
# 用法：
#   tools/attackreduce-test/run.sh
# 环境变量：
#   NETTY_JAR  指定 netty-all 4.0.23.Final 的路径（默认在 PrismLauncher 里找）
#
# 前置：先构建一次（需要 build/mc-classes、build/mcstubs-classes、build/java-classes）
#   cmake --build build -j
# ---------------------------------------------------------------------------
set -euo pipefail

repo_dir="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$repo_dir"

out_dir="${OPENMAVER_ATTACKREDUCE_TEST_OUT:-build/attackreduce-test-classes}"
test_src="tools/attackreduce-test"

if [[ ! -d build/mc-classes || ! -d build/mcstubs-classes || ! -d build/java-classes ]]; then
    echo "[attackreduce-test] 先构建一次：cmake --build build -j" >&2
    exit 1
fi

netty_jar="${NETTY_JAR:-}"
if [[ -z "$netty_jar" ]]; then
    prism_root="${PRISM_ROOT:-$HOME/.local/share/PrismLauncher}"
    netty_jar="$(find "$prism_root/libraries/io/netty/netty-all" -name 'netty-all-4.0.23.Final.jar' 2>/dev/null | head -1 || true)"
fi
if [[ -z "$netty_jar" || ! -f "$netty_jar" ]]; then
    echo "[attackreduce-test] 找不到 netty-all 4.0.23.Final；用 NETTY_JAR=/path/to/jar 指定" >&2
    exit 1
fi

# 真实 netty 必须排在 mcstubs 之前：stub 与真实类同名，排在前面会覆盖真实实现。
classpath="$netty_jar:build/mc-classes:build/java-classes:build/mcstubs-classes"

mkdir -p "$out_dir"
echo "[attackreduce-test] netty: $netty_jar"
javac -source 8 -target 8 -Xlint:-options -encoding UTF-8 -cp "$classpath" -d "$out_dir" \
    $(find "$test_src" -name '*.java')

echo "[attackreduce-test] 1/5 SIMPLE 包层行为（EmbeddedChannel）"
java -cp "$out_dir:$classpath" AttackReduceHookTest
echo "[attackreduce-test] 2/5 SPRINT HIT 主线程时序与原版攻击"
java -cp "$out_dir:$classpath" AttackReduceSprintHitTest
echo "[attackreduce-test] 3/5 SLAP ATTACK 参考机制/动作闸门/Aura 协作"
java -cp "$out_dir:$classpath" AttackReduceSlapTest
echo "[attackreduce-test] 4/5 管线安装/重连（真实 Netty channel）"
java -cp "$out_dir:$classpath" AttackReduceInstallTest
echo "[attackreduce-test] 5/5 聊天调试/按键冲突/主线程隔离"
java -cp "$out_dir:$classpath" AttackReduceChatDebugTest
