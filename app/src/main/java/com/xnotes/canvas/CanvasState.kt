package com.xnotes.canvas

import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.PageInsets
import com.xnotes.core.model.PageTemplates
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.Stroke
import com.xnotes.core.model.insets
import com.xnotes.core.model.resolvedPageColor
import com.xnotes.core.model.resolvedTemplate
import com.xnotes.core.pal.Renderer
import com.xnotes.core.pal.SurfaceFactory
import com.xnotes.core.tools.Tool
import com.xnotes.ui.theme.Palette
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Highlighters are composited live over the finished page each frame (so their MULTIPLY
 * blend darkens against paper, PDF background and ink alike), never baked into the
 * transparent ink cache that sits above the background — see [CanvasView]. All other
 * ink is cached.
 */
internal fun CanvasItem.isHighlighterInk(): Boolean =
    (this is Stroke && this.tool == Tool.HIGHLIGHTER) || (this is com.xnotes.core.model.ShapeItem && this.isHighlighter)

/** How a freshly opened document's initial view is chosen (see [CanvasState.establishInitialView]). */
sealed class InitialView {
    /** Fit the page width and start at the first page. */
    object FitWidth : InitialView()

    /** Reapply a remembered zoom + scroll. */
    class Restore(val zoom: Double, val scrollX: Double, val scrollY: Double) : InitialView()
}

/**
 * Owns the view-side state of the canvas (spec 05): document layout in content
 * space, the viewport (scroll + zoom), the content<->viewport transforms, page
 * navigation and the per-page raster cache.
 *
 * Pure of any Android View dependency so it can be unit-/instrument-tested; the
 * [CanvasView] drives it.
 */
class CanvasState(
    var document: Document,
    private val surfaceFactory: SurfaceFactory,
    var palette: Palette,
) {
    var zoom: Double = 1.0
    var scrollX: Double = 0.0
    var scrollY: Double = 0.0
    var viewportW: Int = 0
    var viewportH: Int = 0
    var renderScale: Double = 1.0

    /**
     * Viewport px a floating toolbar covers along each edge. The pages still run under it, but the
     * scroll range and the fits only count the clear area inside, so nothing is stuck beneath it.
     */
    var insetLeft: Double = 0.0
    var insetTop: Double = 0.0
    var insetRight: Double = 0.0
    var insetBottom: Double = 0.0
    val clearW: Double get() = viewportW - insetLeft - insetRight
    val clearH: Double get() = viewportH - insetTop - insetBottom

    /** The middle of the clear area, where zoom steps anchor. */
    fun clearCenter(): Pt = Pt(insetLeft + clearW / 2.0, insetTop + clearH / 2.0)

    /** User-set zoom limits (preferences); every zoom path clamps into [minZoom]..[maxZoom]. */
    var minZoom: Double = MIN_ZOOM
    var maxZoom: Double = MAX_ZOOM

    /**
     * Long-edge cap (content px) for the on-screen page caches; zoom past it renders the visible
     * region live (the sharp viewport) instead of caching the whole page. User-set in preferences.
     */
    var maxCachePx: Double = 2048.0

    /**
     * Elastic overscroll past the document's **bottom** end (viewport px, already damped). Positive
     * lifts the whole content up, opening a gap below the last page where the "pull to add page"
     * affordance is drawn (see [CanvasView]). Purely visual — it never changes [scrollX]/[scrollY] —
     * and is sprung back to 0 on release by the interaction layer. The interaction layer owns the
     * gesture; this field just lets [origin] and the draw loop see the live stretch.
     */
    var overscrollY: Double = 0.0

    /** Device pixels per dp (display density), set by the view. Lets the speed pen
     *  measure gesture speed in zoom- and device-independent dp (see [InteractionController]). */
    var devicePxPerDp: Double = 1.0
    var pageColorOverride: Rgba? = null
    var didInitialFit: Boolean = false

    /** The view to install on the next layout for a just-opened document (null ⇒ fit width);
     *  consumed by [establishInitialView] once the viewport is sized. */
    var pendingInitialView: InitialView? = null

    /** Horizontal margin on each side of the page column (0 ⇒ fit-width fills the viewport). */
    var sideMargin: Double = MARGIN

    /** Whether the hairline outline around each page is drawn (a user preference). */
    var pageBorders: Boolean = true

    /** Gap (content px) between pages — inside a spread, between rows, and between paginated
     *  rows alike — equal to [sideMargin] so spacing always matches the margin preference. */
    val pageGap: Double get() = sideMargin

    /** Margin (content px) above and below the content. Paginated it follows [sideMargin], so a
     *  0 px preference lets the fit-height magnet fill the viewport; vertical scrolling keeps the
     *  fixed [MARGIN] that holds the first page clear of the toolbar. */
    val vertMargin: Double get() = if (verticalScroll) MARGIN else sideMargin

    /** How pages group into layout rows (Single / Double / Cover); see [rowRanges]. */
    var viewingMode: ViewingMode = ViewingMode.SINGLE

    /**
     * True = continuous vertical scrolling (rows stack top-down). False = paginated: rows
     * lay out as a horizontal strip, the scroll window is clamped to [currentRow]'s span,
     * and the interaction layer flips between rows (edge-swipe; the change is instant).
     */
    var verticalScroll: Boolean = true

    /** The row the paginated view is on (its scroll window); moved by flips and [goToPage]. */
    var currentRow: Int = 0

    /**
     * Paginated edge-pull (viewport px, signed; positive pulls toward the next row). Like
     * [overscrollY] it is purely visual — [origin] shifts by it so the current row slides
     * against empty background under the finger — and on release the interaction layer
     * flips instantly or drops the pull.
     */
    var flipOffsetX: Double = 0.0

    /**
     * Whole-file clockwise page rotation (0/90/180/270), applied by the view: pages lay
     * out with their rotated footprints ([displayW]/[displayH]) and their page-space
     * content is painted through [applyPageTransform]. Model/page space never rotates —
     * caches, items and hit geometry stay in page space and map through
     * [toPageSpace]/[fromPageSpace] at the display boundary.
     */
    var rotationDeg: Int = 0

    /**
     * [page]'s resolved margins in content px (page override -> document -> none). A margin grows
     * the paper *outside* the page's content box without moving anything on it, so page space keeps
     * its coordinates and simply starts negative on a margined edge — see [footprint].
     */
    fun insets(page: Page): PageInsets = page.insets(document)

    /** Page width including its margins (unrotated). */
    fun outerW(page: Page): Double = insets(page).let { it.left + page.width + it.right }

    /** Page height including its margins (unrotated). */
    fun outerH(page: Page): Double = insets(page).let { it.top + page.height + it.bottom }

    /** The whole paper in page space, margins included: the rect every page-space painter fills. */
    fun footprint(page: Page): Rect {
        val i = insets(page)
        return Rect(-i.left, -i.top, i.left + page.width + i.right, i.top + page.height + i.bottom)
    }

    /** On-screen (display) width of [page]'s footprint under the current rotation. */
    fun displayW(page: Page): Double = if (rotationDeg == 90 || rotationDeg == 270) outerH(page) else outerW(page)

    /** On-screen (display) height of [page]'s footprint under the current rotation. */
    fun displayH(page: Page): Double = if (rotationDeg == 90 || rotationDeg == 270) outerW(page) else outerH(page)

    /** Map a page-local display point (relative to the page rect's top-left) into page space. */
    fun displayToPage(page: Page, p: Pt): Pt {
        val i = insets(page)
        val q = when (rotationDeg) {
            90 -> Pt(p.y, outerH(page) - p.x)
            180 -> Pt(outerW(page) - p.x, outerH(page) - p.y)
            270 -> Pt(outerW(page) - p.y, p.x)
            else -> p
        }
        return if (i.isZero) q else Pt(q.x - i.left, q.y - i.top)
    }

    /** Map a page-space point into page-local display coordinates. */
    fun pageToDisplay(page: Page, p: Pt): Pt {
        val i = insets(page)
        val q = if (i.isZero) p else Pt(p.x + i.left, p.y + i.top)
        return when (rotationDeg) {
            90 -> Pt(outerH(page) - q.y, q.x)
            180 -> Pt(outerW(page) - q.x, outerH(page) - q.y)
            270 -> Pt(q.y, outerW(page) - q.x)
            else -> q
        }
    }

    /** Map a page-local display rect into page space (axis-aligned; corners re-normalize). */
    fun displayRectToPage(page: Page, r: Rect): Rect =
        Rect.fromPoints(displayToPage(page, Pt(r.left, r.top)), displayToPage(page, Pt(r.right, r.bottom)))

    /** A content-space point mapped into page [i]'s page space. */
    fun toPageSpace(i: Int, content: Pt): Pt {
        val pr = pageRects[i]
        return displayToPage(document.pages[i], Pt(content.x - pr.left, content.y - pr.top))
    }

    /** A page-space point of page [i] mapped into content space. */
    fun fromPageSpace(i: Int, p: Pt): Pt {
        val pr = pageRects[i]
        val d = pageToDisplay(document.pages[i], p)
        return Pt(pr.left + d.x, pr.top + d.y)
    }

    /** An axis-aligned content rect mapped into page [i]'s page space (re-normalized). */
    fun toPageSpaceRect(i: Int, r: Rect): Rect =
        Rect.fromPoints(toPageSpace(i, Pt(r.left, r.top)), toPageSpace(i, Pt(r.right, r.bottom)))

    /** An axis-aligned page-space rect of page [i] mapped into content space (re-normalized). */
    fun fromPageSpaceRect(i: Int, r: Rect): Rect =
        Rect.fromPoints(fromPageSpace(i, Pt(r.left, r.top)), fromPageSpace(i, Pt(r.right, r.bottom)))

    /** Rotate a content-space vector (an offset/delta) into page space. */
    fun vectorToPageSpace(v: Pt): Pt = when (rotationDeg) {
        90 -> Pt(v.y, -v.x)
        180 -> Pt(-v.x, -v.y)
        270 -> Pt(-v.y, v.x)
        else -> v
    }

    /**
     * Take [r] — already translated to a page rect's top-left — into page space: the view rotation,
     * then the page's margins, so page-space painting lands inside the paper's display footprint.
     * The inverse of [displayToPage].
     */
    fun applyPageTransform(r: Renderer, page: Page) {
        when (rotationDeg) {
            90 -> { r.translate(outerH(page), 0.0); r.rotate(90.0) }
            180 -> { r.translate(outerW(page), outerH(page)); r.rotate(180.0) }
            270 -> { r.translate(0.0, outerW(page)); r.rotate(270.0) }
        }
        val i = insets(page)
        if (!i.isZero) r.translate(i.left, i.top)
    }

    /** [pageToDisplay] as an affine (page space → page-local display coords). */
    private fun displayAffine(page: Page): Affine {
        val rot = when (rotationDeg) {
            90 -> Affine(0.0, 1.0, -1.0, 0.0, outerH(page), 0.0)
            180 -> Affine(-1.0, 0.0, 0.0, -1.0, outerW(page), outerH(page))
            270 -> Affine(0.0, -1.0, 1.0, 0.0, 0.0, outerW(page))
            else -> Affine.IDENTITY
        }
        val i = insets(page)
        return if (i.isZero) rot else rot.compose(Affine(1.0, 0.0, 0.0, 1.0, i.left, i.top))
    }

    /** [displayToPage] as an affine (page-local display coords → page space). */
    private fun pageAffine(page: Page): Affine {
        val rot = when (rotationDeg) {
            90 -> Affine(0.0, -1.0, 1.0, 0.0, 0.0, outerH(page))
            180 -> Affine(-1.0, 0.0, 0.0, -1.0, outerW(page), outerH(page))
            270 -> Affine(0.0, 1.0, -1.0, 0.0, outerW(page), 0.0)
            else -> Affine.IDENTITY
        }
        val i = insets(page)
        return if (i.isZero) rot else Affine(1.0, 0.0, 0.0, 1.0, -i.left, -i.top).compose(rot)
    }

    /**
     * Express a content-space affine [world] in page [i]'s page space, so it can be baked
     * into item geometry: conjugates by the page's display map (translation + rotation + margins).
     */
    fun affineToPageSpace(i: Int, world: Affine): Affine {
        val local = world.translatedFrame(pageRects[i].topLeft)
        val page = document.pages[i]
        if (rotationDeg == 0 && insets(page).isZero) return local
        return pageAffine(page).compose(local.compose(displayAffine(page)))
    }

    /** While true (a pinch that has moved the zoom) caches are blitted stale-scaled
     *  instead of rebuilt every frame; they rebuild at the final resolution when
     *  the gesture ends. */
    var zoomingInProgress: Boolean = false

    /** When true, zoom is fixed (pinch pans only, zoom buttons/fit are no-ops). */
    var zoomLocked: Boolean = false

    /**
     * True while the view is sitting at fit-to-width. Set when a pinch snaps to fit-width (or
     * [fitWidth]/[resetViewToFitWidth] land there), cleared whenever the zoom moves off it. Drives
     * [reflowFitWidthForResize] so the page re-fits when the usable width changes (e.g. the sidebar
     * opens) — independent of [zoomLocked], which only freezes user zoom gestures.
     */
    var fitWidthActive: Boolean = false

    /** Like [fitWidthActive] but for the paginated fit-to-height magnet ([fitHeightZoom]). */
    var fitHeightActive: Boolean = false

    /** The GL host that draws the ink and page underlays; every model change is reported to it. */
    var glBridge: GlInkBridge? = null

    /** Resolution a page underlay should be rendered at for the current zoom, capped at [maxCachePx]. */
    fun underlayRes(page: Page): Double = clampedRes(page)

    /** Items GL must not draw (lifted for selection/editing); set by the interaction layer. */
    var isLiftedItem: (CanvasItem) -> Boolean = { false }

    /**
     * Optional page-background painter (PDF / template). [region] is the page-local content rect
     * to render — the whole page footprint for an underlay texture or a thumbnail.
     */
    var paintPageBackground: ((page: Page, renderer: Renderer, res: Double, region: Rect) -> Unit)? = null

    /**
     * Optional flow-text painter (the document-wide typed text). It paints onto the
     * transparent ink layer *before* the page items, so ink annotates over text while
     * text sits over the page background. [region] is the page-local rect being
     * painted, like [paintPageBackground]. Runs off the UI thread: the installed hook
     * must read only an immutable published layout snapshot, never the live model.
     */
    var paintFlow: ((page: Page, renderer: Renderer, region: Rect) -> Unit)? = null

    /** Whether [page] has any flow text, so GL knows to keep a flow layer for it. */
    var flowOnPage: ((Page) -> Boolean)? = null

    /**
     * True while a flow-text caret session is live: the flow is painted live by the
     * editor instead of baked into a layer, so a keystroke never waits for a rebuild.
     */
    var flowLifted: Boolean = false

    var pageRects: List<Rect> = emptyList()
        private set
    var contentW: Double = 2 * MARGIN
        private set
    var contentH: Double = 2 * MARGIN
        private set

    // --- layout ---

    /**
     * Pages grouped into layout rows by [viewingMode], as consecutive page-index ranges:
     * Single = one page per row, Double = pairs (1-2, 3-4, …), Cover = the first page
     * alone, then pairs (2-3, 4-5, …) so facing pages line up like a book.
     */
    fun rowRanges(): List<IntRange> {
        val n = document.pages.size
        if (n == 0) return emptyList()
        return when (viewingMode) {
            ViewingMode.SINGLE -> (0 until n).map { it..it }
            ViewingMode.DOUBLE -> (0 until n step 2).map { it..min(it + 1, n - 1) }
            ViewingMode.COVER -> buildList {
                add(0..0)
                var i = 1
                while (i < n) {
                    add(i..min(i + 1, n - 1))
                    i += 2
                }
            }
        }
    }

    /** The layout row containing [pageIndex]. */
    fun rowOf(pageIndex: Int): IntRange {
        val last = document.pages.lastIndex
        val i = pageIndex.coerceIn(0, last)
        return when (viewingMode) {
            ViewingMode.SINGLE -> i..i
            ViewingMode.DOUBLE -> (i - i % 2).let { it..min(it + 1, last) }
            ViewingMode.COVER ->
                if (i == 0) 0..0 else (if (i % 2 == 1) i else i - 1).let { it..min(it + 1, last) }
        }
    }

    fun relayout() {
        val pages = document.pages
        if (pages.isEmpty()) {
            pageRects = emptyList()
            contentW = 2 * sideMargin
            contentH = 2 * vertMargin
            currentRow = 0
            glBridge?.layoutChanged()
            return
        }
        // Rects hold the pages' display footprints — rotation swaps their width/height. A row's
        // pages sit side by side, vertically centred within their row so facing pages align.
        val rows = rowRanges()
        val rowWidths = rows.map { r -> r.sumOf { displayW(pages[it]) } + (r.last - r.first) * pageGap }
        val rowHeights = rows.map { r -> r.maxOf { displayH(pages[it]) } }
        val rects = arrayOfNulls<Rect>(pages.size)
        if (verticalScroll) {
            // Rows stack top-down, centred against the widest row.
            val maxW = rowWidths.max()
            var y = vertMargin // vertical top margin (keeps the page below the toolbar)
            for ((ri, row) in rows.withIndex()) {
                var x = sideMargin + (maxW - rowWidths[ri]) / 2.0
                for (i in row) {
                    rects[i] = Rect(x, y + (rowHeights[ri] - displayH(pages[i])) / 2.0, displayW(pages[i]), displayH(pages[i]))
                    x += displayW(pages[i]) + pageGap
                }
                y += rowHeights[ri] + pageGap
            }
            contentW = maxW + 2 * sideMargin
            contentH = (y - pageGap) + vertMargin
        } else {
            // Paginated: rows run left-to-right in one strip, each top-aligned below the margin.
            val maxH = rowHeights.max()
            var x = sideMargin
            for ((ri, row) in rows.withIndex()) {
                var rx = x
                for (i in row) {
                    rects[i] = Rect(rx, vertMargin + (rowHeights[ri] - displayH(pages[i])) / 2.0, displayW(pages[i]), displayH(pages[i]))
                    rx += displayW(pages[i]) + pageGap
                }
                x += rowWidths[ri] + pageGap
            }
            contentW = (x - pageGap) + sideMargin
            contentH = maxH + 2 * vertMargin
        }
        pageRects = rects.map { it!! }
        currentRow = currentRow.coerceIn(0, rows.lastIndex)
        clampScroll()
        glBridge?.layoutChanged()
    }

    /** Index (into [rowRanges]) of the row containing [pageIndex]. */
    fun rowIndexOf(pageIndex: Int): Int {
        val first = rowOf(pageIndex).first
        return rowRanges().indexOfFirst { it.first == first }.coerceAtLeast(0)
    }

    /** The union of a row's page rects (content space). */
    fun rowBounds(row: IntRange): Rect {
        var acc: Rect? = null
        for (i in row) pageRects.getOrNull(i)?.let { acc = acc?.union(it) ?: it }
        return acc ?: Rect(0.0, 0.0, 1.0, 1.0)
    }

    /** The paginated scroll target for [rowIndex]: keep the zoom, land at the row's top. */
    fun rowTargetScroll(rowIndex: Int): Pt {
        val rows = rowRanges()
        if (rows.isEmpty()) return Pt(0.0, 0.0)
        val rb = rowBounds(rows[rowIndex.coerceIn(0, rows.lastIndex)])
        val minX = (rb.left - sideMargin) * zoom - insetLeft
        val maxX = (rb.right + sideMargin) * zoom - viewportW + insetRight
        val sx = if (maxX < minX) (minX + maxX) / 2.0 else minX
        val sy = (rb.top * zoom - TOP_GAP - insetTop).coerceAtLeast(-insetTop)
        return Pt(sx, sy)
    }

    /** True when the paginated view rests against its row's [next]-side edge (a flip is armed). */
    fun atRowEdge(next: Boolean): Boolean {
        val rows = rowRanges()
        if (rows.isEmpty()) return false
        val rb = rowBounds(rows[currentRow.coerceIn(0, rows.lastIndex)])
        val minX = (rb.left - sideMargin) * zoom - insetLeft
        val maxX = (rb.right + sideMargin) * zoom - viewportW + insetRight
        if (maxX < minX) return true // the row fits: both edges at once
        return if (next) scrollX >= maxX - 1.0 else scrollX <= minX + 1.0
    }

    /** Re-derive [currentRow] from the scroll position (a restored view / fresh layout). */
    fun syncCurrentRowToScroll() {
        if (verticalScroll) return
        val rows = rowRanges()
        if (rows.isEmpty()) {
            currentRow = 0
            return
        }
        val cx = (scrollX + clearCenter().x) / zoom
        var best = 0
        var bestD = Double.MAX_VALUE
        rows.forEachIndexed { i, row ->
            val d = abs(rowBounds(row).centerX - cx)
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        currentRow = best
    }

    // --- transforms ---

    fun origin(): Pt {
        val cw = contentW * zoom
        val ch = contentH * zoom
        // Paginated: the row clamp centres the row when it fits, so the scroll always wins; the
        // whole strip must never be centred (two pages would push each single-page row off centre).
        val ox = (if (!verticalScroll || cw >= clearW) -scrollX else insetLeft + (clearW - cw) / 2.0) - flipOffsetX
        val oy = (if (ch < clearH) insetTop + (clearH - ch) / 2.0 else -scrollY) - overscrollY
        return Pt(ox, oy)
    }

    fun contentToViewport(p: Pt): Pt {
        val o = origin()
        return Pt(p.x * zoom + o.x, p.y * zoom + o.y)
    }

    fun viewportToContent(p: Pt): Pt {
        val o = origin()
        return Pt((p.x - o.x) / zoom, (p.y - o.y) / zoom)
    }

    fun visibleContentRect(): Rect =
        Rect.fromPoints(viewportToContent(Pt(0.0, 0.0)), viewportToContent(Pt(viewportW.toDouble(), viewportH.toDouble())))

    // A floating bar lets the scroll run past the content's edges by as much as it covers.
    fun minScrollX(): Double = -insetLeft
    fun minScrollY(): Double = -insetTop
    fun maxScrollX(): Double = max(minScrollX(), ceil(contentW * zoom - viewportW + insetRight))
    fun maxScrollY(): Double = max(minScrollY(), ceil(contentH * zoom - viewportH + insetBottom))

    fun clampScroll() {
        if (!verticalScroll && pageRects.isNotEmpty()) {
            // Paginated: the scroll window is the current row's span (plus the side margins);
            // a row narrower/shorter than the viewport pins centred/top instead of drifting.
            val c = rowClampedScroll(currentRow, scrollX, scrollY)
            scrollX = c.x
            scrollY = c.y
            return
        }
        scrollX = scrollX.coerceIn(minScrollX(), maxScrollX())
        scrollY = scrollY.coerceIn(minScrollY(), maxScrollY())
    }

    /** ([sx], [sy]) clamped into [rowIndex]'s paginated scroll window, without mutating state. */
    fun rowClampedScroll(rowIndex: Int, sx: Double, sy: Double): Pt {
        val rows = rowRanges()
        if (rows.isEmpty()) return Pt(sx, sy)
        val rb = rowBounds(rows[rowIndex.coerceIn(0, rows.lastIndex)])
        val minX = (rb.left - sideMargin) * zoom - insetLeft
        val maxX = (rb.right + sideMargin) * zoom - viewportW + insetRight
        val cx = if (maxX < minX) (minX + maxX) / 2.0 else sx.coerceIn(minX, maxX)
        val maxY = ((rb.bottom + vertMargin) * zoom - viewportH + insetBottom).coerceAtLeast(-insetTop)
        return Pt(cx, sy.coerceIn(-insetTop, maxY))
    }

    fun scrollBy(dx: Double, dy: Double) {
        scrollX += dx
        scrollY += dy
        clampScroll()
    }

    // --- pages & navigation ---

    // --- page style resolution (current page -> document "all pages" -> global default) ---

    /** Resolved paper colour for [page], or null to fall back to the theme paper (see [paperColor]). */
    fun effectivePageColor(page: Page): Rgba? = page.resolvedPageColor(document, pageColorOverride)

    /** Resolved page template key for [page] ([PageTemplates.NONE] when nothing in the chain sets one). */
    fun effectiveTemplate(page: Page): String = page.resolvedTemplate(document)

    fun paperColor(page: Page): Rgba = effectivePageColor(page) ?: palette.paper

    /**
     * The pages the view may draw (and touch). Vertical mode shows everything the viewport
     * reaches. The paginated view shows exactly one row at any moment: [currentRow]
     * (zooming out must not creep the neighbouring rows into view). An edge pull just
     * reveals empty background, never the neighbour.
     */
    fun drawablePageRange(): IntRange {
        val last = document.pages.lastIndex
        if (verticalScroll) return 0..last
        val rows = rowRanges()
        if (rows.isEmpty()) return 0..last
        return rows[currentRow.coerceIn(0, rows.lastIndex)]
    }

    /** Index of the page whose rect contains a content-space point, or null. Hidden paginated
     *  neighbours never hit, so ink/erases/taps can't land on a page that isn't shown. */
    fun pageIndexAtContent(p: Pt): Int? {
        val drawable = drawablePageRange()
        for (i in pageRects.indices) if (i in drawable && pageRects[i].contains(p)) return i
        return null
    }

    /** The current page (spec 05 §4): contains the viewport vertical centre, biased by half a gap.
     *  Paginated mode reads it from [currentRow] instead (the page under the viewport centre). */
    fun currentPageIndex(): Int {
        if (pageRects.isEmpty()) return 0
        if (!verticalScroll) {
            val rows = rowRanges()
            val row = rows[currentRow.coerceIn(0, rows.lastIndex)]
            val cx = viewportToContent(Pt(viewportW / 2.0, viewportH / 2.0)).x
            for (i in row) if (pageRects[i].right + pageGap / 2.0 > cx) return i
            return row.last
        }
        val centerY = viewportToContent(Pt(viewportW / 2.0, viewportH / 2.0)).y
        for (i in pageRects.indices) {
            if (pageRects[i].bottom + pageGap / 2.0 > centerY) return i
        }
        return pageRects.size - 1
    }

    /**
     * True when the bottom edge of the last page is at or above the viewport's bottom — i.e. the
     * document's end is genuinely on screen. The pull-past-the-end (add-page) elastic keys off this
     * rather than inferring "at the end" from a rejected downward scroll, so it can never arm while
     * there is still document below the fold — even when a transient bad scroll/layout state right
     * after a document opens would make the bottom scroll clamp fire. Computed through the same
     * [origin]/transform that draws the frame, so it can never disagree with what the user sees.
     */
    fun isDocumentEndVisible(): Boolean {
        if (pageRects.isEmpty()) return false
        // The last row's lowest edge is contentH - vertMargin whatever the viewing mode.
        return contentToViewport(Pt(0.0, contentH - vertMargin)).y <= viewportH - insetBottom + 1.0
    }

    /** The first page of the row after the one containing [from] (page-nav stepping). */
    fun nextPageIndex(from: Int): Int = min(rowOf(from).last + 1, document.pages.lastIndex.coerceAtLeast(0))

    /** The first page of the row before the one containing [from] (page-nav stepping). */
    fun prevPageIndex(from: Int): Int = rowOf(max(rowOf(from).first - 1, 0)).first

    fun goToPage(index: Int) {
        if (pageRects.isEmpty()) return
        val i = index.coerceIn(0, pageRects.size - 1)
        if (!verticalScroll) {
            // Paginated: jump the scroll window to the page's row and land at its top.
            currentRow = rowIndexOf(i)
            flipOffsetX = 0.0
            val t = rowTargetScroll(currentRow)
            scrollX = t.x
            scrollY = t.y
            clampScroll()
            return
        }
        // Navigate to the page's whole layout row, so a Double/Cover spread centres as one.
        val row = rowOf(i)
        val top = row.minOf { pageRects[it].top }
        val centerX = (row.minOf { pageRects[it].left } + row.maxOf { pageRects[it].right }) / 2.0
        // Scroll so the page top clears the toolbar with a small gap, so no part of the
        // page is hidden behind the chrome.
        scrollY = (top * zoom - TOP_GAP - insetTop).coerceAtLeast(-insetTop)
        scrollX = centerX * zoom - clearCenter().x
        clampScroll()
    }

    // --- zoom ---

    fun setZoomAnchored(focusViewport: Pt, newZoom: Double) {
        if (zoomLocked) return
        val z = newZoom.coerceIn(minZoom, maxZoom)
        if (abs(z - zoom) < 1e-9) return
        fitWidthActive = false // an explicit zoom step leaves the fit magnets
        fitHeightActive = false
        val anchor = viewportToContent(focusViewport)
        zoom = z
        scrollX = anchor.x * z - focusViewport.x
        scrollY = anchor.y * z - focusViewport.y
        clampScroll()
    }

    fun zoomByStep(zoomIn: Boolean) {
        val factor = if (zoomIn) ZOOM_STEP else 1.0 / ZOOM_STEP
        setZoomAnchored(clearCenter(), zoom * factor)
    }

    /** Pull the live zoom back inside [minZoom]..[maxZoom] after the limits changed, anchored at
     *  the viewport centre. Deliberately ignores the zoom lock: a limit edit must always win. */
    fun clampZoomToLimits() {
        val z = zoom.coerceIn(minZoom, maxZoom)
        if (abs(z - zoom) < 1e-9) return
        if (viewportW == 0 || viewportH == 0) {
            zoom = z // not laid out yet; the initial view will place the scroll
            return
        }
        val focus = clearCenter()
        val anchor = viewportToContent(focus)
        zoom = z
        fitWidthActive = false
        fitHeightActive = false
        scrollX = anchor.x * z - focus.x
        scrollY = anchor.y * z - focus.y
        clampScroll()
    }

    fun fitWidth() {
        if (zoomLocked || contentW <= 0.0 || viewportW == 0) return
        val cur = currentPageIndex()
        zoom = fitWidthZoom()
        fitWidthActive = true
        fitHeightActive = false
        goToPage(cur)
    }

    fun fitHeight() {
        val pages = document.pages
        if (zoomLocked || pages.isEmpty() || viewportH == 0) return
        val cur = currentPageIndex()
        val magnet = fitHeightZoom() // paginated: land exactly where a pinch's height magnet does
        zoom = if (magnet > 0.0) magnet else ((clearH - 60.0) / displayH(pages[cur])).coerceIn(minZoom, maxZoom)
        fitWidthActive = false
        fitHeightActive = magnet > 0.0
        goToPage(cur)
    }

    fun fitPage() {
        val pages = document.pages
        if (zoomLocked || pages.isEmpty() || viewportW == 0 || viewportH == 0) return
        val cur = currentPageIndex()
        val page = pages[cur]
        val w = displayW(page) + 2 * sideMargin
        val h = displayH(page) + 2 * vertMargin
        if (w <= 0.0 || h <= 0.0) return
        zoom = min(clearW / w, clearH / h).coerceIn(minZoom, maxZoom)
        fitWidthActive = false
        fitHeightActive = false
        goToPage(cur)
    }

    // --- fit-to-width snapping (spec 05) ---

    /** The zoom at which the page column exactly fills the viewport width, or 0 if not measurable.
     *  Paginated mode fits the current row (the strip as a whole is never the fit target). */
    fun fitWidthZoom(): Double {
        if (viewportW == 0) return 0.0
        if (!verticalScroll) {
            val rows = rowRanges()
            if (rows.isEmpty()) return 0.0
            val w = rowBounds(rows[currentRow.coerceIn(0, rows.lastIndex)]).w + 2 * sideMargin
            return if (w <= 0.0) 0.0 else (clearW / w).coerceIn(minZoom, maxZoom)
        }
        if (contentW <= 0.0) return 0.0
        return (clearW / contentW).coerceIn(minZoom, maxZoom)
    }

    /** The zoom at which the current paginated row (plus the vertical margins) exactly fills
     *  the viewport height. 0 in vertical mode — the height magnet only exists paginated. */
    fun fitHeightZoom(): Double {
        if (verticalScroll || viewportH == 0) return 0.0
        val rows = rowRanges()
        if (rows.isEmpty()) return 0.0
        val h = rowBounds(rows[currentRow.coerceIn(0, rows.lastIndex)]).h + 2 * vertMargin
        return if (h <= 0.0) 0.0 else (clearH / h).coerceIn(minZoom, maxZoom)
    }

    /**
     * Live magnetic fit for a pinch: within [SNAP_TO_FIT_WIDTH] of fit-to-width the zoom sticks
     * to exactly fit-width and sets [fitWidthActive]; in paginated mode fit-to-height is a second
     * magnet with the same band and feel ([fitHeightZoom] / [fitHeightActive]), the nearer target
     * winning when both grab. Otherwise [raw] passes through and both flags clear. Returns the
     * zoom the caller applies; the false→true transition of either flag surfaces the lock hint.
     */
    fun snapZoomToFit(raw: Double): Double {
        val fitW = fitWidthZoom()
        val fitH = fitHeightZoom()
        val nearW = fitW > 0.0 && abs(raw - fitW) <= fitW * SNAP_TO_FIT_WIDTH
        val nearH = fitH > 0.0 && abs(raw - fitH) <= fitH * SNAP_TO_FIT_WIDTH
        val pickH = nearH && (!nearW || abs(raw - fitH) < abs(raw - fitW))
        fitHeightActive = pickH
        fitWidthActive = nearW && !pickH
        return when {
            pickH -> fitH
            fitWidthActive -> fitW
            else -> raw
        }
    }

    /**
     * Re-fit to the available space after a viewport resize (e.g. the sidebar opened/closed), but
     * only while [fitWidthActive] (or, paginated, [fitHeightActive]). Deliberately ignores
     * [zoomLocked]: when the usable space changes, staying fit matters more than holding the
     * exact zoom number.
     */
    fun reflowFitWidthForResize() {
        if (contentW <= 0.0 || viewportW == 0) return
        if (fitHeightActive && !verticalScroll) {
            val fit = fitHeightZoom()
            if (fit <= 0.0) return
            zoom = fit
            clampScroll() // the row window recentres/pins for the new zoom
            return
        }
        if (!fitWidthActive) return
        // Keep the content under the viewport's vertical centre put (don't jump to the page top) and
        // re-centre horizontally; only the width-driven zoom changes.
        val center = clearCenter()
        val centerContentY = viewportToContent(center).y
        zoom = fitWidthZoom()
        scrollX = minScrollX()
        scrollY = centerContentY * zoom - center.y
        clampScroll()
    }

    // --- initial view (on opening a document) ---

    /**
     * Set zoom + scroll directly when opening a document. Unlike [setZoomAnchored] this
     * ignores the zoom lock — switching documents isn't a user zoom gesture, and the new
     * document must land at its own view rather than inherit the previous one's.
     */
    fun setView(newZoom: Double, sx: Double, sy: Double) {
        zoom = newZoom.coerceIn(minZoom, maxZoom)
        scrollX = sx
        scrollY = sy
        syncCurrentRowToScroll() // paginated: land on the restored row before the clamp
        // A restored view that lands at a fit target should still auto-refit on resize.
        val targetW = fitWidthZoom()
        val targetH = fitHeightZoom()
        fitHeightActive = targetH > 0.0 && abs(zoom - targetH) <= targetH * SNAP_TO_FIT_WIDTH
        fitWidthActive = !fitHeightActive && targetW > 0.0 && abs(zoom - targetW) <= targetW * SNAP_TO_FIT_WIDTH
        clampScroll()
    }

    /** Fit page width and scroll to the first page, ignoring the zoom lock (document open). */
    fun resetViewToFitWidth() {
        if (contentW <= 0.0 || viewportW == 0) return
        currentRow = 0 // paginated fit-width targets the first row
        zoom = fitWidthZoom()
        fitWidthActive = true
        fitHeightActive = false
        goToPage(0)
    }

    /**
     * Apply [pendingInitialView] (or fit width when it's null/[InitialView.FitWidth]) once the
     * viewport is sized, and mark the initial fit done. Called when a document is opened and from
     * the view's first layout; resets the view explicitly so a previous document's zoom/scroll
     * (or a stale zoom lock) can never carry over.
     */
    fun establishInitialView() {
        if (viewportW <= 0 || viewportH <= 0) return
        when (val v = pendingInitialView) {
            is InitialView.Restore -> setView(v.zoom, v.scrollX, v.scrollY)
            else -> resetViewToFitWidth()
        }
        pendingInitialView = null
        didInitialFit = true
    }

    // --- GL bridge notifications ---
    // The ink, page underlays and flow text are all drawn by GL; the model's invalidations end here
    // and are forwarded to the GL host, which re-files and re-renders what changed.

    /** Resolution an underlay bitmap should be rendered at for the current zoom, capped at [maxCachePx]. */
    private fun clampedRes(page: Page): Double {
        var res = zoom * renderScale
        val longEdge = max(outerW(page), outerH(page))
        if (longEdge * res > maxCachePx) res = maxCachePx / longEdge
        return res.coerceAtLeast(0.01)
    }

    /**
     * Whether [page] has anything in its background layer — a PDF page or a resolved ruling.
     * Plain colour pages return false so no (large, transparent) background texture is built, even
     * though [paintPageBackground] is always installed for the pattern path.
     */
    fun hasPageBackground(page: Page): Boolean =
        paintPageBackground != null && (page.pdfPage != null || effectiveTemplate(page) != PageTemplates.NONE)

    /** A single just-committed item was added to [page]. */
    fun appendToCache(page: Page, item: CanvasItem) {
        glBridge?.itemAppended(page, item)
    }

    /**
     * Report that only [dirtyRect] (page-local content space) of [page] changed, e.g. after the
     * eraser removed strokes from a small area. Returns false when no GL host is attached, so the
     * caller can fall back to [invalidatePage].
     */
    fun repairRegion(page: Page, dirtyRect: Rect): Boolean {
        val bridge = glBridge ?: return false
        bridge.inkChanged(page, dirtyRect)
        return true
    }

    fun invalidatePage(page: Page) {
        glBridge?.pageInvalidated(page)
    }

    /** Paper colour or ruling changed: every page's underlay is out of date. */
    fun invalidatePaper() {
        glBridge?.everythingChanged()
    }

    fun invalidateBackground(page: Page) {
        glBridge?.backgroundChanged(page)
    }

    fun invalidateAllBackgrounds() {
        glBridge?.everythingChanged()
    }

    /** Page footprints changed (a margin edit). */
    fun invalidatePageGeometry() {
        glBridge?.everythingChanged()
    }

    fun invalidateAllCaches() {
        glBridge?.everythingChanged()
    }

    /** Paint the live stroke, inside the page's own transform. */
    fun paintLiveStroke(r: Renderer, stroke: Stroke) {
        stroke.paint(r)
    }

    /** Undo/redo of a command that cannot say what it touched: everything may have changed. */
    fun refreshAllInk() {
        glBridge?.everythingChanged()
    }

    /**
     * Undo/redo confined to the regions the undone command disturbed (see
     * [com.xnotes.core.history.Command.touched]). Rects are unioned per page.
     */
    fun repairInkRegions(regions: List<Pair<Page, Rect>>) {
        if (regions.isEmpty()) return
        val byPage = LinkedHashMap<Page, Rect>()
        for ((page, rect) in regions) {
            byPage[page] = byPage[page]?.union(rect) ?: rect
        }
        for ((page, rect) in byPage) repairRegion(page, rect.outset(EDIT_PAD))
    }

    /** [page]'s background (e.g. a PDF page whose embedded-image colours just finished parsing) changed. */
    fun refreshBackground(page: Page) {
        if (paintPageBackground == null) return
        glBridge?.backgroundChanged(page)
    }

    // --- ribbon geometry lifetime ---

    /**
     * Pages whose strokes have built ribbon geometry ([Stroke.geometry]), so eviction can walk only
     * the pages that leave a band instead of the whole document.
     */
    private val geomPages = HashSet<Page>()

    /** Record that [page]'s strokes now hold ribbon geometry. May be called from any thread. */
    fun noteGeometryBuilt(page: Page) {
        synchronized(geomPages) { geomPages.add(page) }
    }

    /**
     * Release ribbon geometry for every page outside [keep], and forget it. [Stroke.releaseGeometry]
     * drops the ribbon but keeps the bounds rects.
     */
    fun releaseGeometryExcept(keep: Set<Page>) {
        synchronized(geomPages) {
            if (geomPages.isEmpty()) return
            val it = geomPages.iterator()
            while (it.hasNext()) {
                val page = it.next()
                if (page in keep) continue
                for (item in page.items) if (item is Stroke) item.releaseGeometry()
                it.remove()
            }
        }
    }

    /**
     * The pages whose rect intersects the viewport, as a contiguous `first..last` index range
     * (pages stack vertically, so the visible set is always contiguous), or null when none is
     * visible. Used for the debug readout.
     */
    fun visiblePageRange(): IntRange? {
        val visible = visibleContentRect()
        val drawable = drawablePageRange()
        var first = -1
        var last = -1
        for (i in pageRects.indices) {
            if (i !in drawable) continue
            val pr = pageRects.getOrNull(i) ?: continue
            if (!pr.intersects(visible)) continue
            if (first < 0) first = i
            last = i
        }
        return if (first < 0) null else first..last
    }

    /** Last note-open timings (ms), for the debug overlay; -1 until the first open. [lastOpenReadMs]
     *  is the off-thread file read alone; [lastOpenTotalMs] is the whole open the 160ms spinner sees. */
    var lastOpenReadMs = -1L
    var lastOpenTotalMs = -1L

    /** [lastOpenReadMs] broken into its phases: decompressing the manifest, turning that text into
     *  the model, streaming the images and PDF out, and re-simplifying ink from an old writer. */
    var lastOpenInflateMs = -1L
    var lastOpenParseMs = -1L
    var lastOpenAssetsMs = -1L
    var lastOpenCompactMs = -1L

    /** Whether the open compacted legacy ink (pre-writer-43 file), for the debug overlay. */
    var lastOpenCompacted = false

    /** The opened file's on-disk size at open time (static) and the size of the most recent
     *  save/autosave (live); -1 until a file-backed note is open. For the debug overlay. */
    var openFileBytes = -1L
    var lastSaveBytes = -1L

    /** Last note-save timings (ms), for the debug overlay; -1 until the first save.
     *  [lastSaveTotalMs] is the whole save the editor runs, from snapshot to bookkeeping done;
     *  the other three are its phases: the model deep copy, the encode+deflate into the temp
     *  file, and the temp-to-SAF byte copy. Set by the editor's note-save paths only. */
    var lastSaveTotalMs = -1L
    var lastSaveSnapshotMs = -1L
    var lastSaveEncodeMs = -1L
    var lastSaveCopyMs = -1L

    /** [lastSaveEncodeMs] split in two: the deflated manifest against the stored assets (images and
     *  the embedded PDF) streamed in behind it, which a PDF-backed note re-copies on every save. */
    var lastSaveManifestMs = -1L
    var lastSaveAssetsMs = -1L

    /** Of [lastSaveManifestMs], the part spent compressing rather than generating the JSON, and the
     *  uncompressed size that went into the deflater. */
    var lastSaveDeflateMs = -1L
    var lastSaveManifestBytes = -1L

    /** Autosave status for the debug overlay, set by the editor: "idle", "pending", "in progress",
     *  "done" or "failed". */
    var autosaveStatus = "idle"

    companion object {
        const val MARGIN = 48.0
        const val MIN_ZOOM = 0.12
        const val MAX_ZOOM = 16.0
        const val ZOOM_STEP = 1.25

        /** While a pinch's zoom is within this fraction of [fitWidthZoom] it sticks to fit-to-width. */
        const val SNAP_TO_FIT_WIDTH = 0.05
        const val CTRL_WHEEL_BASE = 1.01

        /** Padding (content px) around a repaired dirty rect, for AA edges. */
        const val EDIT_PAD = 2.0

        /** Gap (viewport px) left above the page top so nothing hides behind the toolbar. */
        const val TOP_GAP = 16.0
        val TRANSPARENT = Rgba(0, 0, 0, 0)
    }
}

/**
 * What the GL ink host needs to hear from [CanvasState]. The paged model has no per-item change
 * events: edits end in one of the invalidations below, which is where these are raised.
 */
interface GlInkBridge {
    /** Page rects or the page list changed. */
    fun layoutChanged()

    /** A page's items changed in some way. */
    fun inkChanged(page: Page, dirty: Rect? = null)

    /** A whole page changed (flow text, rebuild): its ink and its underlay both need refreshing. */
    fun pageInvalidated(page: Page)

    /** One just-committed item joined [page] (the cheap path of an ordinary pen stroke). */
    fun itemAppended(page: Page, item: CanvasItem)

    /** A page's paper, ruling, PDF or flow text changed. */
    fun backgroundChanged(page: Page)

    /** Everything may have changed (margins, styles, a swapped document). */
    fun everythingChanged()
}
