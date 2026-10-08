package com.notesis

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.ink.brush.Brush
import androidx.ink.brush.ExperimentalInkCustomBrushApi
import androidx.ink.brush.InputToolType
import androidx.ink.brush.SelfOverlap
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import java.io.File
import kotlin.math.sin

/** Native ink and Android text rendering cannot be exercised by plain JVM tests. */
class CustomizationInstrumentation : Instrumentation() {
    private var renderOnly = false
    private var settingsOnly = false
    private var integrationOnly = false
    private var meshOnly = false
    private var gesturesOnly = false
    private var benchmarkStrokes = 24
    override fun onCreate(arguments: Bundle?) {
        renderOnly = arguments?.getString("suite") == "render"
        settingsOnly = arguments?.getString("suite") == "settings"
        integrationOnly = arguments?.getString("suite") == "integration"
        meshOnly = arguments?.getString("suite") == "mesh"
        gesturesOnly = arguments?.getString("suite") == "gestures"
        benchmarkStrokes = arguments?.getString("strokes")?.toIntOrNull()?.coerceIn(24, 1200) ?: 24
        super.onCreate(arguments); start()
    }

    private fun onMain(block: () -> Unit) {
        var failure: Throwable? = null
        runOnMainSync { try { block() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }

    private fun progress(label: String) {
        sendStatus(0, Bundle().apply { putString("stream", "CHECK $label\n") })
    }

    @androidx.annotation.RequiresApi(33)
    private fun checkSpotiGlassShader() {
        check(spotiGlassPath(androidx.compose.ui.geometry.Size.Zero, 12f).isEmpty)
        check(spotiGlassPath(androidx.compose.ui.geometry.Size(-1f, 20f), 12f).isEmpty)
        val source = Bitmap.createBitmap(400, 160, Bitmap.Config.ARGB_8888)
        for (y in 0 until source.height) for (x in 0 until source.width) {
            source.setPixel(x, y, Color.rgb((x * 7) % 256, (y * 9) % 256, (x + y) % 256))
        }
        val optical = SpotiGlassShader(targetContext) // Compiles the full port on Android Skia.
        optical.shader.setInputShader("u_texture_input", android.graphics.BitmapShader(
            source, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP))
        val size = androidx.compose.ui.geometry.Size(400f, 160f)
        val frame = SpotiGlassLensFrame(
            center = androidx.compose.ui.geometry.Offset(200f, 80f),
            size = androidx.compose.ui.geometry.Size(100f, 52f), radius = 26f,
            fill = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.08f),
        )
        fun render(value: SpotiGlassLensFrame): Bitmap {
            optical.update(size, value, 1f)
            val reader = android.media.ImageReader.newInstance(400, 160, android.graphics.PixelFormat.RGBA_8888,
                2, android.hardware.HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or android.hardware.HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
            val node = android.graphics.RenderNode("SpotiGlass shader test")
            node.setPosition(0, 0, 400, 160)
            // Exercise the exact cached effect used in Compose, not a freshly
            // created test-only effect that hides stale-uniform regressions.
            node.setRenderEffect(optical.nativeEffect)
            node.beginRecording().drawBitmap(source, 0f, 0f, null)
            node.endRecording()
            val renderer = android.graphics.HardwareRenderer().apply {
                setSurface(reader.surface); setContentRoot(node)
            }
            try {
                renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
                var image = reader.acquireNextImage()
                repeat(50) { if (image == null) { Thread.sleep(10); image = reader.acquireNextImage() } }
                val rendered = image ?: error("GPU shader image was not presented")
                return rendered.use {
                    val buffer = it.hardwareBuffer ?: error("GPU shader hardware buffer missing")
                    buffer.use {
                        val bitmap = Bitmap.wrapHardwareBuffer(it, android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB))
                            ?: error("GPU shader bitmap missing")
                        bitmap.copy(Bitmap.Config.ARGB_8888, false).also { bitmap.recycle() }
                    }
                }
            } finally { renderer.destroy(); reader.close() }
        }
        val resting = render(frame.copy(progress = 0f, presence = 0f))
        val lifted = render(frame)
        val sameEffect = optical.nativeEffect
        optical.update(size, frame, 1f)
        check(optical.nativeEffect === sameEffect) { "Static optics needlessly recreated the effect" }
        optical.update(size, frame.copy(distortion = 0.12f), 1f)
        check(optical.nativeEffect !== sameEffect) { "Changing uniforms retained the stale Android effect" }
        check(resting.getPixel(10, 10) == source.getPixel(10, 10)) { "Rest pill changed outside the lens" }
        check(lifted.getPixel(10, 10) == source.getPixel(10, 10)) { "Moving lens changed outside the lens" }
        check(lifted.getPixel(200, 80) == source.getPixel(200, 80)) { "Pure glass changed the unbent center" }
        var bentPixels = 0
        for (y in 50..110) for (x in 140..260) {
            check(Color.alpha(lifted.getPixel(x, y)) == 255)
            if (lifted.getPixel(x, y) != source.getPixel(x, y)) bentPixels++
        }
        check(bentPixels > 300) { "Refraction band did not bend the captured pixels: $bentPixels" }
        val stretched = render(frame.copy(size = androidx.compose.ui.geometry.Size(112f, 45.76f),
            scale = androidx.compose.ui.geometry.Offset(1.12f, 0.88f)))
        check(stretched.getPixel(10, 10) == source.getPixel(10, 10))
        val restControl = render(frame.copy(size = androidx.compose.ui.geometry.Size(37f, 24f), radius = 12f,
            fill = androidx.compose.ui.graphics.Color.White, progress = 0f, presence = 0f))
        val heldControl = render(frame.copy(size = androidx.compose.ui.geometry.Size(58f, 38.333f), radius = 19.1665f,
            fill = androidx.compose.ui.graphics.Color.White, distortion = 0.12f, band = 13f))
        check(restControl.getPixel(200, 80) == Color.WHITE) { "Rest control did not restore its solid white cover" }
        check(heldControl.getPixel(200, 80) == source.getPixel(200, 80)) { "Held control retained the opaque rest cover" }
        check(heldControl.getPixel(10, 10) == source.getPixel(10, 10))
        // A transparent control capture must not turn into an opaque rectangle.
        source.eraseColor(Color.TRANSPARENT)
        val transparent = render(frame.copy(fill = androidx.compose.ui.graphics.Color.Transparent))
        check(Color.alpha(transparent.getPixel(200, 80)) == 0) { "Glass punched an opaque hole through a transparent capture" }
        check(Color.alpha(transparent.getPixel(10, 10)) == 0)
        val solidThumb = render(frame.copy(fill = androidx.compose.ui.graphics.Color.White, progress = 0f, presence = 0f))
        check(solidThumb.getPixel(200, 80) == Color.WHITE) { "Rest thumb lost tint coverage" }
        File(targetContext.cacheDir, "spotiglass-shader-test.png").outputStream().use {
            stretched.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        // The same AGSL program must also be accepted by the live GPU effect.
        android.graphics.RenderEffect.createRuntimeShaderEffect(optical.shader, "u_texture_input")
        resting.recycle(); lifted.recycle(); stretched.recycle(); restControl.recycle(); heldControl.recycle(); source.recycle()
    }

    override fun onStart() {
        val output = Bundle()
        try {
            if (gesturesOnly) {
                checkCanvasGestures()
                output.putString("stream", "PASS eraser targets, scribble-out, circle to lasso, whole-only lasso, clipboard, page undo and keys\n")
                finish(Activity.RESULT_OK, output)
                return
            }
            if (meshOnly) {
                checkMeshCache()
                checkRenderingUi(denseStrokes = 3000)
                output.putString("stream", "PASS dense mesh GPU cache, pixels, appends, erase and zoom\n")
                finish(Activity.RESULT_OK, output)
                return
            }
            if (settingsOnly) {
                checkToolSettingsUi()
                output.putString("stream", "PASS settings slider, fluorescent palette opacity and eraser controls\n")
                finish(Activity.RESULT_OK, output)
                return
            }
            if (renderOnly) {
                checkRenderingUi(benchmarkStrokes)
                output.putString("stream", "PASS attached rendering and sustained synthetic stylus benchmark\n")
                finish(Activity.RESULT_OK, output)
                return
            }
            val isolated = object : ContextWrapper(targetContext) {
                override fun getFilesDir(): File = File(targetContext.cacheDir, "customization-test").apply { mkdirs() }
                override fun getSharedPreferences(name: String, mode: Int) =
                    super.getSharedPreferences("customization-test-$name", mode)
            }
            if (integrationOnly) {
                checkCloudIntegration(NoteStore(isolated), isolated)
                output.putString("stream", "PASS cloud integration: editing, v2 reload, library, study, recording and portable PDF links\n")
                finish(Activity.RESULT_OK, output)
                return
            }
            isolated.getSharedPreferences("pens", Context.MODE_PRIVATE).edit().clear().commit()
            val pens = PenStore(isolated)
            pens.saveFavoriteWidths(EditMode.PEN, listOf(0.25f, 1.5f, 3f))
            pens.saveColorTemplates(listOf("수학" to listOf(Color.BLUE, Color.RED)))
            pens.toolbarSize = 2
            pens.homeColor = Color.BLUE
            val reloaded = PenStore(isolated)
            check(reloaded.favoriteWidths(EditMode.PEN) == listOf(0.25f, 1.5f, 3f))
            check(reloaded.colorTemplates().single().second == listOf(Color.BLUE, Color.RED))
            check(reloaded.toolbarSize == 2 && reloaded.homeColor == Color.BLUE)
            pens.skin = Skin.SPOTIGLASS
            val glassLook = SkinSettings(spotiglassClarity = 0.43f, spotiglassToolbarOpacity = 0.62f, spotiglassResponse = 0.25f,
                corner = 32f, themeMode = AppThemeMode.DARK)
            SkinSettingsStore(isolated).save(glassLook)
            check(PenStore(isolated).skin == Skin.SPOTIGLASS)
            check(SkinSettingsStore(isolated).load() == glassLook)
            check(SkinSettings.fromJson("{}").spotiglassClarity == SkinSettings().spotiglassClarity)
            check(SkinSettings.fromJson("{}").spotiglassToolbarOpacity == SkinSettings().spotiglassToolbarOpacity)
            check(SkinSettings.fromJson("{\"spotiglassToolbarOpacity\":2}").spotiglassToolbarOpacity == 1f)
            check(SkinSettings.fromJson("{\"spotiglassToolbarOpacity\":-1}").spotiglassToolbarOpacity == 0f)
            check(SkinSettings.fromJson("{\"spotiglassClarity\":2,\"spotiglassResponse\":-1}")
                .let { it.spotiglassClarity == 1f && it.spotiglassResponse == 0f })
            progress("glass shader")
            if (android.os.Build.VERSION.SDK_INT >= 33) checkSpotiGlassShader()
            progress("settings UI")
            checkToolSettingsUi()
            progress("attached ink rendering")
            checkRenderingUi(denseStrokes = 3000)
            progress("dense mesh GPU cache")
            checkMeshCache()
            progress("icon gallery")
            checkReiconUi()
            progress("canvas gestures")
            checkCanvasGestures()
            progress("text and PDF roundtrip")
            val content = TextBoxContent("한글 텍스트\n수식 x² + y² = 1", 32f, Color.BLUE)
            val bitmap = renderTextBox(content)
            check(bitmap.width > 20 && bitmap.height > 20)
            check(bitmap.getPixel(0, 0) == Color.TRANSPARENT)
            val store = NoteStore(isolated)
            val note = store.create("텍스트 회귀 테스트")
            val doc = store.load(note.id)
            val added = store.addImage(note.id, bitmap) ?: error("image save")
            var canvas: InkCanvasView? = null
            onMain {
                canvas = InkCanvasView(targetContext.createDisplayContext(
                    targetContext.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(0))).also {
                    it.open(doc, null)
                    it.putTextBox(added.first, bitmap.width, bitmap.height, content)
                    check(doc.pages[0].images.single().textContent == content)
                    it.undo()
                    check(doc.pages[0].images.isEmpty())
                    it.redo()
                    check(doc.pages[0].images.single().textContent == content)
                }
            }
            val before = doc.pages[0].images.single()
            val changed = content.copy(text = "수정한 텍스트", size = 40f, color = Color.RED)
            val changedBitmap = renderTextBox(changed)
            val replacement = store.addImage(note.id, changedBitmap) ?: error("replacement save")
            onMain { canvas!!.putTextBox(replacement.first, changedBitmap.width, changedBitmap.height, changed, before) }
            store.save(note.id, note.title, doc)
            check(store.imageFile(note.id, before.id).isFile) // autosave must preserve undo assets
            check(store.load(note.id).pages[0].images.single().textContent == changed)
            onMain {
                canvas!!.undo()
                check(doc.pages[0].images.single().textContent == content)
                canvas!!.redo()
                check(doc.pages[0].images.single().textContent == changed)
            }
            val exported = File(isolated.cacheDir, "customization-text.pdf")
            check(exported.outputStream().use { store.exportPdf(note.id, it) })
            PdfSource.open(exported)?.use { pdf ->
                val preview = pdf.renderSelection(PdfSelection(0, "錯誤", listOf(RectF(0f, 0f, 1240f, 1754f))))
                check(preview != null && preview.width > 0)
                preview.recycle()
            } ?: error("PDF roundtrip")
            progress("native ink and storage regressions")
            verifyIntermittentContactSpurs()
            verifyStationaryPenStarts()
            verifyPenAntialiasing()
            verifyToolIdentity(store, isolated)
            verifySmoothPenRendering()
            verifyScaledInkCache()
            verifyPdfFallback()
            verifyVectorStrokeOverlap(store, isolated)
            progress("cloud integration")
            checkCloudIntegration(store, isolated)
            store.delete(note.id)
            bitmap.recycle()
            changedBitmap.recycle()
            output.putString("stream", "PASS Spotiglass GPU shader compilation/refraction/deformation, preferences, text rendering, insertion/edit/undo/redo, autosave/reload, intermittent pen contact, stationary pen starts, pen antialiasing, PDF export/preview and stroke overlap\n")
            finish(Activity.RESULT_OK, output)
        } catch (error: Throwable) {
            output.putString("stream", error.stackTraceToString())
            finish(Activity.RESULT_CANCELED, output)
        }
    }

    private fun verifyIntermittentContactSpurs() {
        val brush = Brush.createWithColorIntArgb(Tool.PEN.brushFamily(), Color.BLACK, 5f, 0.02f)
        fun stroke(points: List<Pair<Float, Float>>, stepMs: Long = 8L): Stroke {
            val inputs = MutableStrokeInputBatch()
            points.forEachIndexed { index, (x, y) ->
                inputs.add(InputToolType.STYLUS, x + 100f, y + 100f, index * stepMs)
            }
            return Stroke(brush, inputs.toImmutable())
        }
        fun times(stroke: Stroke): List<Long> {
            val sample = StrokeInput()
            return (0 until stroke.inputs.size).map { index ->
                stroke.inputs.populate(index, sample).elapsedTimeMillis
            }
        }
        val early = listOf(0f to 0f, 3f to 0f, 0.3f to 0f,
            1.2f to 0f, 2.2f to 0f, 3.2f to 0f, 4.2f to 0f)
        val later = listOf(0f to 0f, 1f to 0f, 2f to 0f, 4.8f to 0f,
            2.2f to 0f, 3.2f to 0f, 4.2f to 0f)
        for (points in listOf(early, later)) {
            val expectedRemoved = if (points === early) 8L else 24L
            for (rotated in listOf(
                points,
                points.map { it.second to it.first },
                points.map { -it.first to it.second },
                points.map { it.second to -it.first },
            )) {
                val original = stroke(rotated)
                val cleaned = withoutContactSpurs(original)
                check(cleaned.inputs.size == original.inputs.size - 1)
                check(expectedRemoved !in times(cleaned))
                check(times(cleaned).first() == 0L && times(cleaned).last() == 48L)
            }
        }
        val atLift = stroke(listOf(0f to 0f, 1f to 0f, 2f to 0f,
            3f to 0f, 4f to 0f, 5f to 0f, 6f to 0f, 9f to 0f, 6.3f to 0f))
        val cleanLift = withoutContactSpurs(atLift)
        check(cleanLift.inputs.size == atLift.inputs.size - 1)
        check(56L !in times(cleanLift))
        val intentionalCorner = stroke(listOf(0f to 0f, 3f to 0f, 3f to 3f,
            3f to 6f, 4f to 8f))
        check(withoutContactSpurs(intentionalCorner) === intentionalCorner)
        val slowReversal = stroke(early, 50L)
        check(withoutContactSpurs(slowReversal) === slowReversal)
    }

    private fun verifyStationaryPenStarts() {
        val brush = Brush.createWithColorIntArgb(Tool.PEN.brushFamily(), Color.BLUE, 1.4f, 0.00625f)
        for (stationaryCount in listOf(12, 28)) {
            val inputs = MutableStrokeInputBatch()
            inputs.add(InputToolType.STYLUS, 100f, 100f, 0L)
            for (index in 1 until stationaryCount) {
                inputs.add(
                    InputToolType.STYLUS,
                    100f + (index % 3 - 1) * 0.015f,
                    100f + (index % 4 - 2) * 0.012f,
                    index * 2L,
                )
            }
            inputs.add(InputToolType.STYLUS, 100.25f, 100f, stationaryCount * 2L)
            inputs.add(InputToolType.STYLUS, 101f, 100.3f, (stationaryCount + 1) * 2L)
            val original = Stroke(brush, inputs.toImmutable())
            val clean = withoutStationaryStart(original)
            check(clean.inputs.size == 3)
            val point = StrokeInput()
            clean.inputs.populate(0, point)
            check(point.x == 100f && point.y == 100f && point.elapsedTimeMillis == 0L)
            clean.inputs.populate(1, point)
            check(point.x == 100.25f && point.elapsedTimeMillis == stationaryCount * 2L)
            check(withoutStationaryStart(clean) === clean)
        }
    }

    private fun verifySmoothPenRendering() {
        val bitmap = Bitmap.createBitmap(1000, 550, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val renderer = CanvasStrokeRenderer.create(PencilTextureStore)
        val label = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.DKGRAY; textSize = 22f
        }
        for ((row, strength) in listOf(0, 20).withIndex()) {
            canvas.drawText(if (strength == 0) "Raw input" else "Direction-aware smoothing 20%", 24f, 28f + row * 260f, label)
            val filter = AdaptiveStrokeStabilizer()
            val inputs = MutableStrokeInputBatch()
            for (i in 0..360) {
                val angle = i * Math.PI / 36.0
                val jitter = if (i % 2 == 0) 0.7f else -0.7f
                val x = 35f + i * 0.9f + 21f * kotlin.math.cos(angle).toFloat()
                val y = 62f + 32f * kotlin.math.sin(angle).toFloat() + jitter
                if (i == 0) filter.reset(strength, x, y, 0)
                else filter.add(x, y, i * 4L, finalSample = i == 360)
                inputs.add(InputToolType.STYLUS, filter.x, filter.y, i * 4L)
            }
            val brush = Brush.createWithColorIntArgb(Tool.PEN.brushFamily(), Color.BLACK, 0.7f, 0.01f)
            val stroke = Stroke(brush, inputs.toImmutable())
            check(stroke.inputs.size == 361) { "Smoothing lost loop input samples" }
            val transform = Matrix().apply { setScale(2.4f, 2.4f); postTranslate(20f, 38f + row * 260f) }
            val saved = canvas.save()
            canvas.concat(transform)
            renderer.draw(canvas, stroke, transform)
            canvas.restoreToCount(saved)
            var inkPixels = 0
            for (y in 100 + row * 260 until 255 + row * 260) for (x in 100 until 900) {
                if (Color.red(bitmap.getPixel(x, y)) < 220) inkPixels++
            }
            check(inkPixels > 1000) { "Thin pen comparison row $row is blank" }
        }
        File(targetContext.cacheDir, "smooth-pen-comparison.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private fun verifyScaledInkCache() {
        val inputs = MutableStrokeInputBatch().apply {
            add(InputToolType.STYLUS, 15.4f, 20.6f, 0L)
            add(InputToolType.STYLUS, 105.3f, 94.7f, 24L)
        }
        val stroke = Stroke(Brush.createWithColorIntArgb(Tool.PEN.brushFamily(), Color.BLACK,
            0.7f, 0.01f), inputs.toImmutable())
        val bitmap = rasterizeInk(256, 256, 2f, listOf(stroke)) ?: error("ink cache")
        try {
            var maxX = 0
            var partial = 0
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                val alpha = Color.alpha(bitmap.getPixel(x, y))
                if (alpha > 0) maxX = maxOf(maxX, x)
                if (alpha in 1..254) partial++
            }
            check(maxX in 209..213) { "Raster cache did not apply page scale: $maxX" }
            check(partial > 150) { "Thin cached pen lost edge coverage: $partial" }
            File(targetContext.cacheDir, "scaled-ink-cache.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally { bitmap.recycle() }
        check(rasterizeInk(256, 256, 2f, listOf(stroke)) { true } == null)
    }

    private fun verifyToolIdentity(store: NoteStore, context: Context) {
        for (tool in Tool.entries.filter { it != Tool.ERASER }) {
            check(Tool.ofBrushFamily(tool.brushFamily()) == tool) { "Brush identity collided for $tool" }
        }
        check(Tool.PEN.brushFamily() != Tool.HIGHLIGHTER.brushFamily())
        val note = store.create("브러시 식별 회귀 검사")
        try {
            val document = store.load(note.id)
            val page = document.pages.single()
            page.loaded = true; page.dirty = true
            for ((index, tool) in listOf(Tool.PEN, Tool.HIGHLIGHTER, Tool.MASK).withIndex()) {
                val input = MutableStrokeInputBatch().apply {
                    add(InputToolType.STYLUS, 100f, 100f + index * 30f, 0L)
                    add(InputToolType.STYLUS, 400f, 100f + index * 30f, 20L)
                }
                val color = if (tool == Tool.HIGHLIGHTER) 0x66FFEB3B else Color.RED
                page.strokes += Stroke(Brush.createWithColorIntArgb(tool.brushFamily(), color, 5f, 0.02f), input.toImmutable())
            }
            store.save(note.id, note.title, document)
            val restored = store.load(note.id)
            val restoredStrokes = store.loadPage(note.id, restored.pages.single(), 0.02f)
            check(restoredStrokes.map { Tool.ofBrushFamily(it.brush.family) } ==
                listOf(Tool.PEN, Tool.HIGHLIGHTER, Tool.MASK)) { "Reload lost drawing tool identity" }
            val strokeFile = File(context.filesDir, "notes/${note.id}/pages/${page.id}.bin")
            java.io.RandomAccessFile(strokeFile, "rw").use {
                it.seek(4); it.writeInt(1)
                it.seek(NoteStore.HEADER_BYTES); it.writeInt(Tool.HIGHLIGHTER.ordinal)
            }
            val legacy = store.loadPage(note.id, restored.pages.single(), 0.02f)
            check(Tool.ofBrushFamily(legacy.first().brush.family) == Tool.PEN) { "Legacy opaque pen remained a highlight" }
            check(Tool.ofBrushFamily(legacy[1].brush.family) == Tool.HIGHLIGHTER)
        } finally { store.delete(note.id) }
    }

    private fun verifyPdfFallback() {
        val file = File(targetContext.cacheDir, "pdf-fallback-regression.pdf")
        val document = android.graphics.pdf.PdfDocument()
        try {
            repeat(3) { index ->
                val page = document.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(595, 842, index + 1).create())
                page.canvas.drawColor(listOf(Color.RED, Color.GREEN, Color.BLUE)[index])
                document.finishPage(page)
            }
            file.outputStream().use { document.writeTo(it) }
        } finally { document.close() }
        PdfSource.open(file)?.use { pdf ->
            fun awaitSharp(index: Int) {
                val deadline = android.os.SystemClock.uptimeMillis() + 15_000
                while (pdf.bitmap(index, 2048, request = false)?.width != 2048) {
                    check(android.os.SystemClock.uptimeMillis() < deadline) { "PDF render timeout page $index" }
                    Thread.sleep(20)
                }
            }
            pdf.prioritizePages(listOf(0), 2048)
            awaitSharp(0)
            // Rapid bucket changes must not cancel an immutable page render.
            repeat(20) { pdf.prioritizePages(listOf(0), if (it % 2 == 0) 1024 else 2048) }
            for (index in 1..2) { pdf.prioritizePages(listOf(index), 2048); awaitSharp(index) }
            val fallback = pdf.bitmap(0, 2048, request = false) ?: error("Evicted page flashed white")
            check(fallback.width < 1024) { "Fixture did not evict the sharp page" }
            for (width in listOf(1024, 2048, 4096, 1024)) {
                val image = pdf.bitmap(0, width, request = false) ?: error("Pinch lost PDF fallback")
                val pixel = image.getPixel(image.width / 2, image.height / 2)
                check(Color.red(pixel) > 240 && Color.green(pixel) < 15) { "PDF fallback became blank" }
            }
        } ?: error("PDF fallback fixture")
    }

    @OptIn(ExperimentalInkCustomBrushApi::class)
    private fun verifyPenAntialiasing() {
        for (tool in listOf(Tool.PEN, Tool.PRESSURE_PEN)) {
            val family = tool.brushFamily()
            val paints = family.coats.first().paintPreferences
            check(paints.isNotEmpty() && paints.all {
                it.selfOverlap == SelfOverlap.DISCARD
            })
            val brush = Brush.createWithColorIntArgb(family, Color.BLACK, 3f, 0.02f)
            val inputs = MutableStrokeInputBatch().apply {
                add(InputToolType.STYLUS, 15.4f, 20.6f, 0L)
                add(InputToolType.STYLUS, 70.5f, 59.2f, 12L)
                add(InputToolType.STYLUS, 105.3f, 94.7f, 24L)
            }
            val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
            try {
                CanvasStrokeRenderer.create(PencilTextureStore).draw(
                    Canvas(bitmap), Stroke(brush, inputs.toImmutable()), Matrix(),
                )
                var solid = false
                var antialiased = false
                for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                    when (Color.alpha(bitmap.getPixel(x, y))) {
                        255 -> solid = true
                        in 1..254 -> antialiased = true
                    }
                }
                check(solid && antialiased) { "$tool did not produce an antialiased edge" }
            } finally {
                bitmap.recycle()
            }
        }
    }

    private fun verifyVectorStrokeOverlap(store: NoteStore, context: Context) {
        val note = store.create("벡터 잉크 겹침 회귀 테스트")
        try {
            val document = store.load(note.id)
            val page = document.pages.single()
            page.loaded = true
            page.dirty = true
            fun stroke(tool: Tool, color: Int, y: Float): Stroke {
                val samples = MutableStrokeInputBatch()
                listOf(200f, 400f, 200f, 400f).forEachIndexed { index, x ->
                    samples.add(InputToolType.STYLUS, x, y, index * 8L)
                }
                return Stroke(
                    Brush.createWithColorIntArgb(tool.brushFamily(), color, 20f, 0.02f),
                    samples.toImmutable(),
                )
            }
            page.strokes += stroke(Tool.PEN, Color.BLACK, 200f)
            page.strokes += stroke(Tool.HIGHLIGHTER, 0x99FFCC00.toInt(), 300f)
            page.masks += PageMask(stroke(Tool.MASK, 0xFF8855CC.toInt(), 400f))
            val curve = MutableStrokeInputBatch()
            for (index in 0..16) {
                curve.add(
                    InputToolType.STYLUS,
                    200f + index * 12.5f,
                    600f + 35f * sin(index * Math.PI / 16.0).toFloat(),
                    index * 8L,
                )
            }
            page.strokes += Stroke(
                Brush.createWithColorIntArgb(Tool.PEN.brushFamily(), Color.BLUE, 8f, 0.02f),
                curve.toImmutable(),
            )
            store.save(note.id, note.title, document)
            val output = File(context.cacheDir, "vector-overlap-regression.pdf")
            check(output.outputStream().use { store.exportPdf(note.id, it) })
            ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { pdf ->
                    pdf.openPage(0).use { rendered ->
                        val bitmap = Bitmap.createBitmap(1240, 1754, Bitmap.Config.ARGB_8888)
                        try {
                            rendered.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            listOf(200, 300, 400).forEach { y ->
                                val pixel = bitmap.getPixel(300, y)
                                check(Color.red(pixel) + Color.green(pixel) + Color.blue(pixel) < 680) {
                                    "Exported $y ink overlap is blank: ${Integer.toHexString(pixel)}"
                                }
                            }
                        } finally { bitmap.recycle() }
                    }
                }
            }
        } finally { store.delete(note.id) }
    }
}
