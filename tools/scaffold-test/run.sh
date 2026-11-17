#!/usr/bin/env bash
# Scaffold 放置时序回归测试（Grim RotationPlace）。
#
# 验证三条不变量（见 design/SCAFFOLD_ROTATIONPLACE_HANDOFF.md）：
#   1. 只有「服务端已收到的 aim + rayTrace 命中」才允许发 C08；
#   2. 角度对不上时重新瞄准下一 tick 再放，重试有上限；
#   3. flushPendingPlace / tick() 里不再存在「拿新角度立即发 C08」的分支。
#
# 用法：tools/scaffold-test/run.sh
# 说明：没有 build/ 产物时自动用 javac 编一份（不依赖 cmake）。
set -euo pipefail

repo_dir="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$repo_dir"

ensure_build() {
    if [[ -d build/mc-classes && -d build/mcstubs-classes && -d build/java-classes \
            && -n "$(ls -A build/mc-classes 2>/dev/null)" ]]; then
        return 0
    fi
    echo "[scaffold-test] build/ 里没有编译产物，先编一份 core + mcstubs + mc"
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

out_dir="${OPENMAVER_SCAFFOLD_TEST_OUT:-build/scaffold-test-classes}"
classpath="build/mc-classes:build/java-classes:build/mcstubs-classes"
mkdir -p "$out_dir"
javac -encoding UTF-8 -cp "$classpath" -d "$out_dir" tools/scaffold-test/ScaffoldPlaceOrderTest.java
java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -cp "$out_dir:$classpath" ScaffoldPlaceOrderTest
