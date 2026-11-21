#!/usr/bin/env bash
# SafeWalk（方块边缘潜行）回归测试。
#
# 覆盖四条不变量（见 design/SAFEWALK_HANDOFF.md）：
#   1. 只有“站在地面 + 有输入 + 探针判定踏空”才接管 Shift；让位与玩家自按一律不动；
#   2. 探针方向与 1.8.9 moveFlying 同一个换算，位置落在脚下、不碰碰撞箱高度；
#   3. 只写 keyBindSneak.pressed（原版自己发 C0B），不写 motion / rotation、不发伪造包；
#   4. movement.safewalk 配置路径、properties 往返、每 tick 驱动一次、ClickGUI 面板。
#
# 用法：tools/safewalk-test/run.sh
# 说明：没有 build/ 产物时自动用 javac 编一份（不依赖 cmake）。
set -euo pipefail

repo_dir="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$repo_dir"

ensure_build() {
    if [[ -d build/mc-classes && -d build/mcstubs-classes && -d build/java-classes \
            && -n "$(ls -A build/mc-classes 2>/dev/null)" ]]; then
        return 0
    fi
    echo "[safewalk-test] build/ 里没有编译产物，先编一份 core + mcstubs + mc"
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

out_dir="${OPENMAVER_SAFEWALK_TEST_OUT:-build/safewalk-test-classes}"
classpath="build/mc-classes:build/java-classes:build/mcstubs-classes"
mkdir -p "$out_dir"
javac -encoding UTF-8 -cp "$classpath" -d "$out_dir" tools/safewalk-test/SafeWalkEdgeTest.java
java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -cp "$out_dir:$classpath" SafeWalkEdgeTest
