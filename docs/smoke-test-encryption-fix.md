# Fix: "Encryption is requested but failed to create encrypt partition"

If `scripts/smoke_test.py` fails with the error above, it comes from the Android
emulator, not from the test script itself. Run the diagnostic section first,
then apply whichever fix applies.

---

## Diagnostic

Run this on the affected machine and share the output:

```bash
echo "=== /dev/kvm ===" && ls -la /dev/kvm 2>&1
echo "=== kvm group ===" && grep kvm /etc/group 2>&1
echo "=== encrypt in AVD ===" && grep -i encrypt ~/.android/avd/PhotoInventory-35.avd/config.ini 2>&1
echo "=== free disk ===" && df -h ~ | tail -1
echo "=== emulator version ===" && ~/Android/Sdk/emulator/emulator -version 2>&1 | head -3
```

---

## Fix 1: AVD `config.ini` has `Encryption = yes`

The emulator tries to set up full-disk encryption but can't create the required
partition.

Check and fix:

```bash
grep -i encrypt ~/.android/avd/PhotoInventory-35.avd/config.ini
```

If it shows `Encryption = yes`, remove that line or set it to `no`:

```bash
sed -i 's/^Encryption = yes/Encryption = no/' ~/.android/avd/PhotoInventory-35.avd/config.ini
# or:   sed -i '/^Encryption/d' ~/.android/avd/PhotoInventory-35.avd/config.ini
```

Then restart the emulator.

---

## Fix 2: KVM is missing or the user isn't in the `kvm` group

The emulator script (`start_emulator.sh`) uses `sg kvm` to access `/dev/kvm`.

Check:

```bash
ls -la /dev/kvm        # must exist
id                     # check if user is in the kvm group
```

If `/dev/kvm` doesn't exist:

```bash
sudo apt install qemu-kvm libvirt-clients libvirt-daemon-system
```

If the user isn't in the `kvm` group:

```bash
sudo usermod -aG kvm $USER
# then log out and back in
```

---

## Fix 3: Not enough disk space

The emulator needs extra space for the encryption partition. Ensure at least
**8–10 GB free**:

```bash
df -h ~ | tail -1
```

---

## Fix 4: Re-create the AVD

If the above doesn't help, the AVD system image may have encryption baked in.
Recreate it:

```bash
# Check the exact system-image the good machine is using:
avdmanager list avd

# On the affected machine, delete and recreate:
avdmanager delete avd -n PhotoInventory-35
avdmanager create avd -n PhotoInventory-35 -k "<system-image-hash>"
# (use the same hash reported by the good machine, e.g. system-images;android-35;google_apis;x86_64)
```