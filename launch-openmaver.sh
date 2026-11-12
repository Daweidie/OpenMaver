#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "$0")" && pwd)"
cd "$repo_dir"

echo "[OpenMaver] building client and Forge integration..."
cmake --build build -j"${OPENMAVER_BUILD_JOBS:-2}"
exec python3 "$repo_dir/launch-openmaver.py" "$@"
