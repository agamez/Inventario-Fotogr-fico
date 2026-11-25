#!/usr/bin/env bash
#
# build-deps-root.sh — Install system-level build dependencies (requires root).
# Project-agnostic Android/Kotlin build prerequisites (no project-specific settings).
#
# Tech stack: Kotlin 1.9+, Jetpack Compose, Room 2.x, Hilt, CameraX 1.3+,
#             Navigation Compose 2.7+, Coil 2.x, Material 3, Min SDK 24, Target SDK 35
#
# Target: Ubuntu/Debian
#
# Usage:   sudo bash build-deps-root.sh

set -euo pipefail
# Target user for group membership: the user who invoked sudo (no hardcoded name).
U="${SUDO_USER:-$(id -un)}"
echo "==> Updating package index..."
apt-get update -y

echo "==> Installing JDK 17..."
apt-get install -y openjdk-17-jdk

echo "==> Installing build utilities..."
apt-get install -y curl wget unzip

echo "==> Installing KVM for hardware-accelerated emulation..."
apt-get install -y qemu-kvm libvirt-daemon-system libvirt-clients bridge-utils

echo "==> Adding current user to kvm and libvirt groups..."
usermod -aG kvm $U
usermod -aG libvirt $U
echo ""
echo "  IMPORTANT: log out and back in for group changes to take effect."

echo "==> Done. System-level dependencies installed."