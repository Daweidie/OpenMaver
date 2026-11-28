#!/usr/bin/env bash
# Local callbacks + fake official services; never opens a browser or uses real accounts.
set -euo pipefail
cd "$(dirname "$0")/../.."
out_dir=build/account-test-classes
classpath=build/mc-classes:build/java-classes:build/mcstubs-classes
mkdir -p "$out_dir"
mapfile -t sources < <(rg --files tools/account-test -g '*.java')
javac --release 8 -Xlint:-options -encoding UTF-8 -cp "$classpath" -d "$out_dir" \
    "${sources[@]}" mc/AuthJson.java mc/MicrosoftAuth.java mc/MaverAccounts.java \
    mc/GuiMaverAccounts.java mc/GuiMaverMainMenu.java
java -Djava.awt.headless=true -Dfile.encoding=UTF-8 -cp "$out_dir:$classpath" AccountLoginTest
python3 tools/account-test/check_runtime.py
