# Test Optimization Plan

Status tracker for speeding up the verification gates. Measured 2026-09-03 on emulator-5554 (API 35, swiftshader).

## Current state (measured)

| Gate | Duration | Notes |
|---|---|---|
| `:shared:jvmTest` + `:desktop:jvmTest` | ~1–2 min | fine, no action |
| Desktop selftest | < 1 min | fine, no action |
| **Android harness** (`scripts/smoke_test.py`, 464 checks) | **~35 min/run** | the pain point; ×2 runs per milestone = ~70 min |

### Where the 35 minutes go (measured/estimated)

| Driver | Evidence | Share |
|---|---|---|
| `uiautomator dump` per poll — **~1.9 s each** (measured 3×), hundreds per run (464 checks + wait-polling) | dump timing loop | **~15–20 min** |
| Animations at scale 1.0 on software rendering → 1.2–2 s settle sleeps everywhere | `settings get global window_animation_scale` = 1.0 | ~4–6 min |
| AI scenario mock delays (12 s × 2–3, deterministic guard testing) | DESCRIBE_DELAY_S | ~0.5–1 min |
| Snackbar wait windows (cosmetic text, 5–15 s polls) | soft checks | ~1–2 min |
| Cold boot (`-no-snapshot`), reinstalls, `pm clear` + setup | launch flags | ~1.5 min |
| Rescans, restores, scenario churn | — | remainder |

## Stages

### Stage 1 — Quick wins (zero test-logic changes) — **DONE 2026-09-03**
- [x] Harness sets all three animation scales to 0 at startup (dialogs/sheets appear instantly; poll-based waits return sooner).
- [x] `scripts/start_emulator.sh`: tuned launch (`-cores 4 -memory 3072 -no-boot-anim`), snapshot-enabled boot (first run cold ~1 min, later runs restore ~10 s; `adb emu kill` saves).
- [x] Harness prints total duration at the end.
- [x] Policy (AGENTS.md): **one full harness run per change**; two consecutive runs only for harness-infrastructure changes or milestone gates.
- Expected: ~35 → ~22–25 min.

### Stage 2 — Kill the dump tax — **DONE 2026-09-03**
- [x] Persistent dump server: instrumentation (`app/src/androidTest/.../DumpServerInstrumentation.kt`) keeps UiAutomation alive and serves uiautomator-compatible XML over a local socket (adb forward). Dump cost ~2 s → ~50–100 ms.
- [x] Harness `dump()` uses the socket client with automatic fallback to the legacy one-shot path (resilience).
- [x] Same XML semantics as `uiautomator dump` (active window only) so `find_all` parsing is unchanged.
- Expected: ~22 → ~12–15 min.

### Stage 3 — Rebalance the pyramid (deferred until the harness hurts again)
- [ ] Compose instrumented test suite (`androidTest`, in-process, no adb round-trips): could carry ~70–80 % of the 464 checks in ~5 min (browse, dialogs, pickers, search, settings; gestures via test APIs).
- [ ] Slim the e2e harness to system-integration-only checks (~15): SAF picker, permission gates, camera, photopicker, PC-edit adoption, disk-edit adoption → ~8–10 min.
- [ ] Optional: Robolectric for Android-only logic (VisionPrefs, scanner glue) on the JVM.
- Expected full gate: ~10–12 min.

### Stage 4 — Sharding (only if still needed)
- [ ] 2–3 emulators, scenarios sharded per device. Linear win, real complexity (ports, state isolation). Skip after Stage 3 unless required.

## Results log

| Date | Change | Harness duration | Result |
|---|---|---|---|
| 2026-09-03 (baseline) | — | ~35 min | 409→464 passed, 0 failed |
| 2026-09-03 | Stage 1 + Stage 2 | _see latest entries below_ | _see latest entries below_ |
