# Spotiglass source provenance

SpotiFLAC-Mobile commit `37ecd39a9ef8ac4b0d6f0cfb114e928f71e7ff09`:
https://github.com/spotiflacapp/SpotiFLAC-Mobile

`mornye_chrome.dart` supplies MornyeGlassSurface, MornyeGlassRim and MornyeTabBar.
Its MIT license is included as `SpotiFLAC.LICENSE` (copyright 2026 zarzet).

The actual optics and animation engine is **liquid_glass_easy 4.3.2**:
https://pub.dev/packages/liquid_glass_easy/versions/4.3.2
https://github.com/AhmeedGamil/liquid_glass_easy

Archive: https://pub.dev/api/archives/liquid_glass_easy-4.3.2.tar.gz
SHA-256: `0900af82971da23f1f2a28ea038c13d28fab6bd1e57c2492c005a8dae3ca8f90`
MIT license: `liquid_glass_easy.LICENSE` (copyright 2025 Ahmed Gamil).

The files in this directory are unmodified upstream sources. Notesis ports:

- GLSL common SDF/refraction, optical/classic border and entry shader to AGSL.
  Regenerate `../liquid_glass.agsl` with `node scripts/port-spotiglass-shader.mjs`.
  The adapter selects the upstream analytic Skia gradient, changes shader types,
  texture coordinates, entry point and whole-layer composition for RenderEffect.
  It leaves the optical and continuous-corner equations intact.
- Spring integration, independent X/Y lift, material lift, drag follow,
  acceleration sampling, travel direction and landing handover to Kotlin in
  `SpotiGlassMotion.kt`. Default tuning follows LiquidGlassTabPillStyle.
- Continuous clip path and shader uniform packing to `SpotiGlassRenderer.kt`.
- Mornye capsule tint, clarity/blur buckets, dark luma filter and rim gradients
  to `spotiGlassSurface`; tab geometry and colored icon shell to SpotiGlassBar.
- Slider and switch rest/lifted thumb dimensions (37x24 and 58x38.333),
  relative drag input and distortion settings to `SpotiGlassControls.kt`.
  Notesis keeps the lens raised until travel settles, including externally
  changed values, as requested. Controls reuse the nav ticker spring port.
  `slider.dart`, `slider_layout.dart`, `switch.dart` and `switch_layout.dart`
  preserve the unmodified upstream implementations.

Compose supplies layout, accessibility and input; Android supplies backdrop
capture, Gaussian blur and shadows. Shadow rasterization and typefaces depend
on the platform. The application retains its own tools, labels and colors.
