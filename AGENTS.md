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
6. Version numbers (the owner's plan): **0.100.0 is kept for the release the
   owner calls complete ("완벽하다")**, and nothing reaches it before. Until
   then a release with new features raises the minor while it is below 0.99
   (0.95.0, 0.96.0 …), a release of fixes only raises the patch (0.95.1); from
   0.99 on, every release raises the patch (0.99.1, 0.99.2 …). `versionCode`
   is always +1.
7. The account's Actions artifact storage has filled before. Artifacts are
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
  pointer or Claude handwriting reading. Do not bring their code or strings
  back. The old agent runtime stays out as well; at the owner's request its
  place is taken by the note commands (`NoteCommands.kt`, Todo.md section 5):
  an agent or terminal changes notes only through them, never a note open on
  screen, deletes only to the trash, and every change can be undone.
- The agent connection (Todo.md C3) is off until the owner turns it on, listens on
  127.0.0.1 (private networks only when the owner switches that on too), and
  answers nothing without its token: the token is made on the device, shown only
  on the owner's screen, kept out of backups, and never logged, printed or
  written in the repository. An agent's changes go through `AgentGateway`: asked
  about first or made at once as the owner chose, never on a note on screen
  (`OpenNotes`), always undoable. A command added to `NoteCommands` must also be
  described in `describeCommand` (a test fails otherwise), or it would run
  without asking. Do not add a way around any of this.
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
| Markdown notes, vault | `MarkdownNoteScreen.kt`, `MarkdownBlockEditor.kt` (Live blocks), `MarkdownLive.kt` (pure: blocks, inline rules, tables, slash menu, undo), `MarkdownReadView.kt` + `assets/viewer/md-read.*` (reading), `MarkdownText.kt`, `MarkdownVault*.kt` |
| Settings | `SettingsSidebar.kt`, `ToolSettingsDialog.kt`, `PenPreset.kt` (`PenStore`) |
| Diagnostics | `Diagnostics.kt`, `LatencyStats.kt`, `JankWatch.kt` (dropped frames → light mode), `MemoryTrim.kt` |
| Page panel, Internet panel | `PageSidebar.kt`, `WebPanel.kt` |
| Folders, smart folders, templates | `FolderPaths.kt` (paths `School/Maths`), `SmartFolders.kt`, `PaperPicker.kt` |
| Note commands (`notesis ...`), terminal | `NoteCommands.kt` (pure: words, paths, undo), `NoteCommandStore.kt` (on disk), `Terminal.kt` (pure: completion, history, printing), `TerminalPanel.kt` (`TerminalHost`, `TerminalSession`) |
| Agent connection (MCP) | `McpProtocol.kt` (pure JSON-RPC), `AgentServer.kt` (HTTP and its checks), `AgentGateway.kt` (ask or make at once), `ChangeCards.kt` (what a command says it does, the log, the questions waiting), `AgentConnection.kt` (settings, `AgentRuntime`, `AgentService`), `AgentPanel.kt` (setup panel, approval card), `OpenNotes.kt` (notes on screen, `SharedNotes`) |
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
