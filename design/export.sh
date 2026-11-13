#!/usr/bin/env bash
# Re-export the OpenMaver design shots with headless Firefox.
set -euo pipefail
cd "$(dirname "$0")"

FF="${FIREFOX:-firefox}"

shot() {
  local page="$1" out="$2" size="$3" query="$4"
  local prof abs
  prof="$(mktemp -d)"
  abs="$PWD/${out}"
  echo "→ ${out} (${size})"
  "$FF" --headless --no-remote --profile "$prof" \
        --screenshot "$abs" --window-size="$size" \
        "file://$PWD/${page}${query}" >/dev/null 2>&1
  rm -rf "$prof"
}

shot clickgui.html  clickgui-combat-8k.png  7680,4800 "?scale=4.8"
shot clickgui.html  clickgui-render-4k.png  3840,2400 "?cat=render&open=esp&scale=2.4"
shot targethud.html targethud-4k.png        3840,2400 "?scale=2.4"

echo "done."
