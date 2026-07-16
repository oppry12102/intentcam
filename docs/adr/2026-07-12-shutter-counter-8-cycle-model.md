# 2026-07-15 — Shutter counter + max-8 cycle session model

## Status
Accepted. Shipped 2026-07-15 (multiple commits).

## Context

Pre-2026-07-15 the shutter had no visual feedback for "how many
more photos can I take?" — the only signal was the analyzer
spinner on the TopOverlay.  Users taking a series of photos
had no warning when the cycle queue was filling up.

Worse, the cycle queue could grow unboundedly — a user taking
50 photos in a session would accumulate 50 `CycleJob` entries
in `allJobs`, blowing memory and making the live UI scrollback
useless.

## Decision

Three coordinated changes:

1. **`UiState.CYCLE_QUEUE_DEPTH = 8`** — max queued+in-flight
   cycles.  Shutter dims when `remaining = CYCLE_QUEUE_DEPTH -
   activeCycleCount` hits 0.
2. **`UiState.CYCLES_MAX_TOTAL = 8`** — terminal FIFO cap on
   the cycles map.  Oldest COMPLETE / ERRORED / SUPERSEDED
   entry evicted first when a new cycle would exceed the cap.
   Live cycles are protected by the backpressure invariant
   (`liveCount <= CYCLE_QUEUE_DEPTH <= CYCLES_MAX_TOTAL`).
3. **`UiState.activeCycleCount: Int`** — single source of truth
   for the shutter counter.  Synced via
   `AppViewModel.syncCycleCounters()` after every cycle
   transition.  Computed from `cycleManager.inFlightJobCount()`
   (PENDING + IN_FLIGHT only).

## Consequences

- Hard memory cap on long sessions — no OOM from accumulating
  cycle history.
- Shutter counter UI: "还可以拍 N 张" (can take N more).
- "重新扫描" UI affordance removed — with the auto-release
  counter, the user never needs a manual reset unless they
  explicitly want a clean slate.

## Migration

- `CYCLE_MAX_CONCURRENT = 2` (now `CYCLE_CONCURRENCY`) is the
  worker pool size; see producer-consumer pipeline ADR.
- 2026-07-16 P0 cleanup retired `UiState.analyzing` in favor
  of `CycleManager.busy` derived flow.