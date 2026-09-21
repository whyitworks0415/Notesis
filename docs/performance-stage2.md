# Stage 2 — stylus input hot path

Updated: 2026-09-21

## Scope and invariants

This stage changes dispatch and temporary storage around stylus input. It does
not change prediction output, stroke stabilization, brush input, committed
stroke data, undo grouping, or the saved file format.

## Historical input verification

The app passes the original `MotionEvent` directly to
`InProgressStrokesView.addToStroke`. Inspection of the pinned AndroidX Ink
1.0.0 AAR shows that its internal `StrokeInputPool` loops from history index
zero through `MotionEvent.getHistorySize()` and reads historical event time and
axis values. Wet ink was therefore already receiving every batched S Pen
sample; flattening or replaying those events in Notesis would duplicate work.

The app-owned lasso path previously retained only the current coordinate. It
now reads historical coordinates as well, keeps them in a primitive float
buffer, and spatially decimates points closer than two screen pixels.

## Applied changes

- Predictor recording now follows only a gesture that owns a wet stroke.
  Eraser, lasso, capture, shape, image, and read gestures no longer run the
  predictor filter when its output cannot be consumed. Freehand prediction is
  recorded in the same DOWN/MOVE/UP order and keeps the same 9 ms clipping.
- Prediction-lead statistics no longer enter a synchronized method for every
  MOVE while diagnostics are hidden.
- Repeated eraser checks reuse point, hit-list, and identity-set storage.
  Empty eraser moves no longer allocate a `FloatArray` and two filtered lists.
- Eraser intersection work is spatially coalesced while the prior square tip
  already covers the move. The next segment spans the skipped distance, and
  ACTION_UP is always processed so the end of the gesture is not lost.
- Lasso points use `FloatArray` storage instead of boxed `Float` entries. Its
  dash effect is cached per scale instead of allocated on every frame.
- Capture, shape, lasso, image, eraser, and hover overlays request at most the
  next animation-frame redraw instead of immediate redraws at S Pen sample rate.
- Image drag reuses its page-coordinate scratch array.
- Pen-tool classification no longer constructs temporary `listOf` collections
  on DOWN/commit, and the common one-stroke commit no longer creates a singleton
  result list.

Compose state was already updated only at gesture DOWN/UP (`onDrawingChanged`),
not for every MOVE, so no additional state throttling was needed.

## Structural before/after evidence

The table counts Notesis-owned work per relevant event. AndroidX/native
internals are outside these counts.

| Path | Before | After |
| --- | --- | --- |
| Eraser/lasso/shape predictor record | 1 per event | 0 |
| Hidden-HUD prediction statistic lock | 1 per predicted MOVE | 0 |
| Lasso coordinate boxing | 2 `Float` objects per retained sample | 0 |
| Empty eraser MOVE scratch/filter allocations | 1 float array + 2 filter lists | 0 |
| Lasso dash allocation while drawing | 1 per draw | 1 per scale |
| Overlay immediate invalidation | up to input rate | coalesced to display frames |
| Freehand singleton commit list | 1 per committed stroke | 0 |

No physical Android device was attached to the build host, so device p50/p95/
p99 claims are intentionally deferred. Compare release 0.31.2 and 0.31.3 on
the same document with the Stage 1 HUD, especially allocation/GC during eraser
and lasso runs and wet-ink/pen-up distributions during fast writing.

## Regression coverage

- Primitive lasso distance decimation and polygon containment
- Forced final eraser sample and covered-move coalescing
- Existing lasso, ink line, prediction horizon, stabilization, undo/redo,
  tessellation, document, and serialization tests
