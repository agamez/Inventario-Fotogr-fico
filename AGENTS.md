# AGENTS.md — Photo Inventory

Guidance for AI agents (and humans) working in this repository. Read this before writing code.

## The golden rule

**Every user-facing feature must be implemented on BOTH platforms: the Android app (`app/`) and the Linux desktop app (`desktop/`).**
A feature that lands on only one platform is incomplete. When picking up work, check the other platform before calling a feature done.

## What this project is

A photo-based inventory of physical containers (buildings → storage units → boxes → boxes → …) where
**the filesystem is the single source of truth**:

- Containers = real folders; photos = real JPEG files named after their descriptions.
- Metadata lives in standard EXIF tags: `ImageDescription` = description, `UserComment` = note, `DateTimeOriginal` = date taken.
- Cover photo of a container = `_cover.jpg` inside its folder (excluded from photo listings).
- SQLite (`cache.db`) is a disposable, rebuildable index — never synced, never migrated, wiped and rebuilt by Rescan.
- Designed for Syncthing sync and PC-side edits: external file/folder changes are adopted on rescan; `*.sync-conflict-*` files are surfaced with a badge + banner.
- Two storage backends behind one interface (`StorageBackend`): SAF (zero permissions, default on Android) and direct File I/O (All-Files-Access, optional).

## Module map

```
shared/    Kotlin Multiplatform core (androidTarget + jvm): StorageBackend, FileBackend,
           Filenames, PhotoMeta, ExifEditor interface, ScanState, ai/VisionClient
app/       Android app (Compose M3, Hilt, CameraX, SAF backend, Room cache, Settings)
desktop/   Linux desktop app (Compose Multiplatform JVM, FileBackend, in-memory index,
           WatchService live rescan, fat-JAR packaging — NO bundled JRE)
scripts/   make_icon.py (parametric icon generator); smoke_test.py (e2e harness)
```

## Hard conventions (violations get bounced)

1. **Disk is truth.** All writes atomic (temp + rename). NEVER normalize/re-encode images — camera captures and gallery picks are stored byte-identical.
2. **Both platforms, always** (see the golden rule).
3. **No TODOs, no placeholders, no stubs.** Implement fully or don't start.
4. **Every user-visible string comes from resources** (`strings.xml` on Android, `DesktopStrings.kt` on desktop).
5. **Description is required**; AI suggestions pre-fill but never overwrite user-typed text (blank-guard).
6. **EXIF tag mapping is contractual**: description→ImageDescription (0x010E), note→UserComment (0x9286), date→DateTimeOriginal (0x9003), "yyyy:MM:dd HH:mm:ss". Both platforms must read each other's files.
7. **Unique sibling names, case-insensitive**, collisions get " (2)" suffixes.
8. **No git write commands by agents** — the orchestrator commits.

## Verification gates (run before claiming done)

- JVM unit tests: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :shared:jvmTest :desktop:jvmTest` (system JDK is 25-EA — it cannot run Gradle; always pin JDK 17).
- Android build: `./gradlew :app:assembleDebug`.
- Desktop: `./gradlew :desktop:packageFatJar` + headless selftest `./gradlew :desktop:run --args="--headless-selftest <folder>"` (must print `SELFTEST OK`, exit 0).
- Full Android e2e harness: `python3 scripts/smoke_test.py` on booted emulator `emulator-5554` (launch it via `scripts/start_emulator.sh`; expected: 0 failed, ≤4 documented soft warnings). Run for any Android-behavior change. **Policy: one full run per change; two consecutive runs only for harness-infrastructure changes or milestone gates.** The harness zeroes animations and prints its total duration (see `docs/test-optimization-plan.md`).
- Desktop EXIF writes are verified against host Pillow (0x010E/0x9286/0x9003 round-trips) — pixel data must stay byte-identical (lossless).
- Emulator notes: grant storage via `adb shell appops set com.inventory MANAGE_EXTERNAL_STORAGE allow`; `adb reverse` for host-loopback mock servers; uiautomator wedges → `pkill -f uiautomator` + retry (harness self-heals).

### Running tests with visible output

Tests take time (JVM is fast; the e2e harness ~15 min). Run them in the background and **watch output via `tail`**, not `>/dev/null`:

```bash
# JVM tests (quick — minutes):
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :shared:jvmTest :desktop:jvmTest

# Desktop headless selftest:
./gradlew :desktop:run --args="--headless-selftest /tmp/selftest"

# Android e2e harness (~15 min):
PYTHONUNBUFFERED=1 python3 scripts/smoke_test.py > /tmp/smoke_test_output.log 2>&1 &
# then monitor in another terminal:
sleep 30 && tail -50 /tmp/smoke_test_output.log
# repeat every few minutes until you see "===== N passed, 0 failed ====="
# or watch continuously:
tail -f /tmp/smoke_test_output.log
# when done, check the full report:
tail -80 /tmp/smoke_test_output.log
```

Key: the Python harness **buffers stdout when redirected to a file** (not a TTY). Without `PYTHONUNBUFFERED=1` you'll see nothing for 10+ minutes and think it's hung.

## Architecture rules

- `:shared` must stay pure JVM/Kotlin (no Android imports, no Hilt). Platform DI wires implementations (Hilt on Android, manual composition on desktop).
- SAF/Android-specific code lives in `app/`; desktop-specific code in `desktop/`.
- Optional network features (e.g. AI auto-label) are OFF by default, configured per platform, and the client lives in `:shared`.
- One commit per coherent feature/module; imperative messages.
