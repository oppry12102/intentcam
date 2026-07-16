# 2026-07-16 — CycleManager producer/consumer pipeline + busy derived flow

## Status
Accepted. Implemented in commits `35c71a5` + `8458906` (2026-07-16).
Source: `app/.../CycleManager.kt`.

## Context

By 2026-07-14 the v3.0 inversion shipped live-UI cycles
(`CycleManager.startCycle` → `runCycleLoop` → `CycleJob.bubble`).
Each cycle occupied a slot for its full lifetime and a single
worker handled them one at a time.  Two UX gaps emerged:

1. **Multi-shot latency** — pressing the shutter twice in quick
   succession started cycle B *after* cycle A finished; the
   shutter would visibly stop responding for 2-3 seconds between
   taps.  Users taking a series of photos reported the lag.
2. **Memory unboundedness** — completed cycles stayed in
   `allJobs` indefinitely; long sessions accumulated every
   bubble ever emitted.

Additionally, the `UiState.analyzing` boolean was manually
flipped at 12 sites across `AppViewModel.kt` / `CycleManager.kt`,
all kept in sync via imperative `_state.copy(analyzing = …)`
writes.  Multiple bugs had already shipped from these getting
out of sync (the `analyzing` spinner staying lit after cycle
completion was the most user-visible).

## Decision

Adopt a classic **bounded producer → queue → worker pool →
output** pipeline inside `CycleManager`:

- **Two independent bounds** — `CYCLE_QUEUE_DEPTH = 8` (max
  queued+in-flight) and `CYCLE_CONCURRENCY = 2` (max active
  workers).
- **Backpressure** — when `liveCount >= CYCLE_QUEUE_DEPTH`,
  `startCycle` returns null and the shutter dims
  (`remaining = CYCLE_QUEUE_DEPTH - activeCycleCount`).
- **Worker pool** — a single-threaded `pump()` loop drains the
  pending FIFO into at most 2 `runCycleLoop` coroutines, each
  occupying one `runningWorkers` slot.  The `finally` block
  decrements the count and re-pumps.
- **Terminal FIFO eviction** — a third bound `CYCLES_MAX_TOTAL = 8`
  caps the total cycles map.  When a new cycle would exceed the
  cap, the oldest **terminal** entry (COMPLETE / ERRORED /
  SUPERSEDED) is evicted first.  Live cycles are protected by
  the backpressure invariant (`liveCount <= CYCLE_QUEUE_DEPTH <=
  CYCLES_MAX_TOTAL`).
- **Single-threaded dispatcher** — `startCycle` / `pump` /
  `cancelAll` / worker `finally` blocks all run on
  `viewModelScope` (Main.immediate).  This makes `pendingQueue`
  (ArrayDeque) and `runningWorkers` (Int) safe without locks —
  the typical coroutine-pipeline single-thread invariant.

### `busy: StateFlow<Boolean>` — derived spinner signal

A new derived flow replaces the manually-managed `UiState.analyzing`:

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
val busy: StateFlow<Boolean> = _focusedJobId
    .flatMapLatest { focusedId ->
        if (focusedId == null) flowOf(false)
        else _allJobs
            .map { it[focusedId] }
            .filterNotNull()
            .flatMapLatest { job -> job.status }
            .map { it == JobStatus.PENDING || it == JobStatus.IN_FLIGHT }
            .distinctUntilChanged()
    }
    .stateIn(scope, SharingStarted.Eagerly, false)
```

`flatMapLatest` over both `_focusedJobId` and the focused job's
`status` flow ensures the signal re-emits on every status change
(the map structure doesn't change when a status flips, so a flat
`combine` would miss the transition).

Semantics: `true` iff the focused job is PENDING or IN_FLIGHT.
ERRORED / SUPERSEDED stay false — the per-cycle BubbleCard
surfaces the "识别超时" affordance via its own status flow
instead of a global spinner.

## Alternatives considered

### A. Unbounded queue, single worker (status quo pre-2026-07-16)
The legacy behavior — a tap starts a cycle, the cycle runs to
completion, then the next tap can fire.  **Rejected**: the
2-3s latency between taps is unacceptable UX; the UX is also
inelegant (the shutter stays lit through the gap).

### B. Unbounded queue, N workers (one worker per cycle)
**Rejected**: with no queue depth cap, an aggressive multi-shot
burst could spawn 50+ concurrent LLM calls, blowing past
Anthropic rate limits and producing 529 storms (the historical
eval contamination pattern).  Worker pool must be small.

### C. Bounded queue, single worker (capacity 1)
**Rejected**: queue-depth-1 means a tap during a cycle is
rejected, breaking the user's mental model of "the shutter is
always ready".  Bounded-but-N is better.

### D. Drop `state.analyzing` entirely; let the BubbleCard's
own status drive every spinner
**Rejected**: the TopOverlay's "识别中…" label needs a single
global signal to know whether to show anything.  Per-cycle
status would require aggregating across cycles in the UI,
which is what `busy` already does for the focused job.

## Consequences

**Net win**:
- Rapid multi-shot UX (8 captures can queue; 2 run in parallel)
- Worker pool caps concurrent Anthropic SSE streams → fewer
  529 storms
- Bounds peak device memory (each in-flight cycle holds a
  4096-px fullRes + bitmap decode + OCR)
- Spinner signal is auto-derived; no manual sync, no future
  drift
- ~440 lines removed (legacy `runToolUseCycle`,
  `runRecognitionCycle`, `enterAnalyzing()`, `isBusy()`,
  `UiState.analyzing`, `UiState.bubbles`, `BUBBLE_MAX`,
  `pendingFullRes`, all 12 `_state.copy(analyzing = …)` sites)

**Trade-offs**:
- `flatMapLatest` over `CycleJob.status` adds a per-cycle
  flow subscription — negligible cost (one extra coroutine per
  focused job; cycled in milliseconds)
- `kotlinx.coroutines.ExperimentalCoroutinesApi` opt-in
  required for `flatMapLatest` — accepted (stable for years)
- `CYCLE_CONCURRENCY = 2` means up to 2 fullRes JPEGs (8MB
  each at q95) can be in flight; on a 4GB-RAM device this is
  fine, but watch memory on 2GB-RAM devices (currently no
  min-RAM device in our support matrix)
- Backpressure at `CYCLE_QUEUE_DEPTH = 8` means the user can
  take 8 photos before any cycle completes; if they want
  more, they have to wait.  8 felt right in smoke; 12 or 16
  might be tested if user pushback emerges.

## Related decisions

- `2026-07-15-shutter-counter.md` (planned) — `activeCycleCount`
  derived from `inFlightJobCount()` (PENDING + IN_FLIGHT only)
  replaced `cycles.size` (total map size); the same `PENDING +
  IN_FLIGHT` semantic that `busy` uses.
- `2026-07-16-legacy-pipeline-removed.md` (planned) — the
  parallel close of the `runToolUseCycle` + `UiState.bubbles`
  legacy path that this pipeline makes redundant.

## Migration

- `viewModel.analyzing` getter → `viewModel.busy: StateFlow<Boolean>`
- `ShutterButton(analyzing = state.analyzing, ...)` →
  `ShutterButton(busy = busy, ...)`
- MainActivity collects `viewModel.busy` once in `CameraScreen`
  and passes down

No persistence concerns — `busy` is reconstructed from
`CycleManager` state on every cold start.