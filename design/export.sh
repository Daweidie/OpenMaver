#!/usr/bin/env bash
# Re-export the ClickGUI design shots with headless Firefox.
set -euo pipefail
cd "$(dirname "$0")"

FF="${FIREFOX:-firefox}"

shot() {
  local out="$1" size="$2" query="$3"
  local prof abs
  prof="$(mktemp -d)"
  abs="$PWD/${out}"
  echo "→ ${out} (${size})"
  "$FF" --headless --no-remote --profile "$prof" \
        --screenshot "$abs" --window-size="$size" \
        "file://$PWD/clickgui.html${query}" >/dev/null 2>&1
  rm -rf "$prof"
}

shot clickgui-combat-8k.png  7680,4800 "?scale=4.8"
shot clickgui-render-4k.png  3840,2400 "?cat=render&open=esp&scale=2.4"

echo "done."
