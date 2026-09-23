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

### Stage 2 — Kill the dump tax — **RE-OPENED 2026-09-23 (was ABANDONED on contaminated evidence)**
NOTE: the abandonment below was based on runs contaminated by the animations-off wedge (Stage 1, above): the legacy runs of that same window failed identically, so the wedge was never a dump-server problem. With animations restored (464/0/4 re-confirmed), the persistent dump server is UNPROVEN, not disproven — re-evaluation scheduled.
- [x] Persistent dump server: instrumentation keeps one `UiAutomation` alive and serves
  uiautomator-compatible XML over a loopback socket (adb forward). Dump cost ~2 s → ~50–100 ms,
  so full runs landed at **~10–12 min** (vs the ~35 min baseline).
- [x] Harness `dump()` used the socket client with automatic fallback to the legacy one-shot path.
- [x] Same XML semantics as `uiautomator dump` (active window only) so `find_all` parsing was
  unchanged.
- **Abandoned:** every variant left **8–35 nondeterministic failures**, always the
  late-AI-reply blank-guard scenario plus its downstream checks. The persistent `UiAutomation`
  connection goes stale when Compose recreates a window (the metadata sheet after typing +
  hiding the IME, and the queued-save sheet advancing to its next item) and for tens of seconds
  returns that window's bare `DecorView` chain (`FrameLayout/ViewGroup/View` — no `text`, no
  `content-desc`) even though the screen is visibly correct. The stability loop (serialize twice
  150 ms apart, cap 1.6 s) kept the speed but not the correctness. In-process recovery was tried
  and rejected — `waitForIdle`, a flag-toggle `getUiAutomation()` reconnect, `clearCache()`, and
  a reflected `UiAutomation.destroy()` all failed to repopulate reliably, and "recycle the
  server" ballooned a run to 1443 s.

**Standalone-process experiment 2026-09-23:** the server was moved into a self-instrumenting
`:dumpserver` APK (`com.inventory.dumpserver`, its own process, no androidx) to test the
hypothesis that sharing the app's process tied the accessibility queries to the app's busy main
thread — the architecture used by Appium's uiautomator2 server. The server survived `pm clear`
and app reinstalls and served correct trees in normal use, but **the wedge persisted
identically**: at the AI-guard failure the server returned the same content-less
`FrameLayout/FrameLayout/FrameLayout/ViewGroup/View/View/View` chain (1479-byte dump saved by
the harness), and the full run repeated the in-process result (**386 passed / 35 failed,
730 s**). Conclusion: the stale entity is the persistent `UiAutomation` *connection*, not the
host process.

**Revert + legacy confirmation 2026-09-23:** Stage 2 was removed in full and the legacy one-shot
`uiautomator dump` path restored. Two full legacy runs on a freshly rebooted emulator gave
**459 passed / 5 failed / 4 warnings in 2077 s** and **382 passed / 35 failed / 4 warnings in
2001 s**. The legacy path wedges **exactly the same way** — its saved failure dumps are the same
bare `FrameLayout/FrameLayout/FrameLayout/ViewGroup/View/View/View` chain (legacy-format,
2342 bytes) — so the 2026-09-03 "464 passed / 0 failed" baseline was **not reproduced** in
either reverted run. The wedge is therefore a pre-existing, run-to-run-variable
platform/Compose accessibility issue, not a Stage 2 regression; reverting only trades the
~12 min Stage 2 runtime back for ~35 min with the same failure distribution. See the Results
log.

One harness fix was kept beyond the Stage 1 code: `check()` now sanitizes failure names that
contain `/` (e.g. `disk: Attic shelf/x.jpg`). Without it every failing run crashed with a
`FileNotFoundError` before it could finish (a latent Stage 1 bug that the 0-failure baseline
never hit); it changes no assertion, only the failure-dump filename.

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
| 2026-09-23 | Stage 1 + Stage 2 (run A) | 616 s (~10.3 min) | 386 passed, 35 failed |
| 2026-09-23 | Stage 1 + Stage 2 (run B) | 611 s (~10.2 min) | 460 passed, 8 failed |
| 2026-09-23 | Stage 2 + stability loop (run A) | 725 s (~12.1 min) | 390 passed, 31 failed |
| 2026-09-23 | Stage 2 + stability loop (run B) | 746 s (~12.4 min) | 456 passed, 12 failed |
| 2026-09-23 | Stage 2 + standalone `:dumpserver` process | 730 s (~12.2 min) | 386 passed, 35 failed — wedge unchanged, Stage 2 reverted |
| 2026-09-23 | Stage 2 reverted → legacy dump (run A, clean boot) | 2077 s (~34.6 min) | 459 passed, 5 failed, 4 warnings |
| 2026-09-23 | Stage 2 reverted → legacy dump (run B) | 2001 s (~33.4 min) | 382 passed, 35 failed, 4 warnings |

Across the Stage 2 rows the run-to-run spread (8 vs 35 failures) is emulator frame/timing jitter:
the failing set is always the late-AI-reply blank-guard (`blank-guard: typed description survives
the late reply`, `blank-guard: empty note still pre-filled`, then the guarded-photo disk/EXIF
checks) plus the downstream final-audit rows for that missing photo; the lucky runs additionally
passed the queue, covers, drag-and-drop and PC-edit scenarios cleanly. The reverted legacy runs
below show the same intermittent bare-tree wedge, so it is not a dump-server artifact.

### Stability loop — outcome 2026-09-23

The DUMP handler sampled the tree twice 150 ms apart and only returned once two serializations
were byte-identical (cap 1.6 s, then the last read regardless); stable screens cost
~150–250 ms per dump. Two consecutive full runs: **390/31 in 725 s** and **456/12 in 746 s** —
the loop keeps the Stage 2 speed but does not remove the residual failures.

A focused repro showed the residual is not a *transient* read: when Compose recreates a window
(the metadata sheet after typing + hiding the IME, and the queued-save sheet advancing to its
next item) the accessibility tree for that window collapses to a bare `DecorView` chain
(`FrameLayout/ViewGroup/View` — no `text`, no `content-desc`; the screen is visibly correct in a
screenshot) for tens of seconds. The stability loop sees two identical content-less reads and
correctly returns them as "stable", but the tree is still wrong. In-process recovery was tried
and rejected — `waitForIdle`, a flag-toggle `getUiAutomation()` reconnect, `clearCache()`, and a
reflected `UiAutomation.destroy()` all failed to repopulate reliably, and "recycle the server"
(close the socket so the harness restarts it) ballooned a run to 1443 s without improving the
failure count, so it was dropped. The reverted legacy runs (a fresh automation per call)
reproduce the identical bare tree, so the wedge is not an artifact of connection age.

**Verdict:** a pre-existing platform/Compose accessibility wedge that hits *any*
`uiautomator`-style dump on window recreation — the persistent connection and the fresh
per-call automation both return the same bare `DecorView` chain. It is intermittent: the legacy
suite produced 5 and 35 failures back-to-back and the Stage 2 suite 8–35, always the
queue/late-AI-reply guard cascade. It is not caused by the dump mechanism and reverting Stage 2
does not remove it; a real fix needs a different readback approach and is out of scope for this
plan. The failures are recorded rather than papered over.

Note: the AI guard and the gallery queue failures are the same wedge; when the queue's second
prompt is missed the whole "Winter jackets x2 / Old skis" cascade and the drag/PC-edit/final
checks go with it, which is why run-to-run failure counts varied by up to ~27.
