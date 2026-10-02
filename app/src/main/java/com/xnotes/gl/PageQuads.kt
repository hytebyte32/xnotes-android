package com.xnotes.gl

import com.xnotes.core.geometry.Rect

/** One page's place in the plane and what to draw for it. */
class PageQuad(
    /** Identity of the page's texture in the underlay. */
    val key: Any,
    /** Where the page sits in content space. */
    val rect: Rect,
    /** Bumped whenever what the page's underlay shows changes; the underlay re-renders on a mismatch. */
    val version: Long,
    /** Packed 0xRRGGBB shown until the texture arrives, so a page never flashes the desk colour. */
    val paperRgb: Int,
)

/** Pure placement maths for page quads, so it tests without a GL context. */
object PageQuads {

    /**
     * Clip-space corners of [rect] (top-left, top-right, bottom-left, bottom-right) for a view at
     * [scrollX]/[scrollY]/[zoom] on a [widthPx] by [heightPx] surface. Worked out in doubles, as the
     * image path does, so it stays exact however far down a long document the page sits.
     */
    fun corners(
        rect: Rect,
        zoom: Double,
        scrollX: Double,
        scrollY: Double,
        widthPx: Int,
        heightPx: Int,
    ): FloatArray {
        val l = (rect.left - scrollX) * zoom
        val t = (rect.top - scrollY) * zoom
        val r = (rect.right - scrollX) * zoom
        val b = (rect.bottom - scrollY) * zoom
        fun cx(px: Double) = (px / widthPx * 2.0 - 1.0).toFloat()
        fun cy(px: Double) = (1.0 - px / heightPx * 2.0).toFloat()
        return floatArrayOf(cx(l), cy(t), cx(r), cy(t), cx(l), cy(b), cx(r), cy(b))
    }

    /** The pages whose rect meets the view grown by [marginPx] device pixels, in the given order. */
    fun inView(
        pages: List<PageQuad>,
        zoom: Double,
        scrollX: Double,
        scrollY: Double,
        widthPx: Int,
        heightPx: Int,
        marginPx: Double = 0.0,
    ): List<PageQuad> {
        val m = marginPx / zoom
        val vl = scrollX - m
        val vt = scrollY - m
        val vr = scrollX + widthPx / zoom + m
        val vb = scrollY + heightPx / zoom + m
        return pages.filter { it.rect.right > vl && it.rect.left < vr && it.rect.bottom > vt && it.rect.top < vb }
    }
}
