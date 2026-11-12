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
3. CI is `.github/workflows/apk.yml`. It runs by hand only
   (workflow_dispatch, input `update`:
   `automatic`/`manual`/`check`). **Every successful `automatic` or `manual`
   run publishes a release** named after `versionName`, so do not run one
   twice for the same version, and bump the version before a build meant to
   ship. `check` builds, runs the unit tests (failing on one) and the
   screenshot tests, and publishes nothing: use it to try a change. An agent working on another
   branch prefix (for example `codex/**`) either adds that prefix to the
   workflow or asks for a manual run.
4. Push to `main` only after the build on the work branch passed.
   When to release (the owner's rule): bug fixes and changes to existing
   features are released as soon as they pass, without asking; new features
   are released only when the owner says so.
5. Who gets a release: a `manual` release (or a GitHub pre-release) reaches
   only copies with the developer code entered in About · Help (beta
   testers); everyone else is offered `automatic` releases only. The code
   itself is never written in the repository - only its SHA-256, in
   `AppUpdate.kt`.
6. The account's Actions artifact storage has filled before. Artifacts are
   kept 3 days and a failed upload does not stop the release; the release
   carries the APK.

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
- Chrome is Material only (`SkinSurface`, `skinMaterial`, `LocalSkinSettings`).
  The glass looks and the pouring popup animation are kept in `archive/`,
  outside the build, and come back only when the owner asks (`Todo.md`, step 7).
  New things that appear grow out of what made them (`PopupGrowth`,
  `revealOutOf`).
- Icons are static Material Symbols by default, with Reicons
  available as a saved choice. Keep the animated glyph player removed; motion
  belongs to controls, selection and navigation instead.
- Removed features stay removed: no ruler/set square/protractor, laser
  pointer, Claude handwriting reading or agent runtime. Do not bring their
  code or strings back.
- Large files: `MainActivity.kt` (screens) and `InkCanvasView.kt` (canvas).
  Search before adding; most features already have a home.
- Commit messages: a short summary line, then what and why. No model names
  or identifiers in code, commits or notes.

## Layout of the code (main pieces)

| Area | Files |
|---|---|
| Library (home) | `MainActivity.kt` (`HomeScreen`), `HomeLibrary.kt` |
| Note screen, toolbar | `MainActivity.kt` (`NoteScreen`, `Toolbar`), `FloatingToolbar.kt`, `ToolbarCustomize.kt` |
| Canvas and ink | `InkCanvasView.kt`, `CompatWetInkView.kt` (S6 Lite wet ink), `PencilBrushes.kt` |
| Storage | `Document.kt` (`NoteStore`, pages, save/load, whiteboard `board.bin`) |
| Text boxes, tables | `TextBox.kt`, `MarkdownSpans.kt`, `TableBox.kt`, `InlineText.kt` |
| Handwriting recognition | `InkIndexer.kt` (ML Kit), `OcrResultDialog.kt` |
| Markdown notes, vault | `MarkdownNoteScreen.kt`, `MarkdownText.kt`, `MarkdownVault*.kt` |
| Settings | `SettingsSidebar.kt`, `ToolSettingsDialog.kt`, `PenPreset.kt` (`PenStore`) |
| Diagnostics | `Diagnostics.kt`, `LatencyStats.kt`, `JankWatch.kt` (dropped frames → light mode), `MemoryTrim.kt` |
| Page panel, Internet panel | `PageSidebar.kt`, `WebPanel.kt` |
| Folders, smart folders, templates | `FolderPaths.kt` (paths `School/Maths`), `SmartFolders.kt`, `PaperPicker.kt` |
| Backup, widget, updates | `AutoBackup.kt` (off by default), `NotesWidget.kt`, `AppUpdate.kt` (beta code) |
| Look, popups | `Skin.kt`, `SkinSettings.kt` (the corner's saved key still says `spotiglass…`), `PopupGrowth.kt`, `PopupReveal.kt`, `AppDialog.kt`, `AppMenu.kt` |
| Roadmap, archive | `Todo.md` (work in order), `archive/` (glass looks, not built) |

## Stable release and native checks

This branch builds the original **Notesis** (`com.notesis`), promoted from the
validated Lab work at the owner's request. Keep its existing data directory,
widget actions, signing lineage and ordinary update channel. Lab stays in the
separate `com.notesis.lab` installation and its preview tags must remain excluded
from the normal feed. Source stays private; only release assets are public.

Run `bash tools/performance-lab/check-native.sh` before
`./gradlew testDebugUnitTest -Pnotesis.hostNativeDir="$PWD/build/host-native"`.
The script checks the same C++ kernel with address/undefined sanitizers and
compiles a JNI bridge for tests. Host benchmark results are not device results.
The own-buffer display remains experimental and disabled by default.

Dispatch `apk.yml` with `update=automatic` once per new stable version to build,
test, sign and publish a non-prerelease to the ordinary update feed. There is no
push trigger: fast-forwarding the tested release commit to main does not produce
a second release. Lab source branches keep their own Lab workflow.
