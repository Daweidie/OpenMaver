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

# 可选：先构建项目，再用真实反射工具验证 ItemStack 元数据适配与运行期 SRG 名称。
if [[ "${1:-}" == "--feature" ]]; then
    if [[ ! -d build/mc-classes || ! -d build/mcstubs-classes || ! -d build/java-classes ]]; then
        echo "[inventory-test] --feature 需要先构建：cmake --build build -j" >&2
        exit 1
    fi
    classpath="$out_dir:build/mc-classes:build/java-classes:build/mcstubs-classes"
    javac -source 8 -target 8 -Xlint:-options -encoding UTF-8 -cp "$classpath" \
        -d "$out_dir" mc/InventoryManagerFeature.java tools/inventory-test/InventoryFeatureTest.java
    java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 \
        -cp "$classpath" InventoryFeatureTest
fi
