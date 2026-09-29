package com.xnotes.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.runtime.Composable

/**
 * Marks the edge of the canvas: an opaque desk colour over everything outside the page and
 * a border line on it. Purely visual; the scroll clamp lives in [com.xnotes.core.infinite.CanvasViewport].
 * The top and left edges always show, since the canvas starts there.
 */
@Composable
fun CanvasLimitsOverlay(editor: InfiniteEditor) {
    @Suppress("UNUSED_VARIABLE")
    val rev = editor.viewRev // read so the overlay redraws on every pan / zoom / limit change
    val doc = editor.document
    val w = doc.limitW
    val h = doc.limitH
    val vp = editor.viewport
    Canvas(Modifier.fillMaxSize()) {
        val z = vp.zoom.toFloat()
        val vw = size.width
        val vh = size.height
        // The extent in viewport px; an infinite axis runs past both viewport edges.
        val left = ((doc.originX - vp.scrollX) * z).toFloat()
        val right = if (w != null) ((doc.originX + w - vp.scrollX) * z).toFloat() else 1e6f
        val top = ((doc.originY - vp.scrollY) * z).toFloat()
        val bottom = if (h != null) ((doc.originY + h - vp.scrollY) * z).toFloat() else 1e6f
        // Beyond the page is an opaque desk, not a tint over the grid, so the page reads as a page and
        // its size is obvious at every zoom. The desk contrasts with the paper, light on dark and back.
        val paper = editor.view.paperColor
        val lum = (0.299 * paper.r + 0.587 * paper.g + 0.114 * paper.b) / 255.0
        val scrim = if (lum > 0.5) Color(0xFF8E8E93) else Color(0xFF0B0B0D)
        // Four bands around the extent, clipped to the viewport.
        fun band(x0: Float, y0: Float, x1: Float, y1: Float) {
            val a = x0.coerceIn(0f, vw)
            val b = y0.coerceIn(0f, vh)
            val c = x1.coerceIn(0f, vw)
            val d = y1.coerceIn(0f, vh)
            if (c > a && d > b) drawRect(scrim, Offset(a, b), Size(c - a, d - b))
        }
        band(0f, 0f, vw, top) // above
        band(0f, bottom, vw, vh) // below
        band(0f, top, left, bottom) // left
        band(right, top, vw, bottom) // right
        val line = if (lum > 0.5) Color(0xFF5A5A5F) else Color(0xFF6A6A70)
        val sw = Stroke(width = 3f)
        // The top and left edges always exist; the far edges only on a limited axis.
        val yLo = top.coerceIn(0f, vh)
        val yHi = bottom.coerceIn(0f, vh)
        val xLo = left.coerceIn(0f, vw)
        val xHi = right.coerceIn(0f, vw)
        drawLine(line, Offset(left, yLo), Offset(left, yHi), sw.width)
        drawLine(line, Offset(xLo, top), Offset(xHi, top), sw.width)
        if (w != null) drawLine(line, Offset(right, yLo), Offset(right, yHi), sw.width)
        if (h != null) drawLine(line, Offset(xLo, bottom), Offset(xHi, bottom), sw.width)
    }
}
