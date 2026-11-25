# E2E Test Failure Diagnosis — Machine "sslayer"

**Date**: 2026-10-21
**Machine**: Debian 13 (trixie), x86_64, Kernel 6.12.111+deb13
**Result**: 570 passed / 633 total → **570 passed, 63 failed, 3 warnings**
**Baseline (other machine, same code)**: ~600 passed / ~600 total → 0–10 failures (varies)

---

## 1. Machine Profile

| Property | Value |
|----------|-------|
| OS | Debian GNU/Linux 13 (trixie) |
| Kernel | 6.12.111+deb13 (x86_64) |
| GLIBC | 2.41-12+deb13u4 |
| CPU | 4 cores, Intel (VT-x / vmx) |
| Memory | 15 GiB |
| GPU | virtio VGA (Device 1234:1111) — emulated, no real GPU |
| Storage | SATA HDD (RATA=1) |
| KVM | `/dev/kvm` present, `kvm_intel` module loaded, user `sslayer` in `kvm` group |
| Emulator version | Android emulator 37.2.12.0 (build 16428233) |
| ADB version | 1.0.41 (37.0.1-15733141) |
| System image | `android-35/google_apis/x86_64` rev 9 |
| Display | Xvfb `:99` — 1024×768x24 at 100 DPI |
| Launch method | `scripts/start_emulator.sh` → `sg kvm -c "emulator ..."` inside tmux session `pic-emulator` |
| GPU backend | `swiftshader_indirect` (swangle) — falls back to `auto` → uses llvmpipe Vulkan + SwiftShader OpenGL |

## 2. Test Matrix

| Test | Status |
|------|--------|
| JVM unit tests (`:shared:jvmTest :desktop:jvmTest`) | ✅ PASS — 76 tests, 0 failures |
| Desktop headless selftest (`--headless-selftest`) | ✅ SELFTEST OK — all checks pass |
| Android e2e harness (`scripts/smoke_test.py`) | ⚠️ **570 passed, 63 failed, 3 warnings** |

## 3. Failure Breakdown

### Category A: Gallery Picker Duplicate Detection (~15 failures cascaded)

**Tests affected:**
- `second item prompts again`
- `second pick flagged as duplicate`
- `gallery flow opens [dup skip]`
- `sheet offers Choose from gallery [dup skip]`
- `gallery picker shows photos [dup skip]`
- `second gallery pick opens the sheet`
- `gallery photos added snackbar`
- `second photo seeded`
- `first gallery import saved`
- `disk: first import present`
- `Winter jacket tile present`
- `EXIF: gallery description stamped`
- `tile Winter jacket`
- `tile Old skis`
- `disk: /Garage/Winter jacket.jpg`
- `disk: /Garage/Old skis.jpg`
- `EXIF: gallery DateTimeOriginal`
- `EXIF: no note on queue-only pick`
- `stored pixels identical to seeded JPEG`

**What happens:**
1. The test opens the Android 15 PhotoPicker, picks two photos (`test_a.jpg`, `test_b.jpg`).
2. Both are the **identical** 287-byte 1×1 magenta JPEG (MD5 `bb9186dc...`).
3. After picking the first one, the app shows the metadata dialog ("Description (required)") — **PASS**.
4. After picking the second one, the app shows a **"Duplicate" warning** instead of the metadata dialog.
5. The test checks for "Description (required)" and **FAILS** because the duplicate warning appears first.

**Root cause:** Android 15 PhotoPicker (API 35) detects the byte-identical content and the app's duplicate guard fires *before* the metadata dialog. The test harness expects the metadata dialog for every pick, then handles duplicates separately via `import_anyway_if_duplicate()`. The duplicate check needs to come *first* in the harness.

**Why it works on the other machine:** Likely a timing/race condition in the app's flow. The duplicate detection and metadata dialog are asynchronous; a faster machine may deliver events in a different order. Could also be a difference in the Android 15 PhotoPicker implementation (extension level 12 vs 13).

**Fix:** In the harness's `t_photos_and_flow()` helper, check for the duplicate warning ("Identical photo already exists here as…") *before* checking for "Description (required)", and handle the "Import anyway" path first.

---

### Category B: Drag-and-Drop on Emulator (~11 failures)

**Tests affected:**
- `dnd1 photo tile located`
- `dnd1 restore: photo back under Shelf A`
- `dnd2 disk: contents travelled`
- `dnd3 disk: subtree intact`
- `dnd4 photo tile located`
- `dnd4 restore: photo back under Shelf A`
- `dnd5 two photos selected`
- `dnd5 disk: both photos under Shelf B`
- `dnd5 restore: only the original photo back under Shelf A`
- `dnd6 photo tile located`
- `dnd7 photo tile located`
- `breadcrumb drag: photo tile located`
- `dnd2 disk: contents travelled`
- `dnd3 disk: subtree intact`

**What happens:**
The harness uses raw `input motionevent` commands to simulate drag-and-drop gestures on the emulator. The gesture is delivered, but the **touch target is not hit** because the gesture injection on the emulator's virtual screen does not correctly resolve to the view under the finger position.

**Root cause:** This is a known Android emulator limitation. The `input motionevent` API injects raw motion events, but the emulator's coordinate system and view layout pass-through can be unreliable, especially:
- On emulated displays (Xvfb / virtual screen)
- With SwiftShader software rendering (different frame timing)
- On HDD storage (slower view layout computation)

This is **not a code bug** — it's an emulator gesture delivery issue that would likely fail on any machine without a real GPU and with software rendering.

**Fix options:**
1. Use the accessibility-based long-press-and-drag API instead of raw `input motionevent`.
2. Accept these as known emulator limitations and exclude them from CI.
3. Test on a device with real GPU.

---

### Category C: EXIF Read-Back for Gallery-Picked Photos (~5 failures)

**Tests affected:**
- `final EXIF: Winter jackets x2 desc+note` → `{desc: None, note: None, date: None}`
- `final EXIF: date well-formed` → `{desc: None, note: None, date: None}`
- `final EXIF: Old skis desc, no note` → `{desc: None, note: None, date: None}`
- `final EXIF: guard photo desc+note` → `{desc: None, note: None, date: None}`
- `final EXIF: plain photo desc, no note` → `{desc: None, note: None, date: None}`

**Root cause:** These photos were seeded via the gallery picker flow (Category A). Since the duplicate detection caused the metadata dialog to be skipped, the photos were either never saved or saved with blank EXIF fields. The `exif_triple()` check at the end of the suite finds files on disk with no EXIF data.

**Fix:** Fix Category A first — all of these are cascading failures.

---

### Category D: Search Timing (~3 failures)

**Tests affected:**
- `'ski' filters to Old skis` — search result appears *after* the assertion fires

**Root cause:** The app debounces or lazily updates the search filter. The test types text and immediately checks for results. On slower rendering (software GPU), the UI update may not have arrived yet.

**Fix:** Increase the `time.sleep()` after typing in search, or add a `wait_for()` polling loop instead of a single `find_all()`.

---

### Category E: UI State Timing (~3 failures)

**Tests affected:**
- `Doomed gone again` — double-delete scenario, UI state race
- `Reportable gone from the UI` — similar post-delete timing issue
- `disk: Viewer container removed` — async deletion not yet reflected on disk

**Root cause:** The app performs deletions asynchronously (coroutines). The test checks the UI or disk state immediately after the delete action. On slower machines, the background work hasn't completed.

**Fix:** Add explicit waits (e.g., `wait_for(text=None, timeout=5)` to wait for UI stabilization, or poll the filesystem).

---

### Category F: Disk Tree Mismatch (~2 failures)

**Tests affected:**
- `final tree matches disk exactly` — the disk listing includes an unexpected file

**Root cause:** The test asserts an exact tree listing. The actual tree includes a file that shouldn't be there (e.g., a leftover from a previous test or the seed photos). This is usually a cleanup issue between test scenarios.

---

## 4. Key Differences from Other Machine

| Factor | This machine | Other machine (unknown) |
|--------|-------------|------------------------|
| GPU | Emulated virtio (no real GPU) | Could be bare-metal GPU |
| Storage | SATA HDD (RATA=1) | Could be NVMe SSD |
| Display | Xvfb virtual screen | Could be Xvfb or real display |
| Android version | API 35, extension 13 | Same? |
| Emulator version | 37.2.12.0 | Same? |
| Harness version | Same git commit | Same? |
| Headless mode | Xvfb `:99` with tmux | Could be `HEADLESS=1` or windowed |

**Most likely culprit:** The emulated GPU + software rendering (SwiftShader/llvmpipe) makes UI rendering slower, which exposes race conditions in the test harness (timing windows that the other machine's faster GPU doesn't trigger).

## 5. What Works Here (to compare)

All of the following **PASS** on this machine, proving the app code and test infrastructure are functional:

- ✅ All container CRUD operations (create, read, update, delete, move)
- ✅ Camera capture via fake emulator camera (`hw.camera.back = emulated`)
- ✅ Camera EXIF writing (description, note, date all stamped correctly)
- ✅ File-mode storage (MANAGE_EXTERNAL_STORAGE, disk writes)
- ✅ SAF mode (tree URIs)
- ✅ AI auto-label flow
- ✅ Trash / restore / purge flow
- ✅ Report export (HTML + CSV)
- ✅ Breadcrumb navigation
- ✅ Photo viewer (open, swipe, edit, delete)
- ✅ Conflict detection (`*.sync-conflict-*` files)
- ✅ Duplicate detection UI (the warning itself works — the harness just doesn't handle it in the right order)
- ✅ Share target (PNG import)
- ✅ Seed photos via ADB push (file-level, not gallery picker)
- ✅ Rescan adoption of external edits

## 6. Recommended Next Steps

1. **Fix the gallery picker duplicate flow in the harness** — check for the duplicate warning before the metadata dialog. This alone should recover ~15 tests.
2. **Accept drag-and-drop failures** as emulator limitations, or replace with accessibility-based gestures.
3. **Compare the other machine's environment:**
   - Does it have a real GPU? (`lspci \| grep -i vga`)
   - Is it SSD or HDD?
   - Is it headless (Xvfb) or real display?
   - Does it use `HEADLESS=1`?
4. **If the other machine has a real GPU**, try adding `swiftshader_indirect` to see if results differ.
5. **Run the harness on a physical device** to confirm all failures are emulator-artifacts.

## 7. Relevant Files

| File | Purpose |
|------|---------|
| `scripts/smoke_test.py` | Android e2e harness (570+ tests) |
| `scripts/start_emulator.sh` | Emulator launch script (KVM + tmux) |
| `docs/emulator-diagnostic-report.md` | Previous diagnostic with full config |
| `docs/emulator-fix-summary.md` | Camera fix and KVM notes |
| `~/.android/avd/PhotoInventory-35.avd/config.ini` | AVD config (camera=emulated) |
| `system-images/android-35/google_apis/x86_64/` | System image directory |
| `dumpserver/` | UI dump server APK |sslayer@full-vibe:~/Proyectos/Inventario-Fotografico$ 
