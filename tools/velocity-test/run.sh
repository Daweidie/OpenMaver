#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Velocity 包层测试：用真实 netty-all 4.0.23.Final（MC 1.8.9 自带版本）的
# EmbeddedChannel 驱动 VelocityPacketHook，验证：
#   - 插入点（packet_handler / encoder 之后）
#   - 入站 S12 吞包与向量解析、他人包放行、1/1 比例不拦包
#   - MODIFY / REVERSAL / STRAFE / JUMP RESET / BLINK 的行为
#   - blink 的扣包与补发顺序、关闭模块后的放行
#   - 无管线兜底的运动特征嗅探（击退 vs 冲刺跳 vs 下落）
#
# 用法：
#   tools/velocity-test/run.sh
# 环境变量：
#   NETTY_JAR  指定 netty-all 4.0.23.Final 的路径（默认在 PrismLauncher 里找）
#
# 前置：先构建一次（需要 build/mc-classes、build/mcstubs-classes、build/java-classes）
#   cmake --build build -j
# ---------------------------------------------------------------------------
set -euo pipefail

repo_dir="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$repo_dir"

out_dir="${OPENMAVER_VELOCITY_TEST_OUT:-build/velocity-test-classes}"
test_src="tools/velocity-test"

if [[ ! -d build/mc-classes || ! -d build/mcstubs-classes || ! -d build/java-classes ]]; then
    echo "[velocity-test] 先构建一次：cmake --build build -j" >&2
    exit 1
fi

netty_jar="${NETTY_JAR:-}"
if [[ -z "$netty_jar" ]]; then
    prism_root="${PRISM_ROOT:-$HOME/.local/share/PrismLauncher}"
    netty_jar="$(find "$prism_root/libraries/io/netty/netty-all" -name 'netty-all-4.0.23.Final.jar' 2>/dev/null | head -1 || true)"
fi
if [[ -z "$netty_jar" || ! -f "$netty_jar" ]]; then
    echo "[velocity-test] 找不到 netty-all 4.0.23.Final；用 NETTY_JAR=/path/to/jar 指定" >&2
    exit 1
fi

# 真实 netty 必须排在 mcstubs 之前：stub 与真实类同名，排在前面会覆盖真实实现。
classpath="$netty_jar:build/mc-classes:build/java-classes:build/mcstubs-classes"

mkdir -p "$out_dir"
echo "[velocity-test] netty: $netty_jar"
javac -encoding UTF-8 -cp "$classpath" -d "$out_dir" \
    $(find "$test_src" -name '*.java')

echo "[velocity-test] 1/2 包层行为（EmbeddedChannel）"
java -cp "$out_dir:$classpath" VelocityHookTest

echo "[velocity-test] 2/2 管线注入/重连（真实 netty 通道）"
java -cp "$out_dir:$classpath" VelocityInstallTest
