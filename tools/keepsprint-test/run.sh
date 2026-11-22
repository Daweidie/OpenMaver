#!/usr/bin/env bash
# KeepSprint：旧 OMNI + 默认 ROTATE（输入/物理/C03/相机恢复）的回归测试。
# 每次重建打包后的 SRG jar；rotation-support 只有 SRG 成员，检查真实字段链接。
set -euo pipefail

repo_dir="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$repo_dir"

ensure_build() {
    # 不复用可能过期的类：源码改过后必须重新编译并 reobf，再测实际 jar。
    if [[ -f build/CMakeCache.txt ]] && command -v cmake >/dev/null 2>&1; then
        cmake --build build --target openmaver_mc_jar -j 4
        return 0
    fi
    echo "[keepsprint-test] 编译 core + mcstubs + mc，并打包 SRG jar"
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
    mkdir -p build/tools build/mc-classes-reobf
    # shellcheck disable=SC2086
    javac -nowarn $release_flags -encoding UTF-8 -d build/tools tools/McpReobf.java
    java -cp build/tools McpReobf build/mc-classes build/mc-classes-reobf
    jar cf build/openmaver-mc.jar -C build/mc-classes-reobf .
}

ensure_build

out_dir="${OPENMAVER_KEEPSPRINT_TEST_OUT:-build/keepsprint-test-classes}"
classpath="build/mc-classes:build/java-classes:build/mcstubs-classes"
mkdir -p "$out_dir"
mapfile -t sources < <(find tools/keepsprint-test -name '*.java' ! -path '*/rotation-support/*' ! -name 'KeepSprintRotationTest.java')
javac -encoding UTF-8 -cp "$classpath" -d "$out_dir" "${sources[@]}"
java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -cp "$out_dir:$classpath" KeepSprintTest
java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -cp "$out_dir:$classpath" KeepSprintRuntimeTest
java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 \
    -cp "$out_dir:build/openmaver-mc.jar:build/java-classes:build/mcstubs-classes" KeepSprintBridgeTest
java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 \
    -cp "$out_dir:build/openmaver-mc.jar:build/java-classes:build/mcstubs-classes" KeepSprintPhysicsTest

rotation_out="$out_dir/rotation"
mkdir -p "$rotation_out"
mapfile -t rotation_sources < <(find tools/keepsprint-test/rotation-support -name '*.java')
javac -encoding UTF-8 -cp "$out_dir:build/openmaver-mc.jar:build/java-classes:build/mcstubs-classes" \
    -d "$rotation_out" "${rotation_sources[@]}" tools/keepsprint-test/KeepSprintRotationTest.java
java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 \
    -cp "$rotation_out:$out_dir:build/openmaver-mc.jar:build/java-classes:build/mcstubs-classes" KeepSprintRotationTest
