# 60Hz wet-ink latency design

## Input-to-display path

```text
S Pen MotionEvent + historical samples
→ requestUnbufferedDispatch
→ optional streaming stabilization
→ InkPredictionPolicy
→ MotionEventPredictor
→ InProgressStrokesView front-buffer wet ink
→ transient soft prediction head
→ committed androidx.ink Stroke
```

`InProgressStrokesView` owns wet ink and selects the supported AndroidX Ink
front-buffer implementation. It is eagerly initialized when `InkCanvasView` is
created so the first pen contact does not pay renderer setup cost. The app does
not enable the deprecated high-latency helper.

The predictor receives only real/stabilized events. Its output is passed as the
predicted batch to `InProgressStrokesView`, which discards the previous
predicted batch whenever the next real event arrives. Predicted samples are
therefore never returned as the finished `Stroke` and never reach storage.

## Prediction policy

- Prediction waits for three stable movement vectors (four positions).
- Motion below 0.12 px/ms disables prediction.
- Slow/medium/fast motion is capped at 4/6/configured milliseconds.
- A meaningful corner resets the stable-vector gate and suppresses prediction
  until the new direction is stable.
- Automatic mode caps prediction at 9ms below 90Hz and 6ms at 90Hz or above.
- Fixed 4/6/9ms settings allow repeatable comparisons on the same display.

Only fast solid pen/pencil strokes receive a separate soft head. It is capped
at 12dp and 24ms, uses two low-alpha antialiased strokes, and uses no blur. The
view is cleared on every real replacement, pen-up, cancel, and detach. It is a
visual overlay and cannot affect serialized stroke geometry.

## Measurement

The existing latency HUD uses AndroidX Ink `LatencyData` to report OS event
detection through estimated presentation time when available, falling back to
the completion of wet-ink draw calls. It also reports frame period and actual
prediction lead percentiles.

For comparable device measurements:

1. Use the same page, brush, zoom, device, and power mode.
2. Collect at least 240 fast-writing MOVE samples with prediction disabled.
3. Repeat with fixed 4ms, 6ms, and 9ms modes.
4. Record wet-ink p50/p95/p99, frame p50/p95/p99, and prediction lead.
5. Repeat at forced 60Hz and 120Hz, then check small writing and 90-degree
   corners visually for overshoot, double ink, or a lingering head.

No Android device was connected to the release workstation, so this release
contains the measurement instrumentation and comparison modes but no new
hardware latency numbers. Device measurements remain a release follow-up.
