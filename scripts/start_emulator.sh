#!/usr/bin/env bash
# Launches the Photo Inventory test emulator with tuned flags.
# Snapshot-enabled: the first run cold-boots (~1 min); later runs restore in ~10 s
# when the previous session ended gracefully (`adb emu kill` saves the snapshot).
# Usage: scripts/start_emulator.sh [AVD] [extra emulator flags...]
#   HEADLESS=1 scripts/start_emulator.sh   # run fully headless: never touches the
#                                          # real X display, even if the session
#                                          # has DISPLAY set (uses offscreen Qt)
set -euo pipefail
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
AVD="${1:-PhotoInventory-35}"
shift || true
FLAGS=("-cores" "4" "-memory" "3072" "-no-boot-anim" "-gpu" "swiftshader_indirect")
if [ "${HEADLESS:-0}" = "1" ]; then
  FLAGS+=("-no-window" "-no-audio")
  # -no-window alone is NOT enough: Qt still picks up $DISPLAY from the login
  # session and can open on the user's real X. Strip both to force offscreen.
  unset DISPLAY
  export QT_QPA_PLATFORM=offscreen
fi
exec sg kvm -c "$ANDROID_HOME/emulator/emulator -avd $AVD ${FLAGS[*]} $*"
