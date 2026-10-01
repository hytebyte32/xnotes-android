package com.xnotes.bench

import android.app.Activity
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
class BenchActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    private lateinit var stage: FrameLayout
    private lateinit var panel: LinearLayout
    private lateinit var reportView: TextView

    private val text = StringBuilder()
    private val results = JSONObject()
    private var refreshMs = 16.6

    private var paged: Editor? = null
    private var canvas: InfiniteEditor? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        button("Run quick (1k, 10k)") { start(quick = true) }
        button("Run full (adds 100k)") { start(quick = false) }
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
        root.addView(panel, FrameLayout.LayoutParams(-1, -1, Gravity.TOP))
        setContentView(root)
        line("xnotes renderer spike. Keep the screen on and the pen away while it runs.")
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // --- reporting ---

    private fun line(s: String) {
        text.append(s).append('\n')
        reportView.text = text
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
        try { pdfRegions() } catch (t: Throwable) { line("pdf test failed: $t") }
        try { textTextures() } catch (t: Throwable) { line("text test failed: $t") }
        try { coldOpen() } catch (e: OutOfMemoryError) { line("cold open: OUT OF MEMORY") }
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
        line("paged: loaded in ${SystemClock.elapsedRealtime() - tLoad} ms")
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
            r.put("renderer", "paged").put("strokes", n).put("gesture", label)
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
        line("canvas: loaded + meshed in ${SystemClock.elapsedRealtime() - tLoad} ms")
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
            r.put("renderer", "gl").put("strokes", n).put("gesture", label)
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

    private fun makePdf(): File {
        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
        val c = page.canvas
        val rnd = Random(7)
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
        val f = File(cacheDir, "bench.pdf")
        FileOutputStream(f).use { doc.writeTo(it) }
        doc.close()
        return f
    }

    // --- text and equations as textures ---

    private suspend fun textTextures() {
        line("--- text boxes and equations as textures ---")
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
                repeat(30) {
                    val bmp = Bitmap.createBitmap((700 * scale).toInt(), (120 * scale).toInt(), Bitmap.Config.ARGB_8888)
                    val cv = Canvas(bmp)
                    cv.scale(scale, scale)
                    val t0 = System.nanoTime()
                    MathRendering.draw(cv, "\\int_0^\\infty e^{-x^2}\\,dx=\\frac{\\sqrt{\\pi}}{2}", 10.0, 70.0, 24.0, Rgba(0, 0, 0, 255), true)
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
        val pdoc = withContext(Dispatchers.Default) { BenchData.pagedDocument(40_000, perPage = 200) }
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
        val po = JSONObject().put("pages", back.pages.size).put("strokes", 40_000).put("file_mb", round(pf.length() / 1048576.0))
            .put("write_ms", writeMs).put("read_ms", readMs).put("first_frame_ms", firstFrame).put("mem", memorySnapshot())
        o.put("paged", po)
        line("paged 200 pages / 40k strokes: file ${po.optDouble("file_mb")} MB, write $writeMs ms, read $readMs ms, first frame $firstFrame ms, mem ${po.getJSONObject("mem")}")
        ed.state.document = Document.blank()
        ed.state.invalidateAllCaches()
        ed.state.relayout()
        pf.delete()
        System.gc()
        delay(500)

        val cdoc = withContext(Dispatchers.Default) { BenchData.canvasDocument(100_000) }
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
        val co = JSONObject().put("strokes", 100_000).put("file_mb", round(cf.length() / 1048576.0))
            .put("write_ms", cwMs).put("read_ms", crMs).put("load_and_first_frame_ms", cFirst).put("mem", memorySnapshot())
            .put("gpu_geometry_mb", st.geometryBytes / (1024 * 1024))
        o.put("canvas", co)
        line("canvas 100k strokes: file ${co.optDouble("file_mb")} MB, write $cwMs ms, read $crMs ms, load+first frame $cFirst ms, " +
            "gpu geometry ${co.optLong("gpu_geometry_mb")} MB, mem ${co.getJSONObject("mem")}")
        ce.replaceDocument(InfiniteDocument())
        cf.delete()
    }
}
