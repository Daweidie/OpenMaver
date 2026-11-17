#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# 箱子搬运回归测试（离线，不需要启动 Minecraft）。
#
# 用 tools/chest-test/support 里的最小 MC 替身驱动真实的 mc/ChestFeature：
#   - 可见模式（默认）：原版 GuiChest 留在屏幕上，界面里 shift-click 搬完再关箱退出
#   - 内容同步门：服务端窗口物品包（S30）到过这个 windowId 才动手点格子
#   - CLICK GAP：两批 windowClick 之间隔开，不在一个 tick 里连点
#   - 静默模式（SHOW CHEST GUI=off）：界面同一 tick 内收起、容器照常可用、
#     搬完自动 C0D 关窗
#   - TAKE 过滤、shift-click 整摞搬运、lowerChestInventory 读不到时的兜底
#   - AUTO SWEEP：不用按键，准星停留后自动开箱 + 搬空
#   - 玩家自己开的箱子界面（AUTO LOOT / AUTO CLOSE）不受影响
#   - 容器开着时打开别的界面会先关容器；创造物品栏不会被当成箱子
#
# 用法：tools/chest-test/run.sh
# 前置：先构建一次（需要 build/mc-classes、build/mcstubs-classes、build/java-classes）
#   cmake --build build -j
# ---------------------------------------------------------------------------
set -euo pipefail

repo_dir="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$repo_dir"

out_dir="${OPENMAVER_CHEST_TEST_OUT:-build/chest-test-classes}"
if [[ ! -d build/mc-classes || ! -d build/mcstubs-classes || ! -d build/java-classes ]]; then
    echo "[chest-test] 先构建一次：cmake --build build -j" >&2
    exit 1
fi

# 测试替身必须排在 mcstubs 之前，否则同名 stub 会盖掉替身。
classpath="$out_dir:build/mc-classes:build/java-classes:build/mcstubs-classes"
mkdir -p "$out_dir"
javac -encoding UTF-8 -cp "build/mc-classes:build/java-classes:build/mcstubs-classes" \
    -d "$out_dir" \
    $(find tools/chest-test -name '*.java')

java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 \
    -cp "$classpath" ChestStealerTest
