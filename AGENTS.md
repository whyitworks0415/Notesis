# AGENTS.md — working on Notesis

Notesis is a handwriting note app for Android tablets (first target: Galaxy Tab
S6 Lite, SM-P61x/P62x). Kotlin, Jetpack Compose for the chrome, a custom
`InkCanvasView` (androidx.ink) for the page. One Gradle module: `app`.

## Build and check

- JDK 17, Android SDK platform 36. `./gradlew assembleRelease` builds the APK;
  `./gradlew testDebugUnitTest` runs the JVM unit tests (`app/src/test`).
- There is no emulator or device in CI. Say plainly in notes and replies what
  was checked by a build or test and what was not tried on a device. Never
  quote performance numbers that were not measured.
- Keep pure logic (layout maths, parsers, policies) in plain functions so it
  can be unit tested; add a test in `app/src/test/java/com/notesis/` for new
  logic of that kind.

## Releasing (every change that ships)

1. Bump `versionCode` (+1) and `versionName` in `app/build.gradle.kts`.
2. Write the release notes in Korean in `docs/releases/<versionName>.md`.
   A manual release starts like this:

   ```
   # Notesis 0.88.0 (187)

   <!-- update: manual -->

   > 수동 업데이트입니다. 앱이 먼저 알리지 않으며, **About · Help → Check for updates**에서 설치할 수 있습니다.
   ```

   Then: what changed and why, how it was checked, what was not done, and a
   short list of what to try on the tablet.
3. CI is `.github/workflows/apk.yml`. It runs on a push to `main` or
   `claude/**`, or by hand (workflow_dispatch, input `update`:
   `automatic`/`manual`). **Every successful run publishes a release** named
   after `versionName`, so do not run it twice for the same version, and bump
   the version before a build meant to ship. An agent working on another
   branch prefix (for example `codex/**`) either adds that prefix to the
   workflow or asks for a manual run.
4. Push to `main` only after the build on the work branch passed.

## Secrets and signing

- The release key and its password are **never** in this repository. The
  workflow signs with GitHub secrets (`NOTESIS_KEYSTORE_BASE64` and its
  `_2`..`_5` parts, the passwords, `RELEASES_TOKEN`). See `docs/signing.md`
  and `docs/private-repo-releases.md`. Do not print, log or copy them.
- Do not add API keys, tokens or personal data to code, notes or tests.

## Text and translation

- Every string the app shows is written in English in the code and shown
  through the `Text` wrapper in `Korean.kt` (L10n). Korean comes from
  `app/src/main/assets/i18n/ko.tsv`: one line per string,
  `English<TAB>Korean`. `{0}`, `{1}` stand for numbers or names filled in
  at run time (`Page {0}` → `{0}쪽`). Add a line for every new string; an
  English key must appear only once.
- Release notes and replies to the owner are in Korean; code, comments and
  commit messages are in English.

## Code conventions

- Comments say why, in plain sentences; keep the existing tone.
- The pen path is latency-critical: no allocation, Compose state writes or
  file work per pen event. Background work gives way while the pen is down
  (`InteractionGovernor`, `penWorking`).
- Chrome follows the chosen skin (`LocalSkin`, `SkinSurface`,
  `skinMaterial`, liquid glass via `LocalLiquidGlassBackdrop`). Glass cannot
  sample a backdrop it is itself inside of (`NoBackdrop`); floating chrome is
  placed outside the recorded content.
- Large files: `MainActivity.kt` (screens) and `InkCanvasView.kt` (canvas).
  Search before adding; most features already have a home.
- Commit messages: a short summary line, then what and why. No model names
  or identifiers in code, commits or notes.

## Layout of the code (main pieces)

| Area | Files |
|---|---|
| Library (home) | `MainActivity.kt` (`HomeScreen`), `HomeLibrary.kt` |
| Note screen, toolbar | `MainActivity.kt` (`NoteScreen`, `Toolbar`), `SpotiGlassToolbar.kt`, `ToolbarCustomize.kt` |
| Canvas and ink | `InkCanvasView.kt`, `CompatWetInkView.kt` (S6 Lite wet ink), `PencilBrushes.kt` |
| Storage | `Document.kt` (`NoteStore`, pages, save/load, whiteboard `board.bin`) |
| Text boxes, tables | `TextBox.kt`, `MarkdownSpans.kt`, `TableBox.kt`, `InlineText.kt` |
| Handwriting recognition | `InkIndexer.kt` (ML Kit), `OcrResultDialog.kt` |
| Markdown notes, vault | `MarkdownNoteScreen.kt`, `MarkdownText.kt`, `MarkdownVault*.kt` |
| Settings | `SettingsSidebar.kt`, `ToolSettingsDialog.kt`, `PenPreset.kt` (`PenStore`) |
| Diagnostics | `Diagnostics.kt`, `LatencyStats.kt` |
