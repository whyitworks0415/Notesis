# Interaction optimization — 0.33.5

## Causes addressed

- Reference gestures previously used view-local coordinates while moving and resizing that same view. This creates feedback between the view transform and the next motion sample. Raw screen-space centroid and spread now drive panel manipulation. Page pinch applies scale at the previous centroid and translation once per MOVE.
- Reference callbacks created with the native view could retain the initial resize state. Updated callbacks now observe the current panel stretch and close state. Resizing preserves manual page zoom instead of fitting again at release.
- A downward dismiss could become a resize from small grip changes. Dismiss intent now stays latched after crossing touch slop. The close button handles initial pointer down, including when another finger remains on the canvas; pen closure waits for asynchronous stroke handoff.
- Two independently loaded copies of the same note could overwrite edits when saved. Both canvases now share the live document. Geometry replacement and distant-page release are suspended while multiple canvases own it.
- Popup image/template loaders decoded files on draw requests. A bounded 16 MiB bitmap cache now reuses results. Cancelled PDF loads release their source, and keyed note panels separate loading and saving state during switches.
- WebView user-agent work occurred during updates, background lifecycle was incomplete, and renderer loss was unhandled. User agents are remembered, stopped activities pause the WebView, and renderer loss recreates it. IME insets shrink the web content without resizing the note surface.
- Material used unnecessary liquid-backdrop allocation and an offscreen AndroidView alpha. These are removed; its shared toolbar layout uses plain surfaces and controls without glass shaders or spring morphing.

## Automated verification

- 124 unit tests pass, including graphite gap/distribution, sticky dismiss intent, shared-document ownership/redraw notifications, and existing toolbar size/menu contracts.
- Debug APK builds successfully.
- Lint completes with zero errors, 59 existing warnings and one hint.
- Release APK build is checked separately; the published tag and APK are linked in the release delivery.

## Device checks still required

No Android device was connected during development. Do not interpret build/test success as measured latency or temperature improvement.

1. Compare pencil strokes at normal and enlarged zoom; check fine grain, opacity, pressure, and export.
2. Open AI chat/search docked and as a popup, focus the bottom input, rotate with keyboard open, and confirm the input remains reachable.
3. Move and resize a reference with three fingers, lift fingers in different orders, and verify no jump or residual page movement. Check pinch centroid stability.
4. Keep one finger on the popup while tapping close with another. Repeat during a pen stroke and reopen to confirm its final segment persists.
5. Edit the same note in both canvases, close/reopen, change reference notes quickly during PDF loading, and verify page, image, undo and saved ink behavior.
6. Check all four toolbar sizes in Material, Glassmorphism and LiquidGlass 1–4, including narrow browser split view. Compare idle CPU and handwriting debug timings at identical page/zoom/refresh settings.
