#!/usr/bin/env bash
# OpenMaver 构建自检：把“到底哪一步挂了”压缩成一段可以直接贴回来的输出。
#
#   1. 工具链版本（cmake / javac / java）
#   2. build/ 的状态（是否存在 CMakeCache.txt、类目录是否为空）
#   3. 没有 cache 就先 configure，然后 build，日志写 build/doctor-build.log
#   4. 跑 GodBridge / Scaffold 两套回归测试
#
# 用法：tools/build-doctor.sh
set -uo pipefail

repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
cd "$repo_dir"

echo "== repo =="
pwd
echo
echo "== toolchain =="
cmake --version 2>&1 | head -1 || echo "cmake: MISSING"
javac -version 2>&1 || echo "javac: MISSING"
java -version 2>&1 | head -1 || echo "java: MISSING"
echo

echo "== build/ state =="
if [[ -d build ]]; then
    if [[ -f build/CMakeCache.txt ]]; then
        echo "build/CMakeCache.txt      : present (configured)"
    else
        echo "build/CMakeCache.txt      : MISSING (目录存在但没配置过)"
    fi
    for dir in java-classes mcstubs-classes mc-classes; do
        if [[ -d "build/$dir" ]]; then
            count=$(find "build/$dir" -name '*.class' | wc -l)
            echo "build/$dir : ${count} class files"
        else
            echo "build/$dir : missing"
        fi
    done
else
    echo "build/ : missing"
fi
echo

echo "== configure =="
if [[ ! -f build/CMakeCache.txt ]]; then
    cmake -S . -B build -DCMAKE_BUILD_TYPE=Release || {
        echo "[doctor] configure FAILED"; exit 2; }
else
    echo "already configured, skip"
fi
echo

echo "== build =="
mkdir -p build
if cmake --build build -j"${OPENMAVER_BUILD_JOBS:-2}" > build/doctor-build.log 2>&1; then
    echo "build OK"
    tail -3 build/doctor-build.log
else
    echo "build FAILED — last 60 lines of build/doctor-build.log:"
    echo "--------------------------------------------------------"
    tail -60 build/doctor-build.log
    echo "--------------------------------------------------------"
    exit 3
fi
echo

echo "== regression =="
tools/godbridge-test/run.sh || exit 4
tools/scaffold-test/run.sh || exit 5
