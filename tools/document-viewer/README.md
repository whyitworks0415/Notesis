# Offline document layout viewer

The Android document screen opens HWP/HWPX, PPT/PPTX, Markdown, TXT and XLS/XLSX
with the renderer in `app/src/main/assets/viewer`. The older Kotlin text extractor
remains available only through the explicit **텍스트 보기** button. DOC/DOCX keep
their existing preview path.

- HWP/HWPX: [rhwp](https://github.com/edwardkim/rhwp), paginated SVG with document
  margins, character styles, tables, images and page navigation.
- PPT/PPTX: [Web-PPT](https://github.com/unStone/web-ppt), SVG slides with original
  aspect ratio, positioned text, backgrounds, geometry and embedded images.
- Markdown: [Marked](https://github.com/markedjs/marked) with headings, lists,
  emphasis, quotes, tables and code. TXT keeps whitespace and supports UTF-8,
  UTF-16 BOMs and Korean legacy encoding. Both use a continuous paper surface.
- XLS/XLSX: [SheetJS](https://docs.sheetjs.com/) for cached values, dates, number
  formats, merges and sheet metadata. The OOXML style adapter preserves XLSX
  fonts, solid fills, borders, alignment, column widths and row heights. Sheets
  render 200 rows and 64 columns per window; navigation exposes the remaining
  rows/columns instead of dropping them. The cell bar shows full content/formulas.

Files stay in memory on the device. Only the selected file and bundled assets can
be fetched. There is no JavaScript-to-Android bridge or remote viewer/CDN. Source
documents are never modified. DOMPurify sanitizes generated HTML/SVG; CSP and the
native request interceptor block document scripts, external resources and navigation.
Input is limited to 128 MiB, ZIP expansion to 96 MiB, and each ZIP entry to 24 MiB.

## Build and verify

Requires Node.js 20+ and a local Chrome installation (or `BROWSER_CHANNEL=msedge`).

```sh
cd tools/document-viewer
npm ci --ignore-scripts
npm run build
npm test
```

Commit source, lockfile and generated assets together. Gradle/CI builds use the
checked-in assets and do not need npm or a network connection for the viewer.
The build copies the WASM engine and generates third-party notices from installed
packages. Screenshots and generated workbook fixtures go into ignored `test-output/`.

Android verification: `gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleRelease`.
With a debug APK installed on an emulator, set `ADB_PATH` to the SDK's adb executable
and run `node test/android-smoke.mjs` to open all eight formats in the actual WebView,
assert visible content/viewport height, and capture Android screenshots.
The viewer WebView must use MATCH_PARENT layout parameters: WRAP_CONTENT reports
a zero CSS viewport height even when Compose has measured a full-screen native view.

## Fidelity boundaries

This is a read-only rendering layer, not Hancom or Microsoft Office. Missing fonts
use platform substitutes, which can change line breaks. Unsupported embedded objects,
some advanced effects, animations and macros are not reproduced. Excel charts and
conditional formatting are not rendered, and legacy XLS visual styles have less
coverage than XLSX. Formula values come from the last calculation saved in the file;
the viewer does not run a calculation engine. Markdown external images/links are
inactive in the isolated viewer. Encrypted or damaged files show an error and offer
the explicit text view; they are never silently presented as a successful layout.
