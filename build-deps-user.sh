#!/usr/bin/env bash
#
# build-deps-user.sh — Install user-level build dependencies (no sudo required).
# Project-agnostic Android/Kotlin build prerequisites (no project-specific settings).
#
# Tech stack: Kotlin 1.9+, Jetpack Compose, Room 2.x, Hilt, CameraX 1.3+,
#             Navigation Compose 2.7+, Coil 2.x, Material 3, Min SDK 24, Target SDK 35
#
# Target: Ubuntu/Debian
#
# Prerequisites: run build-deps-root.sh first (openjdk-17-jdk, curl, wget, unzip, qemu-kvm)
#
# Usage:   bash build-deps-user.sh

set -euo pipefail

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

# ─── 1. JDK 17 (download to user's home) ────────────────────────────────────
# If a system JDK 17 is available (from build-deps-root.sh), use that instead.
echo "==> Setting up JDK 17..."
JDK_DIR="$HOME/.jdks"

# Check for system JDK first
SYSTEM_JDK=$(readlink -f "$(which javac 2>/dev/null)" 2>/dev/null | sed 's|/bin/javac||' 2>/dev/null || true)
if [[ -n "$SYSTEM_JDK" && -d "$SYSTEM_JDK" ]]; then
  export JAVA_HOME="$SYSTEM_JDK"
  export PATH="$JAVA_HOME/bin:$PATH"
  echo "  Using system JDK: $JAVA_HOME"
else
  JDK_ARCHIVE="$JDK_DIR/jdk-17.tar.gz"
  JDK_EXTRACTED="$JDK_DIR/jdk-17"

  if [[ ! -d "$JDK_EXTRACTED" ]]; then
    JDK_URL="https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.13%2B11/openjdk-17.0.13+11_linux-x64_bin.tar.gz"
    mkdir -p "$JDK_DIR"
    echo "  Downloading Temurin JDK 17..."
    curl -sS -L -o "$JDK_ARCHIVE" "$JDK_URL"
    echo "  Extracting..."
    tar -xzf "$JDK_ARCHIVE" -C "$JDK_DIR"
    rm -f "$JDK_ARCHIVE"

    if [[ -d "$JDK_DIR/jdk-17.0.13+11" ]]; then
      mv "$JDK_DIR/jdk-17.0.13+11" "$JDK_DIR/jdk-17"
    fi
  else
    echo "  JDK 17 already installed at $JDK_EXTRACTED, skipping."
  fi

  export JAVA_HOME="$JDK_EXTRACTED"
  export PATH="$JAVA_HOME/bin:$PATH"
fi

# ─── 2. Android SDK Command-Line Tools ───────────────────────────────────────
echo "==> Installing Android SDK Command-Line Tools..."
mkdir -p "$ANDROID_HOME/cmdline-tools"

if [[ ! -f "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]]; then
  SDKTOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
  SDKTOOLS_ARCHIVE="/tmp/commandlinetools.zip"

  curl -sS -L -o "$SDKTOOLS_ARCHIVE" "$SDKTOOLS_URL"
  mkdir -p /tmp/cmdline-tmp
  unzip -qo "$SDKTOOLS_ARCHIVE" -d /tmp/cmdline-tmp
  mv /tmp/cmdline-tmp/cmdline-tools/* "$ANDROID_HOME/cmdline-tools/latest" 2>/dev/null \
    || mv /tmp/cmdline-tmp/cmdline-tools "$ANDROID_HOME/cmdline-tools/latest"
  rm -rf /tmp/cmdline-tmp "$SDKTOOLS_ARCHIVE"
  echo "  SDK tools installed."
else
  echo "  SDK tools already installed, skipping."
fi

# ─── 3. Android SDK Components ──────────────────────────────────────────────
echo "==> Accepting Android SDK licenses..."
yes | sdkmanager --licenses > /dev/null 2>&1 || true

echo "==> Installing Android SDK platform and build-tools..."
sdkmanager --install \
  "platforms;android-35" \
  "platforms;android-24" \
  "build-tools;35.0.0" \
  "platform-tools"

# ─── 3b. Emulator system image + emulator ───────────────────────────────────
echo "==> Installing emulator and system image (android-35, x86_64)..."
sdkmanager --install \
  "emulator" \
  "system-images;android-35;google_apis;x86_64" 2>/dev/null || \
sdkmanager --install \
  "emulator" \
  "system-images;android-35;default;x86_64" 2>/dev/null || \
  echo "  WARNING: failed to install system image. Install manually:"
echo "    sdkmanager 'system-images;android-35;default;x86_64'"

# ─── 3c. Create default emulator AVD ────────────────────────────────────────
echo "==> Creating default emulator AVD..."
EMULATOR_AVD="PhotoInventory-35"

# Check if AVD already exists
if avdmanager list avd 2>/dev/null | grep -q "$EMULATOR_AVD"; then
  echo "  AVD '$EMULATOR_AVD' already exists, skipping."
else
  # Try to find an installed system image (google_apis or default)
  SYSTEM_IMG=$(avdmanager list avd --compact 2>/dev/null | grep "android-35" | head -1 || true)
  if [[ -n "$SYSTEM_IMG" ]]; then
    avdmanager create avd \
      -n "$EMULATOR_AVD" \
      -k "$SYSTEM_IMG" \
      --device "pixel_6" \
      --force
    echo "  Created AVD '$EMULATOR_AVD' (Pixel 6, $SYSTEM_IMG)"
  else
    echo "  WARNING: no android-35 system image found. Install one first, then:"
    echo "    avdmanager create avd -n $EMULATOR_AVD -k <system-image-id> --device pixel_6"
  fi
fi

# ─── 4. Configure shell profile ────────────────────────────────────────────
SHELL_RC="$HOME/.bashrc"
if [[ -f "$HOME/.zshrc" ]]; then
  SHELL_RC="$HOME/.zshrc"
fi

ENV_BLOCK="# === Photo Inventory App Build Environment ===
export ANDROID_HOME=$ANDROID_HOME
export JAVA_HOME=$JAVA_HOME
export PATH=\$JAVA_HOME/bin:\$ANDROID_HOME/cmdline-tools/latest/bin:\$ANDROID_HOME/platform-tools:\$PATH"

if ! grep -Fq "# === Photo Inventory App Build Environment ===" "$SHELL_RC" 2>/dev/null; then
  echo "" >> "$SHELL_RC"
  echo "$ENV_BLOCK" >> "$SHELL_RC"
  echo ""
  echo "==> Shell environment configured in $SHELL_RC"
  echo "    Run 'source $SHELL_RC' or restart your terminal."
fi

# ─── 5. Verify installations ───────────────────────────────────────────────
echo ""
echo "=== Verification ==="
java --version 2>&1 || true
echo "ANDROID_HOME=$ANDROID_HOME"
sdkmanager --list 2>/dev/null | head -20 || true
echo ""
emulator -version 2>&1 | head -3 || true
avdmanager list avd 2>/dev/null || true
echo ""
echo "==> Done. Dependencies are installed."
echo "    Build the app with: ./gradlew assembleDebug"