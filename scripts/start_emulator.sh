#!/usr/bin/env bash
# Launches the Photo Inventory test emulator with tuned flags.
# Snapshot-enabled: the first run cold-boots (~1 min); later runs restore in ~10 s
# when the previous session ended gracefully (`adb emu kill` saves the snapshot).
# Usage: scripts/start_emulator.sh [AVD] [extra emulator flags...]
#   HEADLESS=1 scripts/start_emulator.sh   # run without a window (CI-style)
set -euo pipefail
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
AVD="${1:-PhotoInventory-35}"
shift || true
FLAGS=("-cores" "4" "-memory" "3072" "-no-boot-anim" "-gpu" "swiftshader_indirect")
[ "${HEADLESS:-0}" = "1" ] && FLAGS+=("-no-window" "-no-audio")
exec sg kvm -c "$ANDROID_HOME/emulator/emulator -avd $AVD ${FLAGS[*]} $*"
