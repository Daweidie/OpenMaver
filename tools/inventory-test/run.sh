#!/usr/bin/env bash
# 背包整理（Inventory Manager）的纯逻辑回归：不需要 MC / Forge / Netty。
# 直接编译 mc/InventoryRules.java + mc/InventoryPlanner.java + 测试，跑假容器模拟。
set -euo pipefail
repo_dir="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$repo_dir"
out_dir="${OPENMAVER_INVENTORY_TEST_OUT:-build/inventory-test-classes}"
mkdir -p "$out_dir"
javac -source 8 -target 8 -Xlint:-options -encoding UTF-8 \
    -d "$out_dir" \
    mc/InventoryRules.java mc/InventoryPlanner.java \
    tools/inventory-test/InventoryTest.java
java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 \
    -cp "$out_dir" InventoryTest
