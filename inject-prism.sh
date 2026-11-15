#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "$0")" && pwd)"
cd "$repo_dir"

# --find / --list / --help only inspect the injector; do not touch the instance.
for argument in "$@"; do
    case "$argument" in
        --find|--list|--help|-h)
            exec "$repo_dir/build/bin/openmaver_injector" "$@"
            ;;
    esac
done

echo "[OpenMaver] building client and Forge integration..."
cmake --build build -j"${OPENMAVER_BUILD_JOBS:-2}"

injector="$repo_dir/build/bin/openmaver_injector"
agent_so="$repo_dir/build/lib/libopenmaver_core.so"
core_jar="$repo_dir/build/openmaver-core.jar"
mc_jar="$repo_dir/build/openmaver-mc.jar"

prism_root="${PRISM_ROOT:-$HOME/.local/share/PrismLauncher}"
instance_id="${OPENMAVER_INSTANCE:-1.8.9(1)}"
instance="${OPENMAVER_INSTANCE_DIR:-$prism_root/instances/$instance_id}"
mod_jar="$instance/minecraft/mods/OpenMaver.jar"
disabled_mod_jar="$mod_jar.disabled"

# Maver 自带的 2pimp v2 [16x] 材质包：复制进实例，首次安装时加入已启用列表。
if [ -f "$repo_dir/tools/resourcepack.py" ]; then
    python3 "$repo_dir/tools/resourcepack.py" --game-dir "$instance/minecraft" || true
fi

# Remove --force from the arguments forwarded to the injector.
force=0
inject_args=()
for argument in "$@"; do
    if [ "$argument" = "--force" ]; then
        force=1
    else
        inject_args+=("$argument")
    fi
done

jar_loaded_by() {
    local jar="$1"
    local alias="${2:-}"
    local pid fd target
    for pid in $(pgrep -x java || true); do
        for fd in /proc/"$pid"/fd/*; do
            target="$(readlink "$fd" 2>/dev/null || true)"
            target="${target% (deleted)}"
            if [ "$target" = "$jar" ] || { [ -n "$alias" ] && [ "$target" = "$alias" ]; }; then
                echo "$pid"
                return 0
            fi
        done
    done
    return 1
}

# Detect the current session before renaming the mod jar, because the process's
# open file descriptor follows the rename to OpenMaver.jar.disabled.
mod_pid=""
inject_pid=""
if [ "$force" -ne 1 ]; then
    mod_pid="$(jar_loaded_by "$mod_jar" "$disabled_mod_jar" || true)"
    inject_pid="$(jar_loaded_by "$mc_jar" "$core_jar" || true)"
fi

# Never mix mod loading and runtime injection in one game session.
if [ -f "$mod_jar" ]; then
    mv -f "$mod_jar" "$disabled_mod_jar"
    echo "[OpenMaver] disabled mod jar: $disabled_mod_jar"
fi

if [ -n "$mod_pid" ]; then
    echo "[OpenMaver] pid=$mod_pid 已在启动时加载 OpenMaver mod。"
    echo "[OpenMaver] mod 已重命名为 .disabled；请重启游戏后再运行注入。"
    exit 1
fi
if [ -n "$inject_pid" ]; then
    echo "[OpenMaver] pid=$inject_pid 已经注入过 OpenMaver。"
    echo "[OpenMaver] 请重启游戏后再注入新版本。"
    exit 1
fi

if [ "${#inject_args[@]}" -gt 0 ]; then
    exec "$injector" --re-self \
        --so "$agent_so" \
        --options "jar=$core_jar" \
        --mcjar "$mc_jar" \
        "${inject_args[@]}"
fi

exec "$injector" --re-self \
    --so "$agent_so" \
    --options "jar=$core_jar" \
    --mcjar "$mc_jar"
