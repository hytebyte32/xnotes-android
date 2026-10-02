package com.xnotes.bench

import androidx.activity.ComponentActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import com.hrm.latex.renderer.export.rememberLatexExporter
import com.hrm.latex.renderer.measure.rememberLatexMeasurer
import com.xnotes.core.infinite.Tuning
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.model.Document
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.Stroke
import com.xnotes.core.model.TextItem
import com.xnotes.core.tools.Tool
import com.xnotes.format.CanvasCodec
import com.xnotes.format.DocumentCodec
import com.xnotes.gl.GlStats
import com.xnotes.platform.AndroidImageCodec
import com.xnotes.platform.AndroidRenderer
import com.xnotes.platform.AndroidTextMeasurer
import com.xnotes.platform.MathRendering
import com.xnotes.platform.PdfSource
import com.xnotes.ui.Editor
import com.xnotes.ui.InfiniteEditor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Renderer spike harness. It builds the same synthetic notes for the paged (Skia) stack and the
 * infinite-canvas (GL) stack, drives each with real synthesized touch events, and reports frame
 * times, memory and load times. Throwaway: nothing here ships in the real app.
 */
class BenchActivity : ComponentActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    private lateinit var stage: FrameLayout
    private lateinit var panel: LinearLayout
    private lateinit var reportView: TextView

    private val text = StringBuilder()
    private val results = JSONObject()
    private var refreshMs = 16.6

    private val pickNotes = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) startNoteCheck(uri)
    }

    private var paged: Editor? = null
    private var canvas: InfiniteEditor? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashCapture()
        @Suppress("DEPRECATION")
        refreshMs = 1000.0 / windowManager.defaultDisplay.refreshRate.toDouble().coerceAtLeast(30.0)
        val root = FrameLayout(this)
        stage = FrameLayout(this)
        root.addView(stage, FrameLayout.LayoutParams(-1, -1))
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(235, 18, 18, 22))
            setPadding(24, 24, 24, 24)
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun button(label: String, onClick: () -> Unit) = Button(this).apply {
            this.text = label
            setOnClickListener { onClick() }
            row.addView(this)
        }
        button("Run memory") { startMemory() }
        button("Run decision") { startDecision() }
        button("Run heap") { startHeap() }
        button("Check my notes") { pickNotes.launch(null) }
        button("GL paged smoke test") { startGlSmoke() }
        button("Copy report") { copyReport() }
        button("Save JSON") { saveJson() }
        panel.addView(row)
        reportView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        panel.addView(ScrollView(this).apply { addView(reportView) }, LinearLayout.LayoutParams(-1, -1))
        // The LaTeX engine is built in a composition, so a one-pixel host gives the bench the same one the app has.
        root.addView(
            ComposeView(this).apply {
                setContent {
                    val measurer = rememberLatexMeasurer()
                    val exporter = rememberLatexExporter()
                    val density = LocalDensity.current
                    LaunchedEffect(measurer, exporter, density) { MathRendering.install(measurer, exporter, density) }
                }
            },
            FrameLayout.LayoutParams(1, 1),
        )
        root.addView(panel, FrameLayout.LayoutParams(-1, -1, Gravity.TOP))
        setContentView(root)
        line("xnotes renderer spike. Keep the screen on and the pen away while it runs.")
        File(filesDir, "crash.txt").takeIf { it.exists() }?.let { f ->
            line("=== LAST RUN CRASHED ===")
            line(f.readText().take(3500))
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("crash", f.readText()))
            line("(crash text copied to clipboard)")
            f.delete()
        }
    }

    /** Writes an uncaught exception to a file the next launch shows, since there is no logcat on the tablet. */
    private fun installCrashCapture() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                File(filesDir, "crash.txt").writeText("thread ${t.name}\n" + e.stackTraceToString().take(6000) + "\nlast log lines:\n" + (0 until log.length()).toList().takeLast(8).joinToString("\n") { log.getString(it) })
            } catch (_: Throwable) {
            }
            autosave()
            previous?.uncaughtException(t, e)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // --- reporting ---

    private val log = JSONArray()
    private var autosaveUri: android.net.Uri? = null
    private var lastAutosave = 0L

    /** One partial-results file per run, rewritten as the run goes, so a crash leaves what was measured. */
    private fun beginAutosave() {
        if (Build.VERSION.SDK_INT < 29) return
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "xnotes-bench-partial-${System.currentTimeMillis()}.json")
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/")
        }
        autosaveUri = try { contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) } catch (_: Throwable) { null }
    }

    private fun autosave() {
        val uri = autosaveUri ?: return
        try {
            results.put("log", log)
            contentResolver.openOutputStream(uri, "wt")?.use { it.write(results.toString(2).toByteArray()) }
            lastAutosave = SystemClock.elapsedRealtime()
        } catch (_: Throwable) {
        }
    }


    private fun line(s: String) {
        log.put(s)
        text.append(s).append('\n')
        reportView.text = text
        if (SystemClock.elapsedRealtime() - lastAutosave > 5000) autosave()
    }

    private fun copyReport() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("bench", text.toString()))
        line("(report copied)")
    }

    private fun saveJson() {
        if (Build.VERSION.SDK_INT < 29) { line("(save needs Android 10+)"); return }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "xnotes-bench-${System.currentTimeMillis()}.json")
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/")
        }
        results.put("log", log)
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        if (uri == null) { line("(could not create file)"); return }
        contentResolver.openOutputStream(uri)?.use { it.write(results.toString(2).toByteArray()) }
        line("(saved to Downloads)")
    }

    // --- running ---

    private fun start(quick: Boolean) {
        if (job?.isActive == true) return
        text.setLength(0)
        reportView.text = ""
        beginAutosave()
        job = scope.launch {
            panel.visibility = View.INVISIBLE
            try {
                suite(quick)
            } catch (t: Throwable) {
                line("FAILED: $t")
            }
            panel.visibility = View.VISIBLE
            line("done")
        }
    }

    private suspend fun suite(quick: Boolean) {
        results.put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
        results.put("sdk", Build.VERSION.SDK_INT)
        results.put("refresh_ms", round(refreshMs))
        line("${Build.MODEL}  refresh ${round(1000.0 / refreshMs)} Hz")
        val sizes = if (quick) listOf(1_000, 10_000) else listOf(1_000, 10_000, 100_000)
        val gestures = JSONArray()
        results.put("gestures", gestures)
        for (n in sizes) {
            line("--- $n strokes (${(n + BenchData.PER_PAGE - 1) / BenchData.PER_PAGE} page-areas) ---")
            try { pagedScenario(n, gestures) } catch (e: OutOfMemoryError) { line("paged $n: OUT OF MEMORY") }
            try { canvasScenario(n, gestures) } catch (e: OutOfMemoryError) { line("canvas $n: OUT OF MEMORY") }
        }
        Tuning.reset()
        try { padComparison() } catch (t: Throwable) { line("pad test failed: ${t.stackTraceToString().take(600)}") }
        try { pdfRegions() } catch (t: Throwable) { line("pdf test failed: ${t.stackTraceToString().take(600)}") }
        try { textTextures() } catch (t: Throwable) { line("text test failed: ${t.stackTraceToString().take(900)}") }
        try { coldOpen() } catch (t: Throwable) { line("cold open failed: ${t.stackTraceToString().take(900)}") }
        saveJson()
    }

    // --- helpers ---

    private suspend fun mount(v: View) {
        stage.removeAllViews()
        stage.addView(v, FrameLayout.LayoutParams(-1, -1))
        var guard = 0
        while (v.width <= 0 && guard++ < 60) awaitFrame()
        repeat(3) { awaitFrame() }
    }

    private fun glLine(prefix: String, s: GlStats) =
        line("$prefix  gl: ${round(s.fps)} fps, work ${round(s.frameMs)} ms, worst ${round(s.worstFrameMs)} ms, " +
            "draws ${s.drawCalls}, visible ${s.visibleItems}/${s.items}")

    // --- paged (Skia) ---

    private suspend fun pagedScenario(n: Int, out: JSONArray) {
        val ed = paged ?: Editor(this).also { paged = it }
        val t0 = SystemClock.elapsedRealtime()
        val doc = withContext(Dispatchers.Default) { BenchData.pagedDocument(n) }
        line("paged: built in ${SystemClock.elapsedRealtime() - t0} ms")
        mount(ed.surfaces)
        val st = ed.state
        val tLoad = SystemClock.elapsedRealtime()
        st.document = doc
        st.invalidateAllCaches()
        st.relayout()
        st.fitWidth()
        awaitFrame()
        val loadMs = SystemClock.elapsedRealtime() - tLoad
        line("paged: loaded in $loadMs ms")
        delay(2500)
        ed.selectTool(Tool.PEN)
        for ((label, zoomMul, kind) in gestureMatrix()) {
            st.scrollY = 0.0
            st.clampScroll()
            st.fitWidth()
            if (zoomMul != 1.0) st.setZoomAnchored(st.clearCenter(), st.zoom * zoomMul)
            delay(1500)
            var blurry = 0
            var frames = 0
            val r = gesture(
                kind, ed.view, 4.0,
                { Triple(st.zoom, st.scrollX, st.scrollY) },
            ) {
                frames++
                if (st.isPastResolutionCap() && st.sharpViewportBlit() == null) blurry++
            }
            r.put("renderer", "paged").put("strokes", n).put("gesture", label).put("load_ms", loadMs)
            r.put("blurry_frame_pct", if (frames == 0) 0.0 else round(100.0 * blurry / frames))
            out.put(r)
            line("paged  $label  p50 ${r.optDouble("p50_ms")} p95 ${r.optDouble("p95_ms")} max ${r.optDouble("max_ms")} ms, " +
                ">1.5x ${r.optDouble("over_1_5_refresh_pct")}%, blurry ${r.optDouble("blurry_frame_pct")}%")
            delay(800)
        }
        st.scrollY = 0.0
        st.fitWidth()
        delay(1200)
        out.put(wetInk("paged", n, ed.view, { st.document.pages.sumOf { it.items.size } }, null))
        results.put("mem_paged_$n", memorySnapshot())
        st.document = Document.blank()
        st.invalidateAllCaches()
        st.relayout()
        System.gc()
        delay(500)
    }

    // --- canvas (GL) ---

    private suspend fun canvasScenario(n: Int, out: JSONArray, extraItems: Boolean = false) {
        val ed = canvas ?: InfiniteEditor(this).also { canvas = it }
        val t0 = SystemClock.elapsedRealtime()
        val doc = withContext(Dispatchers.Default) { BenchData.canvasDocument(n) }
        line("canvas: built in ${SystemClock.elapsedRealtime() - t0} ms")
        mount(ed.surfaces)
        val vp = ed.viewport
        val tLoad = SystemClock.elapsedRealtime()
        ed.replaceDocument(doc)
        awaitFrame()
        val loadMs = SystemClock.elapsedRealtime() - tLoad
        line("canvas: loaded + meshed in $loadMs ms")
        delay(1500)
        ed.armTool(Tool.PEN)
        for ((label, zoomMul, kind) in gestureMatrix()) {
            vp.zoom = vp.widthPx / BenchData.page.first
            vp.scrollX = 0.0
            vp.scrollY = 0.0
            vp.clampToLimits()
            if (zoomMul != 1.0) vp.zoomAround(vp.widthPx / 2.0, vp.heightPx / 2.0, vp.zoom * zoomMul)
            ed.view.publish()
            delay(1200)
            val samples = ArrayList<GlStats>()
            var tick = 0
            val r = gesture(kind, ed.view.let { it }, 4.0, { Triple(vp.zoom, vp.scrollX, vp.scrollY) }) {
                if (++tick % 20 == 0) samples.add(ed.view.stats)
            }
            r.put("renderer", "gl").put("strokes", n).put("gesture", label).put("load_ms", loadMs)
            if (samples.isNotEmpty()) {
                r.put("gl_fps_mean", round(samples.map { it.fps }.average()))
                r.put("gl_work_ms_mean", round(samples.map { it.frameMs }.average()))
                r.put("gl_worst_ms_max", round(samples.maxOf { it.worstFrameMs }))
                r.put("gl_draw_calls", samples.last().drawCalls)
                r.put("gl_texture_mb", samples.last().textureBytes / (1024 * 1024))
                r.put("gl_geometry_mb", samples.last().geometryBytes / (1024 * 1024))
            }
            out.put(r)
            line("canvas $label  p50 ${r.optDouble("p50_ms")} p95 ${r.optDouble("p95_ms")} max ${r.optDouble("max_ms")} ms, " +
                ">1.5x ${r.optDouble("over_1_5_refresh_pct")}%  gl work ${r.optDouble("gl_work_ms_mean")} ms")
            delay(800)
        }
        vp.zoom = vp.widthPx / BenchData.page.first
        vp.scrollX = 0.0
        vp.scrollY = 0.0
        ed.view.publish()
        delay(800)
        out.put(wetInk("gl", n, ed.view, { ed.document.items.size }, ed))
        val s = ed.view.stats
        results.put("env_gl", "${s.renderer} / ${s.glVersion} / msaa ${s.msaaSamples}")
        results.put("mem_gl_$n", memorySnapshot())
        glLine("canvas $n", s)
        ed.replaceDocument(InfiniteDocument())
        System.gc()
        delay(500)
    }

    private fun gestureMatrix(): List<Triple<String, Double, String>> = listOf(
        Triple("pan @ fit-width", 1.0, "pan"),
        Triple("pan @ 3x", 3.0, "pan"),
        Triple("pinch", 1.0, "pinch"),
    )

    /**
     * Two real fingers dispatched into [view]. A pan drags both up (then down) in repeating strokes;
     * a pinch swings the gap between them. Frame-to-frame gaps are the measurement.
     */
    private suspend fun gesture(
        kind: String,
        view: View,
        seconds: Double,
        viewState: () -> Triple<Double, Double, Double>,
        perFrame: () -> Unit,
    ): JSONObject {
        val w = view.width.toFloat()
        val h = view.height.toFloat()
        val cx = w / 2f
        val synth = FingerSynth(view)
        val intervals = ArrayList<Double>()
        val before = viewState()
        val start = awaitFrame()
        val warm = 500_000_000L
        val end = start + warm + (seconds * 1e9).toLong()
        var prev = start
        var t = start
        var cycle = -1
        while (t < end) {
            t = awaitFrame()
            val el = (t - start) / 1e9
            if (kind == "pan") {
                val period = 0.7
                val c = (el / period).toInt()
                val local = ((el % period) / period).toFloat()
                val up = (c / 6) % 2 == 0
                val y0 = if (up) h * 0.75f else h * 0.25f
                val y1 = if (up) h * 0.25f else h * 0.75f
                val y = y0 + (y1 - y0) * local
                if (c != cycle) {
                    synth.up()
                    cycle = c
                    synth.down(cx - 150f, y, cx + 150f, y)
                } else {
                    synth.move(cx - 150f, y, cx + 150f, y)
                }
            } else {
                val sep = (500.0 + 350.0 * sin(2.0 * PI * el / 2.4)).toFloat()
                if (cycle < 0) {
                    cycle = 0
                    synth.down(cx - sep / 2f, h / 2f, cx + sep / 2f, h / 2f)
                } else {
                    synth.move(cx - sep / 2f, h / 2f, cx + sep / 2f, h / 2f)
                }
            }
            if (t > start + warm) {
                intervals.add((t - prev) / 1e6)
                perFrame()
            }
            prev = t
        }
        synth.up()
        val after = viewState()
        val o = summarize(intervals, refreshMs)
        o.put("zoom_before", round(before.first)).put("zoom_after", round(after.first))
        o.put("scrollY_before", round(before.third)).put("scrollY_after", round(after.third))
        return o
    }

    /** Synthetic pen strokes: how long the real touch path takes per event, and how the scene copes. */
    private suspend fun wetInk(
        name: String,
        n: Int,
        view: View,
        countStrokes: () -> Int,
        gl: InfiniteEditor?,
    ): JSONObject {
        val w = view.width.toFloat()
        val h = view.height.toFloat()
        val pen = PenSynth(view)
        val dispatchMs = ArrayList<Double>()
        val frames = ArrayList<Double>()
        val before = countStrokes()
        var prev = awaitFrame()
        val glSamples = ArrayList<GlStats>()
        for (s in 0 until 6) {
            val y0 = h * 0.35f + s * 70f
            val x0 = w * 0.25f
            dispatchMs.add(pen.down(x0, y0) / 1e6)
            for (f in 1..60) {
                val t = awaitFrame()
                frames.add((t - prev) / 1e6)
                prev = t
                for (k in 0 until 2) {
                    val u = (f * 2 + k) / 120f
                    dispatchMs.add(pen.move(x0 + u * w * 0.5f, y0 + 40f * sin(u * 4f * PI.toFloat())) / 1e6)
                }
                if (gl != null && f % 20 == 0) glSamples.add(gl.view.stats)
            }
            dispatchMs.add(pen.up(x0 + w * 0.5f, y0) / 1e6)
            awaitFrame()
            awaitFrame()
        }
        delay(600)
        val o = JSONObject()
        o.put("renderer", name).put("strokes", n).put("gesture", "wet-ink (6 pen strokes, dispatch time per event)")
        o.put("dispatch", summarize(dispatchMs, refreshMs))
        o.put("frames", summarize(frames, refreshMs))
        o.put("strokes_committed", countStrokes() - before)
        if (glSamples.isNotEmpty()) o.put("gl_work_ms_mean", round(glSamples.map { it.frameMs }.average()))
        val d = o.getJSONObject("dispatch")
        line("$name wet-ink  per-event p50 ${d.optDouble("p50_ms")} p95 ${d.optDouble("p95_ms")} max ${d.optDouble("max_ms")} ms; " +
            "frames >1.5x ${o.getJSONObject("frames").optDouble("over_1_5_refresh_pct")}%; committed ${o.getInt("strokes_committed")}/6")
        return o
    }

    // --- PDF page regions as the texture a GL tile would need ---

    private suspend fun pdfRegions() {
        line("--- PDF page regions (what a GL tile of a PDF page would cost) ---")
        val file = withContext(Dispatchers.Default) { makePdf() }
        val src = PdfSource.create(this, file) ?: run { line("could not open generated PDF"); return }
        val arr = JSONArray()
        results.put("pdf_region", arr)
        val vw = stage.width.coerceAtLeast(1000)
        val vh = stage.height.coerceAtLeast(700)
        for (z in listOf(1.0, 3.0, 6.0)) {
            val fullW = (vw * z).toInt()
            val fullH = (fullW * 842.0 / 595.0).toInt()
            val left = ((fullW - vw) / 2).coerceAtLeast(0)
            val top = ((fullH - vh) / 2).coerceAtLeast(0)
            val times = ArrayList<Double>()
            withContext(Dispatchers.Default) {
                repeat(3) { src.renderRegion(0, fullW, fullH, left, top, vw, vh)?.bitmap?.recycle() }
                repeat(15) {
                    val t0 = System.nanoTime()
                    val s = src.renderRegion(0, fullW, fullH, left, top, vw, vh)
                    times.add((System.nanoTime() - t0) / 1e6)
                    s?.bitmap?.recycle()
                }
            }
            val sm = summarize(times, refreshMs).put("zoom_vs_fit", z).put("region_px", "${vw}x$vh")
            sm.put("bitmap_mb", round(vw.toDouble() * vh * 4 / (1024 * 1024)))
            arr.put(sm)
            line("pdf region @${z}x fit  ${vw}x$vh  p50 ${sm.optDouble("p50_ms")} p95 ${sm.optDouble("p95_ms")} ms, ${sm.optDouble("bitmap_mb")} MB")
        }
        src.close()
        file.delete()
    }

    private fun makePdf(pages: Int = 1): File {
        val doc = PdfDocument()
        for (pageNo in 0 until pages) {
        val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNo + 1).create())
        val c = page.canvas
        val rnd = Random(7 + pageNo)
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 11f }
        for (i in 0 until 55) c.drawText("Line $i  The quick brown fox jumps over the lazy dog; 0123456789 ({[]}) ~!@#", 40f, 40f + i * 14f, ink)
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 0.6f; color = Color.DKGRAY }
        for (i in 0 until 900) {
            val p = Path()
            p.moveTo(rnd.nextFloat() * 595f, rnd.nextFloat() * 842f)
            p.cubicTo(rnd.nextFloat() * 595f, rnd.nextFloat() * 842f, rnd.nextFloat() * 595f, rnd.nextFloat() * 842f, rnd.nextFloat() * 595f, rnd.nextFloat() * 842f)
            c.drawPath(p, line)
        }
        doc.finishPage(page)
        }
        val f = File(cacheDir, "bench.pdf")
        FileOutputStream(f).use { doc.writeTo(it) }
        doc.close()
        return f
    }

    // --- text and equations as textures ---

    private suspend fun textTextures() {
        line("--- text boxes and equations as textures ---")
        var waited = 0
        while (!MathRendering.ready() && waited++ < 50) delay(100)
        line("math engine ready: ${MathRendering.ready()}")
        val measurer = AndroidTextMeasurer()
        val words = "ink canvas notes page render stroke pen zoom layer image text frame region scroll cache texture".split(' ')
        val rnd = Random(3)
        fun para() = (0 until 60).joinToString(" ") { words[rnd.nextInt(words.size)] }
        val dir = File(cacheDir, "benchtex").apply { mkdirs() }
        val arr = JSONArray()
        results.put("text_textures", arr)
        for (scale in listOf(1.0f, 3.0f)) {
            val textMs = ArrayList<Double>()
            var bytes = 0L
            withContext(Dispatchers.Default) {
                repeat(60) {
                    val item = TextItem(Pt(0.0, 0.0), width = 600.0, text = para(), measurer = measurer)
                    val b = item.bounds()
                    val bw = (600 * scale).toInt()
                    val bh = (maxOf(b.h, 120.0) * scale).toInt()
                    val t0 = System.nanoTime()
                    val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                    val cv = Canvas(bmp)
                    cv.scale(scale, scale)
                    item.paint(AndroidRenderer(cv))
                    textMs.add((System.nanoTime() - t0) / 1e6)
                    bytes += bmp.allocationByteCount
                    bmp.recycle()
                }
            }
            val mathMs = ArrayList<Double>()
            withContext(Dispatchers.Default) {
                repeat(30) { iter ->
                    val bmp = Bitmap.createBitmap((700 * scale).toInt(), (120 * scale).toInt(), Bitmap.Config.ARGB_8888)
                    val cv = Canvas(bmp)
                    cv.scale(scale, scale)
                    val t0 = System.nanoTime()
                    MathRendering.draw(cv, "\\int_0^{$iter} e^{-x^2}\\,dx=\\frac{\\sqrt{\\pi}}{2}", 10.0, 70.0, 24.0, Rgba(0, 0, 0, 255), true)
                    mathMs.add((System.nanoTime() - t0) / 1e6)
                    bmp.recycle()
                }
            }
            val o = JSONObject().put("scale", scale.toDouble())
                .put("text_box", summarize(textMs, refreshMs)).put("equation", summarize(mathMs, refreshMs))
                .put("text_bitmap_kb_avg", bytes / 60 / 1024)
            arr.put(o)
            line("render @${scale}x  text box p50 ${o.getJSONObject("text_box").optDouble("p50_ms")} ms (${bytes / 60 / 1024} KB each)  " +
                "equation p50 ${o.getJSONObject("equation").optDouble("p50_ms")} ms")
        }
        // 200 of those as GL textures among 10k strokes: does the frame time hold?
        val files = withContext(Dispatchers.Default) {
            (0 until 200).map { i ->
                val item = TextItem(Pt(0.0, 0.0), width = 600.0, text = para(), measurer = measurer)
                val h = maxOf(item.bounds().h, 120.0)
                val bmp = Bitmap.createBitmap(1200, (h * 2).toInt(), Bitmap.Config.ARGB_8888)
                val cv = Canvas(bmp)
                cv.scale(2f, 2f)
                item.paint(AndroidRenderer(cv))
                val f = File(dir, "t$i.png")
                FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                val size = Triple(f, 600, h.toInt())
                bmp.recycle()
                size
            }
        }
        val ed = canvas ?: InfiniteEditor(this).also { canvas = it }
        val doc = withContext(Dispatchers.Default) {
            BenchData.canvasDocument(10_000).also { d ->
                val r = Random(9)
                d.addAll(files.map { (f, w, h) ->
                    ImageItem(
                        ImageData(f, w * 2, h * 2),
                        Rect(r.nextDouble() * 500.0, r.nextDouble() * 10 * BenchData.page.second, w.toDouble(), h.toDouble()),
                    )
                })
            }
        }
        mount(ed.surfaces)
        ed.replaceDocument(doc)
        delay(2000)
        val vp = ed.viewport
        vp.zoom = vp.widthPx / BenchData.page.first
        vp.scrollX = 0.0
        vp.scrollY = 0.0
        vp.zoomAround(vp.widthPx / 2.0, vp.heightPx / 2.0, vp.zoom * 3)
        ed.view.publish()
        delay(1200)
        val samples = ArrayList<GlStats>()
        var tick = 0
        val r = gesture("pan", ed.view, 4.0, { Triple(vp.zoom, vp.scrollX, vp.scrollY) }) {
            if (++tick % 20 == 0) samples.add(ed.view.stats)
        }
        r.put("renderer", "gl").put("scene", "10k strokes + 200 text-box textures").put("gesture", "pan @ 3x")
        if (samples.isNotEmpty()) {
            r.put("gl_work_ms_mean", round(samples.map { it.frameMs }.average()))
            r.put("gl_texture_mb", samples.last().textureBytes / (1024 * 1024))
        }
        results.put("gl_with_text_textures", r)
        line("canvas 10k + 200 text textures  pan @3x  p50 ${r.optDouble("p50_ms")} p95 ${r.optDouble("p95_ms")} max ${r.optDouble("max_ms")} ms, " +
            "gl work ${r.optDouble("gl_work_ms_mean")} ms, textures ${r.optDouble("gl_texture_mb")} MB")
        ed.replaceDocument(InfiniteDocument())
        dir.deleteRecursively()
    }

    // --- cold open and memory ---

    private suspend fun coldOpen() {
        line("--- cold open and memory ---")
        val o = JSONObject()
        results.put("cold_open", o)
        val imageCodec = AndroidImageCodec()
        val ed = paged ?: Editor(this).also { paged = it }
        val pdoc = withContext(Dispatchers.Default) { BenchData.pagedDocument(20_000, perPage = 100) }
        val pf = File(cacheDir, "bench.xnote")
        val codec = DocumentCodec(imageCodec, AndroidTextMeasurer())
        val tw = SystemClock.elapsedRealtime()
        withContext(Dispatchers.IO) { FileOutputStream(pf).use { codec.write(pdoc, it) } }
        val writeMs = SystemClock.elapsedRealtime() - tw
        val tr = SystemClock.elapsedRealtime()
        val back = withContext(Dispatchers.IO) { FileInputStream(pf).use { codec.read(it) } }
        val readMs = SystemClock.elapsedRealtime() - tr
        mount(ed.surfaces)
        val t1 = SystemClock.elapsedRealtime()
        ed.state.document = back
        ed.state.invalidateAllCaches()
        ed.state.relayout()
        ed.state.fitWidth()
        awaitFrame()
        awaitFrame()
        val firstFrame = SystemClock.elapsedRealtime() - t1
        delay(2000)
        val po = JSONObject().put("pages", back.pages.size).put("strokes", 20_000).put("file_mb", round(pf.length() / 1048576.0))
            .put("write_ms", writeMs).put("read_ms", readMs).put("first_frame_ms", firstFrame).put("mem", memorySnapshot())
        o.put("paged", po)
        line("paged 200 pages / 20k strokes: file ${po.optDouble("file_mb")} MB, write $writeMs ms, read $readMs ms, first frame $firstFrame ms, mem ${po.getJSONObject("mem")}")
        ed.state.document = Document.blank()
        ed.state.invalidateAllCaches()
        ed.state.relayout()
        pf.delete()
        System.gc()
        delay(500)

        val cdoc = withContext(Dispatchers.Default) { BenchData.canvasDocument(30_000) }
        val cf = File(cacheDir, "bench.xcanvas")
        val ccodec = CanvasCodec(imageCodec)
        val cw = SystemClock.elapsedRealtime()
        withContext(Dispatchers.IO) { FileOutputStream(cf).use { ccodec.write(cdoc, it) } }
        val cwMs = SystemClock.elapsedRealtime() - cw
        val cr = SystemClock.elapsedRealtime()
        val cback = withContext(Dispatchers.IO) { FileInputStream(cf).use { ccodec.read(it) } }
        val crMs = SystemClock.elapsedRealtime() - cr
        val ce = canvas ?: InfiniteEditor(this).also { canvas = it }
        mount(ce.surfaces)
        val c1 = SystemClock.elapsedRealtime()
        ce.replaceDocument(cback)
        awaitFrame()
        awaitFrame()
        val cFirst = SystemClock.elapsedRealtime() - c1
        delay(2000)
        val st = ce.view.stats
        val co = JSONObject().put("strokes", 30_000).put("file_mb", round(cf.length() / 1048576.0))
            .put("write_ms", cwMs).put("read_ms", crMs).put("load_and_first_frame_ms", cFirst).put("mem", memorySnapshot())
            .put("gpu_geometry_mb", st.geometryBytes / (1024 * 1024))
        o.put("canvas", co)
        line("canvas 30k strokes: file ${co.optDouble("file_mb")} MB, write $cwMs ms, read $crMs ms, load+first frame $cFirst ms, " +
            "gpu geometry ${co.optLong("gpu_geometry_mb")} MB, mem ${co.getJSONObject("mem")}")
        ce.replaceDocument(InfiniteDocument())
        cf.delete()
    }

    // --- config grid ---

    /** Wet ink on the GL canvas with the front-buffer pad on and off. */
    private suspend fun padComparison() {
        line("--- GL wet ink: front-buffer pad on vs off (10k strokes) ---")
        Tuning.reset()
        val ed = canvas ?: InfiniteEditor(this).also { canvas = it }
        val doc = withContext(Dispatchers.Default) { BenchData.canvasDocument(10_000) }
        mount(ed.surfaces)
        val vp = ed.viewport
        vp.zoom = vp.widthPx / BenchData.page.first
        vp.scrollX = 0.0
        vp.scrollY = 0.0
        ed.replaceDocument(doc)
        delay(2000)
        ed.armTool(Tool.PEN)
        val arr = JSONArray()
        results.put("pad_comparison", arr)
        val original = ed.pad.frontBuffering
        for (on in listOf(true, false)) {
            ed.pad.frontBuffering = on
            delay(500)
            val o = wetInk("gl", 10_000, ed.view, { ed.document.items.size }, ed)
            o.put("pad", if (on) "on" else "off")
            arr.put(o)
            line("pad ${if (on) "on" else "off"} done")
        }
        ed.pad.frontBuffering = original
        ed.replaceDocument(InfiniteDocument())
        System.gc()
        delay(500)
    }

    // --- Decision suite: Skia vs GL pan/zoom, PDF pan, hybrid tiles ---

    private fun startDecision() {
        if (job?.isActive == true) return
        text.setLength(0)
        reportView.text = ""
        beginAutosave()
        job = scope.launch {
            panel.visibility = View.INVISIBLE
            try {
                decisionSuite()
            } catch (t: Throwable) {
                line("FAILED: ${t.stackTraceToString().take(900)}")
            }
            Tuning.reset()
            panel.visibility = View.VISIBLE
            line("done")
        }
    }

    private suspend fun decisionSuite() {
        results.put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
        results.put("sdk", Build.VERSION.SDK_INT)
        results.put("refresh_ms", round(refreshMs))
        line("${Build.MODEL}  refresh ${round(1000.0 / refreshMs)} Hz  (decision suite: shipped optimizations, minimap off)")
        // The shipped defaults, so GL is measured as the app would run it.
        Tuning.apply(sharedRails = true, simplify = true, coarseCaps = true, lod = false, mergeDraws = true, staticBuffers = true)
        try { compareSuite() } catch (t: Throwable) { line("compare failed: ${t.stackTraceToString().take(600)}") }
        autosave()
        try { pdfPan() } catch (t: Throwable) { line("pdf pan failed: ${t.stackTraceToString().take(600)}") }
        autosave()
        try { hybridTiles() } catch (t: Throwable) { line("hybrid failed: ${t.stackTraceToString().take(900)}") }
        saveJson()
    }

    /** Paged (Skia) against canvas (GL) on the same strokes, the same two-finger pan and pinch. */
    private suspend fun compareSuite() {
        line("=== Skia (paged) vs GL (canvas): pan / pinch / wet ink ===")
        val rows = JSONArray()
        results.put("compare", rows)
        for (n in listOf(1_000, 10_000, 30_000, 60_000)) {
            line("--- $n strokes ---")
            try { pagedScenario(n, rows) } catch (e: OutOfMemoryError) { line("paged $n: OUT OF MEMORY") }
            autosave()
            try { canvasScenario(n, rows) } catch (e: OutOfMemoryError) { line("canvas $n: OUT OF MEMORY") }
            autosave()
        }
    }

    /** The paged editor panning a PDF-backed note, with and without ink on top. */
    private suspend fun pdfPan() {
        line("=== paged PDF pan (generated 20-page PDF: text + 900 vector paths per page) ===")
        val rows = JSONArray()
        results.put("pdf_pan", rows)
        val pages = 20
        val file = withContext(Dispatchers.Default) { makePdf(pages) }
        for ((label, ink) in listOf("pdf only" to 0, "pdf + 10k ink" to 10_000)) {
            val ed = paged ?: Editor(this).also { paged = it }
            val doc = withContext(Dispatchers.Default) {
                val d = if (ink == 0) Document.blank(count = pages) else BenchData.pagedDocument(ink, perPage = ink / pages)
                d.pdfFile = file
                d.pages.forEachIndexed { i, p -> p.pdfPage = i }
                d
            }
            mount(ed.surfaces)
            val st = ed.state
            val tLoad = SystemClock.elapsedRealtime()
            st.document = doc
            ed.rebuildPdfSource()
            st.invalidateAllCaches()
            st.relayout()
            st.fitWidth()
            awaitFrame()
            val loadMs = SystemClock.elapsedRealtime() - tLoad
            line("pdf pan [$label]: loaded in $loadMs ms")
            delay(3000)
            for ((gestureLabel, mul, kind) in gestureMatrix()) {
                st.scrollY = 0.0
                st.clampScroll()
                st.fitWidth()
                if (mul != 1.0) st.setZoomAnchored(st.clearCenter(), st.zoom * mul)
                delay(1500)
                var blurry = 0
                var frames = 0
                val r = gesture(kind, ed.view, 4.0, { Triple(st.zoom, st.scrollX, st.scrollY) }) {
                    frames++
                    if (st.isPastResolutionCap() && st.sharpViewportBlit() == null) blurry++
                }
                r.put("scenario", label).put("gesture", gestureLabel).put("load_ms", loadMs)
                r.put("blurry_frame_pct", if (frames == 0) 0.0 else round(100.0 * blurry / frames))
                rows.put(r)
                autosave()
                line("pdf pan [$label] $gestureLabel  p50 ${r.optDouble("p50_ms")} p95 ${r.optDouble("p95_ms")} max ${r.optDouble("max_ms")} ms, " +
                    ">1.5x ${r.optDouble("over_1_5_refresh_pct")}%, blurry ${r.optDouble("blurry_frame_pct")}%")
                delay(600)
            }
            results.put("mem_pdf_pan_${ink}", memorySnapshot())
            st.document = Document.blank()
            ed.rebuildPdfSource()
            st.invalidateAllCaches()
            st.relayout()
            System.gc()
            delay(700)
        }
        file.delete()
    }

    /**
     * What the hybrid would cost, measured in pieces and not wired into either editor: Skia drawing
     * screen-resolution tiles of dense ink, then a GL view compositing a grid of such tiles under a
     * pan, then streaming new tiles in while it pans.
     */
    private suspend fun hybridTiles() {
        line("=== hybrid: Skia tile render, GL composite, tile memory ===")
        val rows = JSONArray()
        results.put("hybrid", rows)
        val doc = withContext(Dispatchers.Default) { BenchData.canvasDocument(30_000) }
        val items = ArrayList(doc.items)
        val sources = HashMap<Int, Bitmap>()
        for (tile in listOf(512, 1024)) {
            for (z in listOf(0.5, 1.0, 2.0, 4.0)) {
                val renderMs = ArrayList<Double>()
                val counts = ArrayList<Double>()
                withContext(Dispatchers.Default) {
                    val bmp = Bitmap.createBitmap(tile, tile, Bitmap.Config.ARGB_8888)
                    val span = tile / z
                    for (k in 0 until 8) {
                        val x0 = 100.0
                        val y0 = 3000.0 + k * 2500.0
                        val area = Rect(x0, y0, span, span)
                        // The visible set is picked outside the timing: a real tiler would use an index.
                        val hit = items.filter { it.bounds().intersects(area) }
                        fun renderOnce(): Double {
                            val t0 = System.nanoTime()
                            bmp.eraseColor(Color.WHITE)
                            val cv = Canvas(bmp)
                            cv.scale(z.toFloat(), z.toFloat())
                            cv.translate(-x0.toFloat(), -y0.toFloat())
                            val r = AndroidRenderer(cv)
                            for (item in hit) item.paint(r)
                            return (System.nanoTime() - t0) / 1e6
                        }
                        renderOnce() // warm: geometry built, paints primed
                        renderMs.add((0 until 3).map { renderOnce() }.average())
                        counts.add(hit.size.toDouble())
                    }
                    if (z == 1.0) sources[tile] = bmp else bmp.recycle()
                }
                val row = JSONObject().put("kind", "tile_render").put("tile_px", tile).put("zoom", z)
                row.put("strokes_in_tile_mean", round(counts.average())).put("strokes_in_tile_max", (counts.maxOrNull() ?: 0.0))
                row.put("render", summarize(renderMs, refreshMs))
                row.put("tile_mb", round(tile.toDouble() * tile * 4 / (1024 * 1024)))
                rows.put(row)
                autosave()
                line("tile ${tile}px @${z}x  ~${round(counts.average())} strokes  render p50 ${row.getJSONObject("render").optDouble("p50_ms")} " +
                    "p95 ${row.getJSONObject("render").optDouble("p95_ms")} ms, ${row.optDouble("tile_mb")} MB")
            }
        }
        items.forEach { (it as? Stroke)?.releaseGeometry() }
        val vw = stage.width.coerceAtLeast(1000)
        val vh = stage.height.coerceAtLeast(700)
        for (tile in listOf(512, 1024)) {
            val src = sources[tile] ?: continue
            val cols = (vw + tile - 1) / tile + 1
            val rowsN = (vh + tile - 1) / tile + 1
            val v = TileCompositeView(this)
            mount(v)
            v.tilePx = tile
            v.cols = cols
            v.rows = rowsN
            v.textureCount = cols * rowsN
            v.source = src
            v.rebuild = true
            delay(2500)
            val first = synchronized(v.uploadMs) { ArrayList(v.uploadMs) }
            for ((phase, uploads) in listOf("pan only" to 0, "pan + 2 tile uploads per frame" to 2)) {
                v.uploadsPerFrame = uploads
                v.frameMs.clear()
                v.uploadMs.clear()
                val intervals = ArrayList<Double>()
                val warm = 500_000_000L
                val start = awaitFrame()
                var prev = start
                var t = start
                while (t < start + warm + 4_000_000_000L) {
                    t = awaitFrame()
                    val el = (t - start) / 1e9
                    v.panX = (900.0 * sin(2.0 * PI * el / 3.0)).toFloat()
                    v.panY = (500.0 * sin(2.0 * PI * el / 2.0)).toFloat()
                    if (t > start + warm) intervals.add((t - prev) / 1e6)
                    prev = t
                }
                val row = JSONObject().put("kind", "composite").put("tile_px", tile).put("phase", phase)
                row.put("tiles_resident", v.textureCount).put("texture_mb", round(v.textureCount.toDouble() * tile * tile * 4 / (1024 * 1024)))
                row.put("tiles_drawn", v.drawnTiles).put("gl_renderer", v.rendererName)
                row.put("first_upload_ms", summarize(first, refreshMs))
                row.put("frame_intervals", summarize(intervals, refreshMs))
                row.put("gl_frame_ms", summarize(synchronized(v.frameMs) { ArrayList(v.frameMs) }, refreshMs))
                if (uploads > 0) row.put("streamed_upload_ms", summarize(synchronized(v.uploadMs) { ArrayList(v.uploadMs) }, refreshMs))
                rows.put(row)
                autosave()
                line("composite ${tile}px [$phase]  ${v.textureCount} tiles ${row.optDouble("texture_mb")} MB  " +
                    "frame p50 ${row.getJSONObject("frame_intervals").optDouble("p50_ms")} p95 ${row.getJSONObject("frame_intervals").optDouble("p95_ms")} ms, " +
                    ">1.5x ${row.getJSONObject("frame_intervals").optDouble("over_1_5_refresh_pct")}%, gl work p50 ${row.getJSONObject("gl_frame_ms").optDouble("p50_ms")} ms")
            }
            v.onPause()
            stage.removeAllViews()
        }
        sources.values.forEach { it.recycle() }
    }

    // --- Memory investigation: where does the GL canvas's process memory go ---

    /** Round-trips every .xnote and .xcanvas under [tree] through the unified .xdoc format, writing nothing to it. */
    private fun startGlSmoke() {
        if (job?.isActive == true) return
        text.setLength(0)
        reportView.text = ""
        beginAutosave()
        job = scope.launch {
            panel.visibility = View.INVISIBLE
            try {
                glSmoke()
            } catch (t: Throwable) {
                line("FAILED: ${t.stackTraceToString().take(900)}")
            }
            panel.visibility = View.VISIBLE
            line("done")
        }
    }

    /** End-to-end check of GL ink on paged notes: every item type, the text-tool and ruler routes, select, delete, undo, pages. */
    private suspend fun glSmoke() {
        line("--- GL paged smoke test ---")
        results.put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
        val rows = JSONArray()
        results.put("gl_smoke", rows)
        var failed = 0
        fun row(step: String, ok: Boolean, detail: String) {
            if (!ok) failed++
            rows.put(JSONObject().put("step", step).put("ok", ok).put("detail", detail))
            line("${if (ok) "PASS" else "FAIL"}  $step  $detail")
        }
        val ed = paged ?: Editor(this).also { paged = it }
        mount(ed.surfaces)
        val st = ed.state
        val doc = withContext(Dispatchers.Default) { BenchData.pagedDocument(300, perPage = 100) }
        st.document = doc
        st.invalidateAllCaches()
        st.relayout()
        st.fitWidth()
        awaitFrame()
        val host = ed.setGlInk(true)
        if (host == null) { row("GL ink on", false, "host not created"); return }
        suspend fun settle() { delay(900); awaitFrame() }
        settle()
        settle()
        fun c() = host.counts()
        row("GL ink on", st.glBridge != null && ed.view.glMode, c().toString())
        row("strokes drawn", c().vectors > 0, "vectors visible ${c().vectors}")

        val page = st.document.pages[0]
        val pt = com.xnotes.core.geometry.Pt(60.0, 60.0)

        // 1. text the way the text tool makes it
        var before = c()
        val n0 = page.items.size
        ed.controller.debugCreateText(0, pt, "text tool box")
        settle()
        var after = c()
        row("text tool: box added to page", page.items.size == n0 + 1, "items $n0 -> ${page.items.size}")
        row("text tool: filed in GL", after.textFiled > before.textFiled, "filed ${before.textFiled} -> ${after.textFiled}")
        row("text tool: texture made", after.textTextures > before.textTextures && after.textFailures == 0, after.toString())
        row("text tool: drawn", after.texts > before.texts, "text quads ${before.texts} -> ${after.texts}")

        // 2. text the way the ruler saves a reading
        before = c()
        val ruler = com.xnotes.core.model.TextItem(
            com.xnotes.core.geometry.Pt(60.0, 220.0), 300.0, 0.0, "ruler box",
            com.xnotes.core.model.TextItem.DEFAULT_COLOR, 13.0, com.xnotes.core.pal.FontFace.MONO,
            com.xnotes.platform.AndroidTextMeasurer(),
        )
        page.items.add(ruler)
        st.appendToCache(page, ruler)
        settle()
        after = c()
        row("ruler text: drawn", after.texts > before.texts, "text quads ${before.texts} -> ${after.texts}; ${after}")

        // 3. an image
        before = c()
        val png = java.io.ByteArrayOutputStream().also { out ->
            val bmp = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(bmp).drawColor(android.graphics.Color.rgb(200, 60, 60))
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        }.toByteArray()
        val nImg = page.items.size
        ed.insertImageAt(png, st.fromPageSpace(0, com.xnotes.core.geometry.Pt(200.0, 400.0)))
        settle()
        after = c()
        row("image: added to page", page.items.size > nImg, "items $nImg -> ${page.items.size}")
        row("image: drawn", after.images > before.images, "image quads ${before.images} -> ${after.images}")

        // 4. select all, then clear: lifted items must come back to GL
        val full = c()
        ed.controller.selectAll()
        settle()
        val lifted = c()
        line("      filed ${host.filedCount()}, sel ${ed.controller.hasSelection}, selected: vec ${full.vectors}->${lifted.vectors} img ${full.images}->${lifted.images} txt ${full.texts}->${lifted.texts}")
        ed.controller.clearSelection()
        settle()
        after = c()
        row("select then clear: everything back", after.vectors >= full.vectors && after.images >= full.images && after.texts >= full.texts,
            "vec ${after.vectors}/${full.vectors} img ${after.images}/${full.images} txt ${after.texts}/${full.texts}")

        // 5. delete the selection, undo it
        ed.controller.selectAll()
        settle()
        ed.controller.deleteSelection()
        settle()
        val gone = c()
        row("delete: page emptied and GL follows", page.items.isEmpty() && gone.vectors + gone.images + gone.texts < full.vectors + full.images + full.texts,
            "items ${page.items.size}, filed ${host.filedCount()}, sel ${ed.controller.hasSelection}, ${gone}")
        ed.undo()
        settle()
        after = c()
        row("undo: back in GL", page.items.isNotEmpty() && after.vectors >= full.vectors && after.texts >= full.texts,
            "items ${page.items.size}, vec ${after.vectors} img ${after.images} txt ${after.texts}")

        // 6. pages
        st.scrollY = 0.0; st.clampScroll(); st.fitWidth(); settle()
        val pages0 = st.document.pages.size
        ed.insertPageAfter(0)
        settle()
        row("page added", st.document.pages.size == pages0 + 1 && c().vectors + c().texts > 0, c().toString())
        ed.deletePages(listOf(1))
        settle()
        row("page deleted", st.document.pages.size == pages0 && c().vectors > 0, c().toString())

        // 7. off and on again
        st.scrollY = 0.0; st.clampScroll(); st.fitWidth()
        ed.setGlInk(false)
        awaitFrame()
        row("GL off", st.glBridge == null && !ed.view.glMode, "")
        val host2 = ed.setGlInk(true)
        settle()
        settle()
        val back = host2?.counts()
        row("GL on again", back != null && back.vectors > 0 && back.texts > 0, back?.toString() ?: "no host")

        ed.setGlInk(false)
        st.document = Document.blank()
        st.invalidateAllCaches()
        st.relayout()
        results.put("gl_smoke_failed", failed)
        line(if (failed == 0) "ALL PASSED" else "$failed FAILED")
    }

    private fun startNoteCheck(tree: android.net.Uri) {
        if (job?.isActive == true) return
        text.setLength(0)
        reportView.text = ""
        beginAutosave()
        job = scope.launch {
            try {
                val r = withContext(Dispatchers.IO) { NoteCheck(this@BenchActivity) { s -> runOnUiThread { line(s) } }.run(tree) }
                results.put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                results.put("note_check", r)
            } catch (t: Throwable) {
                line("FAILED: ${t.stackTraceToString().take(900)}")
            }
            line("done")
        }
    }

    private fun startMemory() {
        if (job?.isActive == true) return
        text.setLength(0)
        reportView.text = ""
        beginAutosave()
        job = scope.launch {
            panel.visibility = View.INVISIBLE
            try {
                memorySuite()
            } catch (t: Throwable) {
                line("FAILED: ${t.stackTraceToString().take(900)}")
            }
            Tuning.reset()
            panel.visibility = View.VISIBLE
            line("done")
        }
    }

    /**
     * The GL canvas at the shipped settings, loaded at four sizes, with the OS's breakdown of the
     * process taken empty, with the document built but not shown, loaded, and after being released.
     * The empty reading before each size shows what earlier sizes left behind.
     */
    private suspend fun memorySuite() {
        results.put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
        results.put("heap_limit_mb", Runtime.getRuntime().maxMemory() / 1048576)
        line("memory: GL canvas, shipped defaults, serial load. Every stage reads the OS's own account of the process.")
        Tuning.apply(sharedRails = true, simplify = true, coarseCaps = true, lod = false, mergeDraws = true, staticBuffers = true)
        val rows = JSONArray()
        results.put("memory", rows)
        val ed = canvas ?: InfiniteEditor(this).also { canvas = it }
        mount(ed.surfaces)
        val vp = ed.viewport
        vp.zoom = vp.widthPx / BenchData.page.first
        vp.scrollX = 0.0
        vp.scrollY = 0.0
        ed.replaceDocument(InfiniteDocument())
        delay(1500)
        rows.put(memoryStage("start", 0, ed))
        for (n in listOf(10_000, 30_000, 60_000, 100_000)) {
            var doc: InfiniteDocument? = withContext(Dispatchers.Default) { BenchData.canvasDocument(n) }
            rows.put(memoryStage("doc built, scene empty", n, ed))
            val t0 = SystemClock.elapsedRealtime()
            ed.replaceDocument(doc!!)
            doc = null
            awaitFrame()
            delay(3000)
            rows.put(memoryStage("loaded (${SystemClock.elapsedRealtime() - t0} ms)", n, ed))
            ed.replaceDocument(InfiniteDocument())
            delay(2000)
            rows.put(memoryStage("released", n, ed))
            autosave()
        }
        saveJson()
    }

    private suspend fun memoryStage(label: String, n: Int, ed: InfiniteEditor): JSONObject {
        repeat(3) {
            System.gc()
            System.runFinalization()
            delay(150)
        }
        ed.view.publish()
        delay(500)
        val s = ed.view.stats
        val o = withContext(Dispatchers.Default) { memoryBreakdown() }
        o.put("stage", label).put("strokes", n)
        o.put("gl_geometry_capacity_mb", s.geometryBytes / (1024 * 1024))
        o.put("gl_geometry_live_mb", s.liveGeometryBytes / (1024 * 1024))
        o.put("gl_texture_mb", s.textureBytes / (1024 * 1024))
        o.put("scene_items", s.items)
        val sm = o.getJSONObject("summary_mb")
        val top = o.optJSONArray("top_mappings")
        val topText = (0 until minOf(4, top?.length() ?: 0)).joinToString("; ") {
            val m = top!!.getJSONObject(it)
            "${m.getString("mapping").takeLast(34)} ${m.getLong("pss_mb")}"
        }
        line("mem $label n=$n  pss ${o.getLong("total_pss_mb")} MB  java ${sm.optLong("java-heap")} native ${sm.optLong("native-heap")} " +
            "graphics ${sm.optLong("graphics")} other ${sm.optLong("private-other")} system ${sm.optLong("system")}  " +
            "geom cap ${o.getLong("gl_geometry_capacity_mb")} live ${o.getLong("gl_geometry_live_mb")}  | $topText")
        return o
    }

    // --- Java heap ---

    private fun usedMb(): Double {
        val rt = Runtime.getRuntime()
        return (rt.totalMemory() - rt.freeMemory()) / 1048576.0
    }

    /** Heap in use once the collector has had several goes at it, so it counts what is really held. */
    private suspend fun settledMb(): Double {
        repeat(3) {
            System.gc()
            System.runFinalization()
            delay(150)
        }
        return round(usedMb())
    }

    /** Runs [block] while a thread samples the heap, and returns the highest reading in [peak]. */
    private suspend fun <T> withPeak(peak: DoubleArray, block: suspend () -> T): T {
        val sampler = scope.launch(Dispatchers.Default) {
            while (true) {
                peak[0] = maxOf(peak[0], usedMb())
                delay(15)
            }
        }
        try {
            return block()
        } finally {
            sampler.cancel()
        }
    }

    private fun startHeap() {
        if (job?.isActive == true) return
        text.setLength(0)
        reportView.text = ""
        beginAutosave()
        job = scope.launch {
            panel.visibility = View.INVISIBLE
            try {
                heapSuite()
            } catch (t: Throwable) {
                line("FAILED: ${t.stackTraceToString().take(900)}")
            }
            Tuning.reset()
            panel.visibility = View.VISIBLE
            line("done")
        }
    }

    private suspend fun heapSuite() {
        results.put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
        results.put("heap_limit_mb", Runtime.getRuntime().maxMemory() / 1048576)
        line("heap: limit ${Runtime.getRuntime().maxMemory() / 1048576} MB")
        val rows = JSONArray()
        results.put("heap", rows)
        // The shipped defaults, so this is the heap the real app would carry.
        Tuning.apply(sharedRails = true, simplify = true, coarseCaps = true, lod = false, mergeDraws = true, staticBuffers = true)
        for (n in listOf(1_000, 10_000, 30_000, 60_000, 100_000)) {
            canvasHeap(n, rows)
            pagedHeap(n, rows)
        }
        saveJson()
    }

    private suspend fun canvasHeap(n: Int, rows: JSONArray, parallel: Boolean = false) {
        val row = JSONObject().put("renderer", "canvas").put("strokes", n).put("parallel_meshing", parallel)
        rows.put(row)
        var step = "start"
        try {
            canvas = null
            step = "baseline"
            val ed = InfiniteEditor(this).also { canvas = it }
            ed.meshInParallel = parallel
            mount(ed.surfaces)
            val h0 = settledMb()
            step = "building model"
            var doc: InfiniteDocument? = withContext(Dispatchers.Default) { BenchData.canvasDocument(n) }
            val h1 = settledMb()
            row.put("model_kb_per_stroke", round((h1 - h0) * 1024 / n))
            step = "building geometry caches"
            withContext(Dispatchers.Default) { for (it in doc!!.items) (it as? Stroke)?.geometry() }
            val h2 = settledMb()
            row.put("geometry_cache_kb_per_stroke", round((h2 - h1) * 1024 / n))
            withContext(Dispatchers.Default) { for (it in doc!!.items) (it as? Stroke)?.releaseGeometry() }
            val h3 = settledMb()
            row.put("after_release_vs_model_mb", round(h3 - h1))
            step = "loading into the scene"
            val peak = DoubleArray(1)
            val tLoad = SystemClock.elapsedRealtime()
            withPeak(peak) {
                ed.replaceDocument(doc!!)
                repeat(5) { awaitFrame() }
                delay(2500)
            }
            row.put("load_ms", SystemClock.elapsedRealtime() - tLoad)
            row.put("load_peak_mb", round(peak[0]))
            row.put("load_peak_over_model_mb", round(peak[0] - h1))
            val h4 = settledMb()
            row.put("loaded_total_kb_per_stroke", round((h4 - h0) * 1024 / n))
            withContext(Dispatchers.Default) { for (it in doc!!.items) (it as? Stroke)?.releaseGeometry() }
            row.put("load_mesh_ms", ed.lastLoadMeshMs).put("load_wait_ms", ed.lastLoadWaitMs)
            val h5 = settledMb()
            row.put("scene_kb_per_stroke", round((h5 - h1) * 1024 / n))
            row.put("geometry_still_cached_kb_per_stroke", round((h4 - h5) * 1024 / n))
            step = "cleanup"
            ed.replaceDocument(InfiniteDocument())
            doc = null
            row.put("stage", "done")
            line("heap canvas $n ${if (parallel) "parallel" else "serial"}  load ${row.optLong("load_ms")} ms (mesh ${row.optLong("load_mesh_ms")}, wait ${row.optLong("load_wait_ms")})  model ${row.optDouble("model_kb_per_stroke")} KB, geometry cache ${row.optDouble("geometry_cache_kb_per_stroke")} KB, " +
                "scene ${row.optDouble("scene_kb_per_stroke")} KB, load peak ${row.optDouble("load_peak_mb")} MB (+${row.optDouble("load_peak_over_model_mb")} over model)")
        } catch (e: OutOfMemoryError) {
            row.put("stage", step).put("error", "OutOfMemory")
            line("heap canvas $n: OUT OF MEMORY while $step")
        } catch (t: Throwable) {
            row.put("stage", step).put("error", t.toString())
            line("heap canvas $n failed while $step: ${t.stackTraceToString().take(500)}")
        }
        canvas?.let { runCatching { it.replaceDocument(InfiniteDocument()) } }
        stage.removeAllViews()
        canvas = null
        settledMb()
        autosave()
    }

    private suspend fun pagedHeap(n: Int, rows: JSONArray) {
        val row = JSONObject().put("renderer", "paged").put("strokes", n)
        rows.put(row)
        var step = "start"
        try {
            val ed = paged ?: Editor(this).also { paged = it }
            mount(ed.surfaces)
            val st = ed.state
            st.document = Document.blank()
            st.invalidateAllCaches()
            st.relayout()
            val h0 = settledMb()
            step = "building model"
            var doc: Document? = withContext(Dispatchers.Default) { BenchData.pagedDocument(n) }
            val h1 = settledMb()
            row.put("model_kb_per_stroke", round((h1 - h0) * 1024 / n))
            step = "building geometry caches"
            withContext(Dispatchers.Default) {
                for (p in doc!!.pages) for (it in p.items) (it as? Stroke)?.geometry()
            }
            val h2 = settledMb()
            row.put("geometry_cache_kb_per_stroke", round((h2 - h1) * 1024 / n))
            withContext(Dispatchers.Default) {
                for (p in doc!!.pages) for (it in p.items) (it as? Stroke)?.releaseGeometry()
            }
            step = "loading into the editor"
            val peak = DoubleArray(1)
            val tLoad = SystemClock.elapsedRealtime()
            withPeak(peak) {
                st.document = doc!!
                st.invalidateAllCaches()
                st.relayout()
                st.fitWidth()
                repeat(5) { awaitFrame() }
                delay(3000)
            }
            row.put("load_ms", SystemClock.elapsedRealtime() - tLoad)
            row.put("load_peak_mb", round(peak[0]))
            row.put("load_peak_over_model_mb", round(peak[0] - h1))
            val h4 = settledMb()
            row.put("loaded_total_kb_per_stroke", round((h4 - h0) * 1024 / n))
            step = "cleanup"
            st.document = Document.blank()
            st.invalidateAllCaches()
            st.relayout()
            doc = null
            row.put("stage", "done")
            line("heap paged $n  model ${row.optDouble("model_kb_per_stroke")} KB, geometry cache ${row.optDouble("geometry_cache_kb_per_stroke")} KB, " +
                "loaded total ${row.optDouble("loaded_total_kb_per_stroke")} KB, load peak ${row.optDouble("load_peak_mb")} MB")
        } catch (e: OutOfMemoryError) {
            row.put("stage", step).put("error", "OutOfMemory")
            line("heap paged $n: OUT OF MEMORY while $step")
        } catch (t: Throwable) {
            row.put("stage", step).put("error", t.toString())
            line("heap paged $n failed while $step: ${t.stackTraceToString().take(500)}")
        }
        runCatching {
            paged?.state?.let { s ->
                s.document = Document.blank()
                s.invalidateAllCaches()
                s.relayout()
            }
        }
        settledMb()
        autosave()
    }
}
