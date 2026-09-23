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

### Stage 2 — Kill the dump tax — **DONE 2026-09-23**

Final resolution 2026-09-23: the standalone `:dumpserver` was rebuilt to the same design (one
persistent `UiAutomation`, uiautomator-compatible XML over a loopback socket, `adb forward`) and
the harness rewired to the socket fast path, keeping the legacy one-shot dump as the automatic
fallback and the `PHOTOINV_DUMP_SERVER=0` kill-switch. The one blocker the clean re-evaluation
had isolated — the camera shutter race — is fixed in the harness: `camera_capture` re-taps the
shutter (up to 3×, 6 s dialog wait per attempt) when the first tap is swallowed. Two consecutive
full runs then landed **468 passed / 0 failed / 0 warnings** in **673 s** and **671 s**
(~11.2 min vs the ~35 min legacy baseline), with the dump server active throughout. The four
soft snackbar checks that used to warn now pass as well (fast dumps catch the transient
snackbar), so the counts read 468/0/0 instead of 464/0/4.

The clean re-evaluation below (kept for the record) proved the server itself correct and named
the only remaining defect, the camera-timing race:

Clean re-evaluation 2026-09-23 (animations at their defaults — the harness never touches the
scales): the standalone `:dumpserver` was rebuilt exactly as specified and run twice
back-to-back. Both runs produced **319 passed / 82 failed / 1 warning** (845 s and 847 s) with
a byte-identical failure set. The persistent connection was **not** the bare-DecorView wedge:
it served correct, complete trees (the contamination note below stands), and the late-AI
blank-guard scenario it once poisoned passed cleanly in both runs (AI 59/59). The regression was
a **camera-flow timing race that the fast dump exposes**:

- `camera_capture` taps the in-app shutter the moment its `content-desc="Take photo"` node
  appears. With ~150 ms socket dumps that tap lands ~2 s before the CameraX pipeline is
  capture-ready, so the capture is swallowed and the app stays on the camera screen. The
  shutter node already reports `enabled="true"`/`clickable="true"`, so the harness cannot tell
  "shown" from "ready". The legacy `uiautomator dump` path (~1.9 s/call) accidentally masks the
  race by delaying the tap long enough for the camera to bind.
- A/B cold-app proof: forcing the legacy path (`PHOTOINV_DUMP_SERVER=0`) makes the same
  cold-app `camera_capture` pass 4/4 (32 s), while the socket path fails deterministically;
  retapping the identical shutter node after 3 s succeeds. `UiAutomation.waitForIdle` before
  each read was tried and does not help (the camera screen is a11y-idle while the hardware
  initialises).
- The fix therefore retries the shutter instead of re-adding ~2 s of latency to every dump. That
  retry (already in the harness) is the only camera-path change between the failing re-eval runs
  and the two green runs above.

One serializer-fidelity point surfaced by the green runs: an empty `EditText` exposes `text=""`,
so the server omits only truly **null** attributes, not empty strings. Omitting empty strings
made two checks (`note field prefilled empty`, `disabled dialog leaves the description empty`)
read `None` instead of `""`; emitting the empty attribute restores parity with `uiautomator dump`.

History (kept for the record): the Stage 2 code was implemented and reverted once before on
evidence contaminated by the Stage 1 animations-off wedge. The notes below predate the clean
re-evaluation above; where they blame the persistent connection for the bare-tree wedge, that
is superseded — the bare tree was the animations-off Stage 1 artifact, and the only Stage 2
defect that survived the clean re-evaluation was the camera-timing race described above, which
the harness shutter retry now fixes.

NOTE: the earlier abandonment was based on runs contaminated by the animations-off wedge (Stage 1, above): the legacy runs of that same window failed identically, so the wedge was never a dump-server problem. With animations restored (464/0/4 re-confirmed), the persistent dump server was UNPROVEN, not disproven — hence this clean re-evaluation, which now settles it.
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
| 2026-09-23 | Stage 2 **re-eval**, standalone `:dumpserver`, animations ON (run A) | 845 s (~14.1 min) | 319 passed, 82 failed, 1 warning — camera shutter race |
| 2026-09-23 | Stage 2 **re-eval**, standalone `:dumpserver`, animations ON (run B) | 847 s (~14.1 min) | 319 passed, 82 failed, 1 warning — identical failure set; superseded below |
| 2026-09-23 | Stage 2 **reverted** → legacy dump, animations ON (confirmation A) | 2093 s (~34.9 min) | 464 passed, 0 failed, 4 warnings |
| 2026-09-23 | Stage 2 **reverted** → legacy dump, animations ON (confirmation B) | 2077 s (~34.6 min) | 464 passed, 0 failed, 4 warnings |
| 2026-09-23 | Stage 2 **DONE** — rebuilt `:dumpserver` + harness shutter retry (run A) | 673 s (~11.2 min) | **468 passed, 0 failed, 0 warnings** |
| 2026-09-23 | Stage 2 **DONE** — rebuilt `:dumpserver` + harness shutter retry (run B) | 671 s (~11.2 min) | **468 passed, 0 failed, 0 warnings** |

The rows through the 2026-09-23 standalone-process one are the contaminated window (Stage 1
animations off): their failing set is the late-AI-reply blank-guard cascade, and the bare tree
they saved was the animations-off artifact, not a dump-server defect. The two Stage 2 **re-eval**
rows are the clean measurement (animations at defaults): the failure set is instead the
camera-capture race described under Stage 2 — 82 failures, byte-identical across both runs, with
the late-AI scenario green. The two **confirmation** rows re-run the restored legacy path with
animations at defaults and reproduce the green baseline (464/0/4). The two bottom **DONE** rows
are the final Stage 2 state: server rebuilt, shutter retry in the harness, both runs green at
468/0/0 with the dump server active, and ~3× faster than the legacy baseline.

### Stability loop — outcome 2026-09-23

Historical (superseded by the Stage 2 **DONE** outcome above): this section records the
abandoned/reverted window. The "bare DecorView wedge" it blames on the persistent connection was
the Stage 1 animations-off artifact, and the residual failures it lists were the camera-shutter
race, not a dump-server defect.

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
