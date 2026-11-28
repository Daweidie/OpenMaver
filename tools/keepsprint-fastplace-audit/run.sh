#!/usr/bin/env bash
# Regress the camera / movement-yaw handoff without connecting to a server.
set -euo pipefail

repo_dir="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$repo_dir"
cmake --build build --target openmaver_mc_jar -j 4

audit_out="$(mktemp -d /tmp/openmaver-placement-audit.XXXXXX)"
trap 'rm -rf -- "$audit_out"' EXIT
mkdir -p "$audit_out/base" "$audit_out/rotation"
mapfile -t support < <(rg --files tools/keepsprint-test/support -g '*.java')
javac --release 8 -Xlint:-options -encoding UTF-8 \
    -cp build/mc-classes:build/java-classes:build/mcstubs-classes \
    -d "$audit_out/base" "${support[@]}" \
    tools/keepsprint-test/bridge-support/net/minecraft/client/Minecraft.java \
    tools/keepsprint-test/KeepSprintRuntimeTest.java

audit_cp="$audit_out/base:build/openmaver-mc.jar:build/java-classes:build/mcstubs-classes"
mapfile -t rotation_support < <(rg --files tools/keepsprint-test/rotation-support -g '*.java')
javac --release 8 -Xlint:-options -encoding UTF-8 -cp "$audit_cp" \
    -d "$audit_out/rotation" "${rotation_support[@]}" \
    tools/keepsprint-test/KeepSprintRotationTest.java \
    tools/fastplace-test/net/minecraft/item/ItemBlock.java \
    tools/keepsprint-fastplace-audit/KeepSprintFastPlaceAudit.java
java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 \
    -Dopenmaver.features="$audit_out/features.properties" \
    -Dopenmaver.config="$audit_out/config.json" \
    -cp "$audit_out/rotation:$audit_cp" KeepSprintFastPlaceAudit
