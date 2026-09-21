# Notesis performance baseline

Updated: 2026-09-21

## Scope

The in-app latency panel is the baseline collector. Toggling the toolbar speed
icon on starts a fresh session; toggling it off makes the normal input and draw
paths stop recording. Samples are held in fixed-size primitive ring buffers.
No note data, stroke data, or file format is changed.

The panel reports rolling p50/p95/p99 values for:

- stylus event detection to wet-ink draw/presentation (`wet ink`)
- `ACTION_UP` dispatch to committed-stroke bookkeeping and edit notification
- visible stroke load/re-tessellation (`mesh/refine`)
- each native `PdfRenderer.Page.render` call, with the latest pixel count
- all page draws and draws with at least 500 visible strokes
- display frame period and prediction lead
- process allocation rate and GC count sampled every 500 ms

Perfetto/Android system tracing also receives these diagnostic-only sections:

- `Notesis stylus DOWN`, `MOVE`, `UP`, and `other`
- `Notesis mesh/refine`
- `Notesis PDF render`

## Baseline protocol

Use a release APK on the target S Pen tablet, close other foreground apps, and
repeat each scenario three times. Turn the speed panel off and on before every
run so the rolling windows contain only that scenario.

1. Write continuously for 30 seconds at normal and fast speed.
2. Add 50 short strokes, recording the `pen-up 확정` distribution.
3. On a 500-, 1,000-, and 2,000-stroke page, pan and zoom for 30 seconds.
4. Zoom from fit width to the maximum and wait for refinement after each stop.
5. Scroll a long PDF by one page at a time, then scrub rapidly across 100 pages.
6. Erase and lasso continuously for 30 seconds while watching allocation/GC.

Capture the HUD after each run and record a Perfetto trace when a p95/p99 spike
is reproducible. Allocation/GC is process-wide by design; correlate a spike
with the named trace sections or an Android Studio allocation recording before
assigning it to a hot path.

## Current baseline availability

No Android device was attached during this implementation, so device-specific
latency, frame, PDF, memory, and GC numbers cannot be stated honestly yet. JVM
tests verify percentile calculation, fixed-window classification, reset
semantics, and that disabled diagnostics retain no hot-path samples. The HUD
and trace sections above make the missing physical baseline directly
collectable on the target tablet.

## Bottleneck candidates from the code audit

Priority reflects expected impact and whether the work runs on an interactive
or UI-thread boundary. These are candidates to validate with the baseline, not
claimed device timings.

1. **P0 — pen-up reconstruction on the commit callback.** A freehand stroke is
   rebuilt by `correctStrokeStart` and then again by `smoothFreehandStroke`
   before `afterEdit`. Both transformations allocate a new input batch/stroke
   on the commit path, making short, rapid strokes especially sensitive.
2. **P0 — dense-page draw work.** A cache miss draws every visible stroke; a
   page containing highlighter strokes performs two passes. Spatial culling is
   present, but the draw still scans/classifies the visible list each frame.
3. **P0 — refine allocation and UI-thread reconciliation.** Refinement creates
   replacement `Stroke` lists and an identity map in the worker, then scans page
   strokes and remaps undo history on the UI thread. Deep zoom and large visible
   regions multiply both mesh work and temporary allocations.
4. **P1 — per-MOVE prediction objects.** `MotionEventPredictor.predict()` returns
   a recyclable event, and clipping a long prediction creates a second event.
   Pointer arrays are already reused, but prediction-enabled high-rate input is
   still an allocation/GC candidate.
5. **P1 — eraser candidate collections.** Every eraser sample filters stroke
   and mask candidates into new lists before identity removal. Dense pages and
   120–240 Hz S Pen input make this the most likely non-drawing allocation hot
   path.
6. **P1 — native PDF rendering and bitmap pressure.** `PdfRenderer.render()` is
   serialized and non-interruptible. Whole-page and tile bitmaps are bounded,
   but a render can still take tens of milliseconds and allocate multi-megabyte
   bitmaps; fast navigation can expose tail latency even when stale queued work
   is discarded.
7. **P2 — diagnostic presentation itself.** The HUD sorts copied ring buffers
   and reads ART counters every 500 ms. This cost exists only while diagnostics
   are explicitly enabled and is intentionally excluded from normal release
   behavior.

## Interpretation rules

- Optimize p95/p99 regressions before p50 when the median is already below one
  display frame.
- Do not infer motion-to-photon latency from draw-call completion when the
  platform does not provide an estimated presentation timestamp; the wet-ink
  metric labels the boundary actually reported by AndroidX Ink.
- Compare PDF times only at similar pixel counts.
- Treat GC correlation as evidence to collect an allocation trace, not proof of
  the allocating call site.
