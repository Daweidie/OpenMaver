#!/usr/bin/env bash
# Reach 距离、上报视角与聊天调试回归测试。
#
# 验证：
#   - 0~5 米原始模式、独立 Grim 距离校验、MCP/SRG/obf 上报坐标与视角
#   - SWING / SKIP / HIT 行字段（cap、distance、border、yaw/pitch、anim）
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
# 事务延迟测试需要真实 netty（EmbeddedChannel）；mcstubs 里的 netty 只有签名。
netty_jar="${OPENMAVER_NETTY_JAR:-$HOME/.local/share/PrismLauncher/libraries/io/netty/netty-all/4.0.23.Final/netty-all-4.0.23.Final.jar}"
if [[ ! -f "$netty_jar" ]]; then
    netty_jar="$(ls -1 "$HOME/.local/share/PrismLauncher/libraries"/io/netty/netty-all/*/netty-all-*.jar 2>/dev/null | head -1 || true)"
fi
runtime_cp="$classpath"
if [[ -n "$netty_jar" && -f "$netty_jar" ]]; then
    runtime_cp="$out_dir:$netty_jar:$classpath"
else
    echo "[reach-test] netty-all 未找到，跳过 TransactionDelayTest" >&2
fi
mkdir -p "$out_dir"
javac -encoding UTF-8 -cp "$runtime_cp" -d "$out_dir" \
    tools/reach-test/ReachDebugTest.java tools/reach-test/ReachSyncTest.java \
    tools/reach-test/TransactionDelayTest.java
java -cp "$out_dir:$classpath" ReachDebugTest
java -cp "$out_dir:$classpath" ReachSyncTest
if [[ -n "$netty_jar" && -f "$netty_jar" ]]; then
    java -cp "$runtime_cp" TransactionDelayTest
    mkdir -p "$out_dir/order"
    javac -encoding UTF-8 -cp "$runtime_cp" -d "$out_dir/order" \
        tools/reach-test/order-support/net/minecraft/client/Minecraft.java \
        tools/reach-test/TransactionOrderTest.java
    java -cp "$out_dir/order:$runtime_cp" TransactionOrderTest
fi
