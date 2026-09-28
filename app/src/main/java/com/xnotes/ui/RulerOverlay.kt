package com.xnotes.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.canvas.RulerMath
import com.xnotes.core.infinite.OverlayTessellator
import com.xnotes.core.measure.CM_PER_INCH
import com.xnotes.core.measure.Measure
import com.xnotes.core.measure.RulerMode
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

/**
 * The two-point ruler over the infinite canvas: the line between its points with graduations in
 * real units, the readout, and the handles.
 *
 * Drawn as Compose over the surface rather than inside the GL frame, like the debug HUD, so it
 * costs the render thread nothing and can draw text. It draws nothing itself that a touch could
 * land on: presses are read by the canvas view underneath and routed through the editor.
 */
@Composable
fun RulerOverlay(editor: InfiniteEditor) {
    if (editor.rulerMode == RulerMode.OFF) return
    val density = LocalDensity.current.density
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD } }
    // Read here so a change recomposes this and hands the canvas below a fresh lambda to draw.
    val rev = editor.rulerRev
    Box(modifier = Modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (rev >= 0) drawPointRuler(editor, density, paint)
        }
        val placed = editor.pointRuler.placed
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 18.dp)
                .background(Color(0xE6FFFFFF), RoundedCornerShape(18.dp))
                .then(if (placed) Modifier.clickable { editor.newRulerLine() } else Modifier)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Text(
                if (placed) "New ruler line" else "Press and drag to measure",
                color = Color(0xFF222222),
                fontSize = 13.sp,
            )
        }
    }
}

private val INK = Color(0xFF1E88E5)
private val HALO = Color(0xF2FFFFFF)

private val METRIC_STEPS = doubleArrayOf(
    0.1, 0.2, 0.5, 1.0, 2.0, 5.0, 10.0, 20.0, 50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0, 10000.0,
)
private val INCH_STEPS = doubleArrayOf(
    0.0625, 0.125, 0.25, 0.5, 1.0, 2.0, 5.0, 10.0, 20.0, 50.0, 100.0, 200.0, 500.0, 1000.0,
)

private fun DrawScope.drawPointRuler(editor: InfiniteEditor, density: Float, paint: Paint) {
    val r = editor.pointRuler
    if (r.lengthPx <= 0.0) return
    val vp = editor.viewport
    val zoom = vp.zoom
    val a = vp.contentToViewport(r.start)
    val b = vp.contentToViewport(r.end)
    val ax = a.x.toFloat()
    val ay = a.y.toFloat()
    val bx = b.x.toFloat()
    val by = b.y.toFloat()
    val lenV = a.distanceTo(b)
    if (lenV < 1e-6) return
    val dx = ((b.x - a.x) / lenV).toFloat()
    val dy = ((b.y - a.y) / lenV).toFloat()
    // The line's normal, the side the graduations sit on.
    val nx = -dy
    val ny = dx

    val lineW = 2f * density
    drawLine(HALO, Offset(ax, ay), Offset(bx, by), strokeWidth = lineW + 3f * density)
    drawLine(INK, Offset(ax, ay), Offset(bx, by), strokeWidth = lineW)

    // Graduations in real units. The smallest step that still leaves room between ticks is used, so
    // zooming in brings in finer ones and zooming out drops to coarser, with labels thinned to fit.
    val inches = editor.useInches
    val unitPx = RulerMath.contentPxPerCm(editor.document.dpi) * zoom * (if (inches) CM_PER_INCH else 1.0)
    val steps = if (inches) INCH_STEPS else METRIC_STEPS
    val minGap = 7.0 * density
    val step = steps.firstOrNull { it * unitPx >= minGap } ?: steps.last()
    val stepPx = step * unitPx
    val labelEvery = ceil(64.0 * density / stepPx).toInt().coerceAtLeast(1)
    val count = min((lenV / stepPx).toInt(), 3000)
    val nc = drawContext.canvas.nativeCanvas
    paint.textSize = 11f * density
    for (i in 0..count) {
        val s = (i * stepPx).toFloat()
        val px = ax + dx * s
        val py = ay + dy * s
        val major = i % labelEvery == 0
        val tick = (if (major) 11f else 5f) * density
        val ex = px + nx * tick
        val ey = py + ny * tick
        drawLine(HALO, Offset(px, py), Offset(ex, ey), strokeWidth = 2.6f * density)
        drawLine(INK, Offset(px, py), Offset(ex, ey), strokeWidth = 1.2f * density)
        if (major) {
            val text = trimmed(i * step)
            val lx = px + nx * (tick + 9f * density)
            val ly = py + ny * (tick + 9f * density)
            outlinedText(nc, paint, text, lx, ly, density)
        }
    }

    // Handles: the two ends, the pivot the ruler turns about, and the grip that turns it.
    val end = 7f * density
    for (p in listOf(Offset(ax, ay), Offset(bx, by))) {
        drawCircle(HALO, end + 2f * density, p)
        drawCircle(INK, end, p)
    }
    if (r.placed) {
        val pv = vp.contentToViewport(r.pivot())
        val pc = Offset(pv.x.toFloat(), pv.y.toFloat())
        val arm = 9f * density
        for (axis in listOf(Offset(dx, dy), Offset(nx, ny))) {
            val p1 = Offset(pc.x - axis.x * arm, pc.y - axis.y * arm)
            val p2 = Offset(pc.x + axis.x * arm, pc.y + axis.y * arm)
            drawLine(HALO, p1, p2, strokeWidth = 3.4f * density)
            drawLine(INK, p1, p2, strokeWidth = 1.4f * density)
        }
        val gripArm = OverlayTessellator.GRIP_ARM_PX.toFloat()
        val gc = Offset(pc.x + nx * gripArm, pc.y + ny * gripArm)
        drawLine(INK, pc, gc, strokeWidth = 1.2f * density)
        drawCircle(HALO, 9f * density, gc)
        drawCircle(INK, 7f * density, gc)
        drawCircle(Color.White, 3f * density, gc)
    }

    // The reading, beside the middle of the line on the side opposite the graduations.
    var deg = -Math.toDegrees(r.angle())
    deg = ((deg % 360.0) + 360.0) % 360.0
    val reading = Measure.format(r.lengthPx, editor.document.dpi, inches) + "   " + "%.1f°".format(deg)
    val mx = (ax + bx) / 2f - nx * 22f * density
    val my = (ay + by) / 2f - ny * 22f * density
    paint.textSize = 15f * density
    outlinedText(nc, paint, reading, mx, my, density)
}

/** Text with a light outline, so it reads over ink of any colour. */
private fun outlinedText(nc: android.graphics.Canvas, paint: Paint, text: String, cx: Float, cy: Float, density: Float) {
    val x = cx - paint.measureText(text) / 2f
    val y = cy + paint.textSize / 3f
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 3f * density
    paint.color = HALO.toArgb()
    nc.drawText(text, x, y, paint)
    paint.style = Paint.Style.FILL
    paint.color = INK.toArgb()
    nc.drawText(text, x, y, paint)
}

private fun trimmed(v: Double): String =
    if (v == floor(v)) v.toLong().toString() else "%.4f".format(v).trimEnd('0').trimEnd('.')
