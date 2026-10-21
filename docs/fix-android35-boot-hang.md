# Fix: Android 35 Emulator Hangs at Boot

## Problem

The emulator QEMU process starts but never completes boot — `sys.boot_completed`
never returns `"1"`. The hang occurs 3–15 minutes after launch, with no error or
crash output.

## Root Cause

The `android-35/default/x86_64` system image has different encryption handling and
boot scripts than the `google_apis` variant. The `default` image appears to require
an encryption key partition formatted in a specific way that the current 256-byte
`encryptionkey.img` doesn't satisfy, causing the init process to hang in the
`security` phase.

## Fix

### Step 1 — Install the google_apis system image

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager \
  "system-images;android-35;google_apis;x86_64"
```

If that path doesn't exist, try the legacy location:

```bash
$ANDROID_HOME/tools/bin/sdkmanager \
  "system-images;android-35;google_apis;x86_64"
```

This downloads ~2–3 GB. Expect 2–5 minutes on a typical connection.

### Step 2 — Delete the old AVD and recreate it

```bash
avdmanager delete avd -n PhotoInventory-35

avdmanager create avd \
  -n PhotoInventory-35 \
  -k "system-images;android-35;google_apis;x86_64" \
  -d pixel_6
```

This generates a new `config.ini`, `hardware-qemu.ini`, and handles the
encryption key correctly.

### Step 3 — Apply custom device settings

The freshly created AVD won't have the same flags as the old one. Re-apply the
settings that were in the original `config.ini` / `hardware-qemu.ini`:

```bash
AVD_DIR="$HOME/.android/avd/PhotoInventory-35.avd"
INI="$AVD_DIR/config.ini"

# Disable sensors, camera, audio, keyboard, SD card, battery
for flag in hw.battery.no hw.camera.back.none hw.camera.front.none \
            hw.gps.no hw.accelerometer.no hw.audioInput.no \
            hw.audioOutput.no hw.sdCard.no hw.keyboard.no hw.dPad.no; do
  key=$(echo "$flag" | sed 's/.no$//' | sed 's/\./ /' | awk '{print $1}')
  echo "$key = no" >> "$INI"
  echo "$key = none" >> "$INI"  # for camera
done

# GPU & display (pixel_6 defaults are 1080x2400, 420 dpi — already correct)
echo "hw.gpu.enabled = yes" >> "$INI"
echo "hw.gpu.mode = swiftshader_indirect" >> "$INI"

# Disk
echo "disk.dataPartition.size = 6442450944" >> "$INI"
echo "userdata.useQcow2 = false" >> "$INI"
echo "hw.diskChecks = no" >> "$INI"
echo "hw.vulkan = no" >> "$INI"

# Disable Play Store (test environment)
echo "PlayStore.enabled = no" >> "$INI"

# Force cold boot for CI reliability
echo "fastboot.forceColdBoot = true" >> "$INI"
```

Or, more simply, replace the entire `config.ini` with the one below (this is the
exact file from the good machine that worked):

```
PlayStore.enabled = no
abi.type = x86_64
avd.id = PhotoInventory-35
avd.name = PhotoInventory-35
hw.cpu.arch = x86_64
hw.cpu.ncore = 4
hw.ramSize = 4096
hw.gpu.enabled = yes
hw.gpu.mode = swiftshader_indirect
hw.lcd.width = 1080
hw.lcd.height = 2400
hw.lcd.density = 420
hw.device.name = pixel_6
image.sysdir.1 = system-images/android-35/google_apis/x86_64/
hw.battery = no
hw.sdCard = no
disk.dataPartition.size = 6442450944
hw.useext4 = true
hw.vulkan = no
hw.diskChecks = no
hw.rng_seed = 12345
hw.camera.back = none
hw.camera.front = none
hw.gps = no
hw.accelerometer = no
hw.audioInput = no
hw.audioOutput = no
hw.keyboard = no
hw.dPad = no
userdata.useQcow2 = false
fastboot.forceColdBoot = true
```

> **Important:** Change `image.sysdir.1` from `android-35/default/x86_64/` to
> `android-35/google_apis/x86_64/` in `config.ini` AND `hardware-qemu.ini`.

### Step 4 — Fix `hardware-qemu.ini` kernel and partition paths

```bash
AVD_DIR="$HOME/.android/avd/PhotoInventory-35.avd"
HWINI="$AVD_DIR/hardware-qemu.ini"

# Update kernel and image paths
sed -i 's|system-images/android-35/default/x86_64|system-images/android-35/google_apis/x86_64|g' "$HWINI"

# Also fix the ramdisk/initrd path
sed -i 's|system-images/android-35/default/x86_64|system-images/android-35/google_apis/x86_64|g' "$AVD_DIR/initrd" 2>/dev/null || true
```

### Step 5 — Launch and verify

```bash
export DISPLAY=:99   # or omit for windowed mode
$ANDROID_HOME/emulator/emulator \
  -avd PhotoInventory-35 \
  -cores 4 -memory 3072 \
  -no-boot-anim \
  -gpu swiftshader_indirect
```

Wait ~30–60 seconds for the first boot (the AVD builds its userdata). Then check:

```bash
adb -s emulator-5554 wait-for-device
adb -s emulator-5554 shell "getprop sys.boot_completed"
# Expected output: 1
```

Once `sys.boot_completed` returns `1`, the emulator is ready.

---

## If that still hangs: use an older system image

Android 34 (`android-34`) does not enforce full-disk encryption by default and
is known to boot reliably in headless/container environments.

```bash
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager \
  "system-images;android-34;google_apis;x86_64"

avdmanager delete avd -n PhotoInventory-35

avdmanager create avd \
  -n PhotoInventory-34 \
  -k "system-images;android-34;google_apis;x86_64" \
  -d pixel_6

# Then update start_emulator.sh to use PhotoInventory-34 instead of PhotoInventory-35
```

Update `scripts/start_emulator.sh` line 9:

```bash
AVD="${1:-PhotoInventory-34}"
```

And update `smoke_test.py` line 53 (FS_ROOT is unchanged — no update needed there).

## Files to Commit

After applying the fix, the following files change:

| File | Change |
|------|--------|
| `scripts/start_emulator.sh` | `PhotoInventory-35` → `PhotoInventory-34` (if using Android 34) |
| `~/.android/avd/PhotoInventory-35.ini` | Recreated with new system image target |
| `~/.android/avd/PhotoInventory-35.avd/config.ini` | Recreated with `google_apis` path |
| `~/.android/avd/PhotoInventory-35.avd/hardware-qemu.ini` | Updated partition paths |
| `system-images/android-35/google_apis/x86_64/` | New SDK directory (~2–3 GB) |

The AVD directory and system images are **not** tracked by git (they live under
`~/.android/` and `$ANDROID_HOME/system-images/`). Document the changes in the
PR description and/or the `.gitignore` is already correct.