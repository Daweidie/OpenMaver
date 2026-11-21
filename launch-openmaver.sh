#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "$0")" && pwd)"
cd "$repo_dir"

# Inspection must leave both the build and the game instance untouched.
for argument in "$@"; do
    case "$argument" in
        --dry-run|--help|-h)
            exec python3 "$repo_dir/launch-openmaver.py" "$@"
            ;;
    esac
done

# build/ 可能不存在、也可能是个空目录（例如手动 mkdir 过 build/java-classes 之类）。
# 这两种情况 `cmake --build build` 都会报 "could not load cache"，
# 所以先看 CMakeCache.txt 而不是只看目录。
if [[ ! -f build/CMakeCache.txt ]]; then
    echo "[OpenMaver] build/ 还没配置，先执行 cmake -S . -B build"
    cmake -S . -B build -DCMAKE_BUILD_TYPE=Release
fi

echo "[OpenMaver] building client and Forge integration..."
cmake --build build -j"${OPENMAVER_BUILD_JOBS:-2}"
exec python3 "$repo_dir/launch-openmaver.py" "$@"
