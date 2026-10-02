package com.xnotes.canvas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import com.xnotes.core.geometry.Pt
import com.xnotes.core.infinite.CanvasBackground
import com.xnotes.core.infinite.ItemMesher
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.Page
import com.xnotes.core.model.TextItem
import com.xnotes.core.model.PagePattern
import com.xnotes.core.pal.Pen
import com.xnotes.gl.CanvasScene
import com.xnotes.gl.CanvasSceneSink
import com.xnotes.gl.InfiniteCanvasView
import com.xnotes.gl.PageQuad
import com.xnotes.gl.PagedInkSync
import com.xnotes.gl.PagedScene
import com.xnotes.gl.TextBuckets
import com.xnotes.platform.AndroidRenderer
import java.util.IdentityHashMap
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.ln

/**
 * Draws a paged note with GL: page underlays (paper, PDF, ruling, flow text) as Skia-rendered
 * textures, every item's ink as geometry, all in one view under [view], which stays on top as a
 * transparent layer for input, tool overlays and the scrollbar.
 *
 * Temporary: it is switched on by a debug setting while the Skia ink path still exists, so the two
 * can be compared on the tablet. Rotated views are not handled yet and fall back to Skia.
 *
 * Main thread, except where noted.
 */
class GlInkHost(
    context: Context,
    private val state: CanvasState,
    private val view: CanvasView,
    /** Whether the front-buffer pad still owns [item]'s pixels, so GL must not file it yet. */
    private val held: (CanvasItem) -> Boolean,
) : GlInkBridge {

    val glView = InfiniteCanvasView(context)

    private val scene = CanvasScene()
    private val paged = PagedScene(scene)
    private val sync = PagedInkSync(
        CanvasSceneSink(scene),
        skip = { item -> state.isLiftedItem(item) || held(item) },
        mesh = { item -> ItemMesher.mesh(item, simplify = 0.0) },
    )

    private val main = Handler(Looper.getMainLooper())
    private val renderPool = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "xnotes-underlay").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }
    }

    private var globalGen = 0L
    private val pageGen = IdentityHashMap<Page, Long>()
    private val inFlight = IdentityHashMap<Page, Long>()
    private val flowGen = IdentityHashMap<Page, Long>()
    private val flowWanted = IdentityHashMap<Page, Long>()
    private val flowInFlight = IdentityHashMap<Page, Long>()

    private var origins: List<Pt> = emptyList()
    private var filedPages: List<Page> = emptyList()
    private var attached = false

    private var lastZoom = Double.NaN
    private var lastOx = Double.NaN
    private var lastOy = Double.NaN
    private var publishedBucketZoom = 1.0
    private val zoomSettle = Runnable { republishPages() }

    init {
        glView.viewport.apply {
            minZoom = 1e-4
            maxZoom = 1e4
            originX = -1e15
            originY = -1e15
        }
        glView.background = CanvasBackground(pattern = PagePattern.NONE)
        glView.scene = paged
        paged.onNeedTextures = { stale -> main.post { render(stale) } }
        paged.onNeedFlow = { stale -> main.post { renderFlow(stale) } }
        scene.onNeedText = { item, key, bucket -> main.post { renderText(item, key, bucket) } }
    }

    /** Hand drawing over to GL. Pass the page list as it stands. */
    fun attach() {
        if (attached) return
        attached = true
        glView.paperColor = state.palette.bg
        state.glBridge = this
        view.glCamera = ::camera
        view.glAfterFrame = { once -> glView.afterFrame { main.post(once) } }
        view.glMode = true
        rebuildAll()
    }

    /** Give drawing back to the Skia path. */
    fun detach() {
        if (!attached) return
        attached = false
        state.glBridge = null
        view.glCamera = null
        view.glAfterFrame = null
        view.glMode = false
        main.removeCallbacks(zoomSettle)
        state.invalidateAllCaches()
    }

    // --- camera ---

    private fun camera(zoom: Double, ox: Double, oy: Double, widthPx: Int = state.viewportW, heightPx: Int = state.viewportH) {
        val vp = glView.viewport
        vp.widthPx = widthPx
        vp.heightPx = heightPx
        vp.zoom = zoom
        vp.scrollX = -ox / zoom
        vp.scrollY = -oy / zoom
        glView.paperColor = state.palette.bg
        glView.publish()
        val moved = zoom != lastZoom || ox != lastOx || oy != lastOy
        if (moved) glView.setInteractive(true)
        if (zoom != lastZoom) {
            lastZoom = zoom
            // A pinch is drawn from the texture in hand, scaled; the sharp one is asked for once it settles.
            if (abs(ln(zoom / publishedBucketZoom)) > 0.2) {
                main.removeCallbacks(zoomSettle)
                main.postDelayed(zoomSettle, ZOOM_SETTLE_MS)
            }
        }
        lastOx = ox
        lastOy = oy
        if (moved) glView.setInteractive(false)
    }

    // --- GlInkBridge ---

    override fun layoutChanged() {
        val pages = state.document.pages
        val now = originsNow()
        if (now == null) return
        val samePages = pages.size == filedPages.size && pages.indices.all { pages[it] === filedPages[it] }
        if (!samePages) {
            rebuildAll()
            return
        }
        // Only pages that actually moved are re-filed, so a window resize that leaves the layout
        // alone costs nothing.
        for (i in pages.indices) {
            val o = origins[i]
            if (abs(o.x - now[i].x) > 1e-6 || abs(o.y - now[i].y) > 1e-6) sync.refile(pages[i], now[i])
        }
        origins = now
        republishPages()
        glView.publish()
    }

    override fun inkChanged(page: Page) {
        val i = state.document.pages.indexOfFirst { it === page }
        if (i < 0) return
        val o = originsNow()?.get(i) ?: return
        sync.refile(page, o)
        glView.publish()
    }

    /** Flow text changed on [pages] (every page when null): its layer re-renders, the underlay does not. */
    fun flowChanged(pages: Collection<Page>?) {
        val targets = pages ?: state.document.pages
        for (p in targets) flowGen[p] = (flowGen[p] ?: 0L) + 1
        republishPages()
    }

    override fun pageInvalidated(page: Page) {
        inkChanged(page)
        pageGen[page] = (pageGen[page] ?: 0L) + 1
        republishPages()
    }

    override fun itemAppended(page: Page, item: CanvasItem) {
        val i = state.document.pages.indexOfFirst { it === page }
        if (i < 0) return
        val o = originsNow()?.get(i) ?: return
        sync.appendItem(page, item, o)
        glView.publish()
    }

    override fun backgroundChanged(page: Page) {
        pageGen[page] = (pageGen[page] ?: 0L) + 1
        republishPages()
    }

    override fun everythingChanged() {
        globalGen++
        rebuildAll()
    }

    // --- internals ---

    /** Content-space origin of each page's page space, or null while the layout has not caught up. */
    private fun originsNow(): List<Pt>? {
        val pages = state.document.pages
        if (state.pageRects.size != pages.size) return null
        return pages.indices.map { state.fromPageSpace(it, Pt(0.0, 0.0)) }
    }

    private fun rebuildAll() {
        val pages = state.document.pages
        val now = originsNow() ?: return
        filedPages = ArrayList(pages)
        origins = now
        sync.rebuild(pages, now)
        republishPages()
        glView.publish()
    }

    private fun republishPages() {
        val pages = state.document.pages
        if (state.pageRects.size != pages.size) return
        publishedBucketZoom = state.zoom
        paged.pages = pages.indices.map { i ->
            val p = pages[i]
            PageQuad(
                key = p,
                rect = state.pageRects[i],
                version = (globalGen * 1_000_003L + (pageGen[p] ?: 0L)) * 64L + bucketFor(p),
                paperRgb = state.paperColor(p).toArgb() and 0xFFFFFF,
                flowVersion = if (state.flowOnPage?.invoke(p) == true) {
                    ((globalGen * 1_000_003L + (flowGen[p] ?: 0L)) * 128L + bucketFor(p) + 1L).also { flowWanted[p] = it }
                } else {
                    0L
                },
            )
        }
        glView.publish()
    }

    /** Half-octave resolution step, so a slow pinch re-renders only at doublings-ish, not every frame. */
    private fun bucketFor(page: Page): Long =
        (ceil(2.0 * ln(state.underlayRes(page)) / LN2).toInt() + BUCKET_BIAS).coerceIn(0, 63).toLong()

    private fun resForBucket(bucket: Long): Double = Math.pow(2.0, (bucket - BUCKET_BIAS) / 2.0)

    private fun render(stale: List<PageQuad>) {
        for (q in stale) {
            val page = q.key as Page
            if (inFlight[page] == q.version) continue
            inFlight[page] = q.version
            val res = resForBucket(q.version % 64L)
            renderPool.execute {
                val bmp = runCatching { renderUnderlay(page, res) }.getOrNull()
                main.post {
                    if (inFlight[page] == q.version) inFlight.remove(page)
                    if (bmp != null) {
                        paged.underlay.submit(page, bmp, q.version)
                        glView.publish()
                    }
                }
            }
        }
    }

    private fun renderFlow(stale: List<PageQuad>) {
        for (q in stale) {
            val page = q.key as Page
            val version = q.flowVersion
            if (flowInFlight[page] == version) continue
            flowInFlight[page] = version
            val res = resForBucket((version - 1L) % 128L)
            renderPool.execute {
                val bmp = runCatching { renderFlowLayer(page, res) }.getOrNull()
                main.post {
                    if (flowInFlight[page] == version) flowInFlight.remove(page)
                    // A render that lost the race to a newer keystroke is dropped, so text never steps backwards.
                    if (bmp != null && flowWanted[page] == version) {
                        paged.flow.submit(page, bmp, version)
                        glView.publish()
                    } else {
                        bmp?.recycle()
                    }
                }
            }
        }
    }

    /** Flow text alone, on a transparent bitmap the size of the page's footprint. */
    private fun renderFlowLayer(page: Page, res: Double): Bitmap {
        val cover = state.footprint(page)
        val w = ceil(cover.w * res).toInt().coerceIn(1, MAX_EDGE)
        val h = ceil(cover.h * res).toInt().coerceIn(1, MAX_EDGE)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val r = AndroidRenderer(Canvas(bmp))
        r.scale(w / cover.w, h / cover.h)
        r.translate(-cover.left, -cover.top)
        state.paintFlow?.invoke(page, r, cover)
        return bmp
    }

    /** Snapshot of what GL holds and drew, for the bench's smoke test. */
    class Counts(
        val records: Int, val vectors: Int, val images: Int, val texts: Int,
        val textFiled: Int, val textTextures: Int, val textAsked: Int, val textRenders: Int, val textFailures: Int,
        val lastError: String,
    ) {
        override fun toString() =
            "records=$records vec=$vectors img=$images txt=$texts txtFiled=$textFiled txtTex=$textTextures " +
                "asked=$textAsked rend=$textRenders fail=$textFailures$lastError"
    }

    fun counts() = Counts(
        scene.recordCount, scene.visibleVectors, scene.visibleImages, scene.visibleTexts,
        scene.textFiled, scene.textLayer.textureCount, scene.textRequested, textRenders, textFailures, lastTextError,
    )

    fun filedCount(): Int = sync.filedCount()

    private var textRenders = 0
    private var textFailures = 0
    private var lastTextError = ""

    /** One line for the debug HUD: what the GL side has done about text boxes. */
    fun hud(): String =
        "gl txt filed${scene.textFiled} drawn${scene.textDrawnLast} tex${scene.textLayer.textureCount} " +
            "asked${scene.textRequested} rend$textRenders fail$textFailures$lastTextError"

    private val textInFlight = IdentityHashMap<TextItem, Long>()

    /** Render a text box with Skia for [bucket]'s density and hand the bitmap to the scene's text layer. */
    private fun renderText(item: TextItem, key: Long, bucket: Int) {
        val want = key * 64L + bucket
        if (textInFlight[item] == want) return
        textInFlight[item] = want
        val res = TextBuckets.resFor(bucket)
        renderPool.execute {
            val result = runCatching { renderTextBox(item, res) }
            val bmp = result.getOrNull()
            main.post {
                if (textInFlight[item] == want) textInFlight.remove(item)
                textRenders++
                result.exceptionOrNull()?.let {
                    textFailures++
                    lastTextError = " " + it.javaClass.simpleName + ":" + (it.message ?: "").take(40)
                }
                if (bmp != null) {
                    scene.textLayer.submit(item, bmp, want)
                    glView.publish()
                }
            }
        }
    }

    private fun renderTextBox(item: TextItem, res: Double): Bitmap {
        val b = item.bounds()
        val w = ceil(b.w * res).toInt().coerceIn(1, MAX_EDGE)
        val h = ceil(b.h * res).toInt().coerceIn(1, MAX_EDGE)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val r = AndroidRenderer(Canvas(bmp))
        r.scale(w / b.w.coerceAtLeast(1e-6), h / b.h.coerceAtLeast(1e-6))
        r.translate(-b.x, -b.y)
        item.paint(r)
        return bmp
    }

    /** Paper, border, PDF/ruling and flow text for [page] at [res]: everything static under the ink. */
    private fun renderUnderlay(page: Page, res: Double): Bitmap {
        val cover = state.footprint(page)
        val w = ceil(cover.w * res).toInt().coerceIn(1, MAX_EDGE)
        val h = ceil(cover.h * res).toInt().coerceIn(1, MAX_EDGE)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val r = AndroidRenderer(Canvas(bmp))
        r.scale(w / cover.w, h / cover.h)
        r.translate(-cover.left, -cover.top)
        r.fillRect(cover, state.paperColor(page))
        if (state.hasPageBackground(page)) state.paintPageBackground?.invoke(page, r, res, cover)
        if (state.pageBorders) r.strokeRect(cover, Pen(state.palette.paperBorder, 1.0, cosmetic = true))
        return bmp
    }

    companion object {
        private val LN2 = ln(2.0)
        private const val BUCKET_BIAS = 20
        private const val MAX_EDGE = 8192
        private const val ZOOM_SETTLE_MS = 150L
    }
}
