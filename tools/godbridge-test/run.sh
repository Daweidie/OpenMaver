#!/usr/bin/env bash
# GodBridge（神桥 / 蹲起搭路）回归测试。
#
# 教材 → 不变量：
#   1. 12 / 10 / 7 tick 一方块 = 1.67 / 2.00 / 2.86 方块每秒；
#   2. S = 相机 +180°，S+D = 相机 +225°（视角与桥线 45°），且能反推；
#   3. 放块 → RELEASE（松 Shift）→ RESNEAK（贴边按回）→ WAIT_EDGE → 放块，
#      紧急余量/离地必须提前按回；
#   4. 源码里只有一处 C08、aim 只在 movementInput hook 写、Scaffold 让位、
#      配置 / ClickGUI / HUD 全部接上。
#
# 用法：tools/godbridge-test/run.sh
# 说明：没有 build/ 产物时自动用 javac 编一份（不依赖 cmake），
#       有产物时直接用 build/ 里的类（与正式构建一致）。
set -euo pipefail

repo_dir="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$repo_dir"

ensure_build() {
    if [[ -d build/mc-classes && -d build/mcstubs-classes && -d build/java-classes \
            && -n "$(ls -A build/mc-classes 2>/dev/null)" ]]; then
        return 0
    fi
    echo "[godbridge-test] build/ 里没有编译产物，先编一份 core + mcstubs + mc"
    local release_flags
    if javac --help 2>&1 | grep -q -- '--release'; then
        release_flags="--release 8 -Xlint:-options"
    else
        release_flags="-source 8 -target 8 -Xlint:-options"
    fi
    mkdir -p build/java-classes build/mcstubs-classes build/mc-classes
    # shellcheck disable=SC2086
    javac -nowarn $release_flags -encoding UTF-8 \
        -d build/java-classes $(find java -name '*.java')
    # shellcheck disable=SC2086
    javac -nowarn $release_flags -encoding UTF-8 \
        -d build/mcstubs-classes $(find mcstubs -name '*.java')
    # shellcheck disable=SC2086
    javac -nowarn $release_flags -encoding UTF-8 \
        -cp build/mcstubs-classes:build/java-classes \
        -d build/mc-classes $(find mc -name '*.java')
}

ensure_build

out_dir="${OPENMAVER_GODBRIDGE_TEST_OUT:-build/godbridge-test-classes}"
classpath="build/mc-classes:build/java-classes:build/mcstubs-classes"
mkdir -p "$out_dir"
javac -encoding UTF-8 -cp "$classpath" -d "$out_dir" tools/godbridge-test/GodBridgeCycleTest.java
java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -cp "$out_dir:$classpath" GodBridgeCycleTest
