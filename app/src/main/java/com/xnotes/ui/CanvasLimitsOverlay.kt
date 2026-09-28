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
 * Marks the edge of a limited infinite canvas: a dim scrim over everything outside the extent and
 * a border line on it. Purely visual; the scroll clamp lives in [com.xnotes.core.infinite.CanvasViewport].
 * Draws nothing when both axes are infinite.
 */
@Composable
fun CanvasLimitsOverlay(editor: InfiniteEditor) {
    @Suppress("UNUSED_VARIABLE")
    val rev = editor.viewRev // read so the overlay redraws on every pan / zoom / limit change
    val doc = editor.document
    val w = doc.limitW
    val h = doc.limitH
    if (w == null && h == null) return
    val vp = editor.viewport
    Canvas(Modifier.fillMaxSize()) {
        val z = vp.zoom.toFloat()
        val vw = size.width
        val vh = size.height
        // The extent in viewport px; an infinite axis runs past both viewport edges.
        val left = if (w != null) ((0.0 - vp.scrollX) * z).toFloat() else -1e6f
        val right = if (w != null) ((w - vp.scrollX) * z).toFloat() else 1e6f
        val top = if (h != null) ((0.0 - vp.scrollY) * z).toFloat() else -1e6f
        val bottom = if (h != null) ((h - vp.scrollY) * z).toFloat() else 1e6f
        val scrim = Color(0x59000000)
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
        val line = Color(0xCC888888)
        val sw = Stroke(width = 2f)
        if (w != null) {
            drawLine(line, Offset(left, top.coerceIn(0f, vh)), Offset(left, bottom.coerceIn(0f, vh)), sw.width)
            drawLine(line, Offset(right, top.coerceIn(0f, vh)), Offset(right, bottom.coerceIn(0f, vh)), sw.width)
        }
        if (h != null) {
            drawLine(line, Offset(left.coerceIn(0f, vw), top), Offset(right.coerceIn(0f, vw), top), sw.width)
            drawLine(line, Offset(left.coerceIn(0f, vw), bottom), Offset(right.coerceIn(0f, vw), bottom), sw.width)
        }
    }
}
