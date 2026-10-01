package com.xnotes.core.infinite

import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Orientation
import com.xnotes.core.model.PageSize
import kotlin.math.max

/**
 * Splits an infinite canvas into PDF pages without cutting the work up.
 *
 * The canvas runs on forever along one axis (down for an endless height, across for an endless
 * width, down when both are endless). Items are merged into *blocks*: runs along that axis where
 * something is drawn. Blocks are separated by empty gaps, and a page boundary only ever falls in
 * a gap. Pages are packed greedily: each starts at the top of the next block and takes in as many
 * whole blocks as fit, so page space is used rather than cut at fixed intervals.
 *
 * A single block taller than a page is scaled down to fit one page rather than split, up to
 * [MAX_SHRINK] times its length; past that, shrinking would make it unreadable, so it is cut where
 * the fewest items cross the line, as near the end of the page as it can be.
 *
 * Page size comes from the canvas: a limited axis sets that side of the page and the other side is
 * the A4 ratio. An endless-by-endless canvas gets A4 portrait. Pure, so it unit-tests.
 */
object CanvasPagination {

    /** One page: the content rectangle it shows, and points per content pixel. */
    data class Page(val cover: Rect, val scale: Double) {
        val widthPoints: Double get() = (cover.w * scale).coerceAtLeast(1.0)
        val heightPoints: Double get() = (cover.h * scale).coerceAtLeast(1.0)
    }

    /** How much longer than a page a block may be and still be shrunk to fit rather than cut. */
    const val MAX_SHRINK = 1.6

    private const val A4_RATIO = 297.0 / 210.0
    private const val A4_WIDTH_PTS = 595.276
    private const val A4_HEIGHT_PTS = 841.89

    /** Whitespace kept round a page's content, in inches. */
    private const val PAD_INCHES = 0.2

    fun plan(
        bounds: List<Rect>,
        dpi: Int,
        originX: Double,
        originY: Double,
        limitW: Double?,
        limitH: Double?,
    ): List<Page> {
        val safeDpi = if (dpi > 0) dpi else PageSize.DEFAULT_DPI
        val base = 72.0 / safeDpi
        val items = bounds.filter { it.isFinite() && it.w >= 0.0 && it.h >= 0.0 }

        // Both sides fixed: the canvas is one page, whatever is on it.
        if (limitW != null && limitH != null) {
            return listOf(capped(Rect(originX, originY, limitW, limitH), base))
        }
        if (items.isEmpty()) {
            val (w, h) = PageSize.A4.pixels(Orientation.PORTRAIT, safeDpi)
            return listOf(Page(Rect(originX, originY, w, h), base))
        }

        val vertical = limitH == null
        val pad = PAD_INCHES * safeDpi
        // Axis helpers: "along" is the endless direction pages are stacked in, "cross" is the other.
        fun aLo(r: Rect) = if (vertical) r.top else r.left
        fun aHi(r: Rect) = if (vertical) r.bottom else r.right
        fun cLo(r: Rect) = if (vertical) r.left else r.top
        fun cHi(r: Rect) = if (vertical) r.right else r.bottom

        val crossMin = items.minOf { cLo(it) }
        val crossMax = items.maxOf { cHi(it) }
        val limitedCross = if (vertical) limitW else limitH

        // Page cross length in content px, and the scale: a limited side is taken as set (1:1); an
        // endless side is A4's width, shrinking only when the content is wider than that.
        val crossStart: Double
        val crossLen: Double
        val scale: Double
        if (limitedCross != null) {
            crossStart = if (vertical) originX else originY
            crossLen = limitedCross
            scale = base
        } else {
            val a4cross = (if (vertical) A4_WIDTH_PTS else A4_HEIGHT_PTS) / base
            val need = crossMax - crossMin + 2 * pad
            crossStart = crossMin - pad
            crossLen = max(a4cross, need)
            scale = (if (vertical) A4_WIDTH_PTS else A4_HEIGHT_PTS) / crossLen
        }
        // Page along-length in content px: the A4 ratio of the cross length.
        val pageLen = if (limitedCross != null) crossLen * A4_RATIO
        else (if (vertical) A4_HEIGHT_PTS else A4_WIDTH_PTS) / scale

        val blocks = blocks(items.map { aLo(it) to aHi(it) })
        val pages = ArrayList<Page>()

        fun cover(alongStart: Double, alongLen: Double, s: Double): Page {
            val crossL = crossLenFor(vertical, limitedCross, crossLen, s, scale)
            val c = if (vertical) Rect(crossStart, alongStart, crossL, alongLen) else Rect(alongStart, crossStart, alongLen, crossL)
            return Page(c, s)
        }

        var i = 0
        while (i < blocks.size) {
            val start = blocks[i].first - pad
            val firstLen = blocks[i].second + pad - start
            if (firstLen > pageLen) {
                if (firstLen <= pageLen * MAX_SHRINK) {
                    // Too tall for a page but not hopeless: shrink it onto one page, uncut.
                    val s = scale * pageLen / firstLen
                    pages.add(cover(start, firstLen, s))
                    i++
                } else {
                    // Taller than a shrunk page can read: cut it where the fewest items cross.
                    val end = blocks[i].second + pad
                    var from = start
                    while (end - from > pageLen) {
                        val cut = cheapestCut(items.map { aLo(it) to aHi(it) }, from + pageLen * 0.6, from + pageLen)
                        pages.add(cover(from, pageLen, scale))
                        from = cut
                    }
                    pages.add(cover(from, pageLen, scale))
                    i++
                }
                continue
            }
            // Take in whole blocks while they fit.
            var j = i
            while (j + 1 < blocks.size && blocks[j + 1].second + pad - start <= pageLen) j++
            pages.add(cover(start, pageLen, scale))
            i = j + 1
        }
        return pages
    }

    /** The cross length of a page at scale [s]: a shrunk page shows more across to keep its point size. */
    private fun crossLenFor(vertical: Boolean, limitedCross: Double?, crossLen: Double, s: Double, scale: Double): Double =
        if (s == scale) crossLen else crossLen * scale / s

    /** Merge the along-axis spans of every item into disjoint blocks, sorted. */
    internal fun blocks(spans: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
        val sorted = spans.sortedBy { it.first }
        val out = ArrayList<Pair<Double, Double>>()
        for ((lo, hi) in sorted) {
            val last = out.lastOrNull()
            if (last != null && lo <= last.second) out[out.size - 1] = last.first to max(last.second, hi)
            else out.add(lo to hi)
        }
        return out
    }

    /** The position in [lo]..[hi] crossed by the fewest item spans, preferring the one nearest [hi]. */
    internal fun cheapestCut(spans: List<Pair<Double, Double>>, lo: Double, hi: Double, steps: Int = 64): Double {
        var best = hi
        var bestCount = Int.MAX_VALUE
        for (k in 0..steps) {
            val p = hi - (hi - lo) * k / steps
            val crossing = spans.count { it.first < p && it.second > p }
            if (crossing < bestCount) {
                bestCount = crossing
                best = p
            }
        }
        return best
    }

    /** One page for [r] at 1:1, shrunk uniformly if it would pass PDF's page-size ceiling. */
    private fun capped(r: Rect, base: Double): Page {
        val over = max(r.w * base, r.h * base) / CanvasPdfLayout.MAX_PAGE_POINTS
        return Page(r, if (over > 1.0) base / over else base)
    }
}
