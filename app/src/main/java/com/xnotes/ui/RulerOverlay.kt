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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.canvas.RulerButton
import com.xnotes.canvas.RulerMath
import com.xnotes.core.geometry.Geometry
import com.xnotes.core.geometry.Pt
import com.xnotes.core.measure.CM_PER_INCH
import com.xnotes.core.measure.Measure
import com.xnotes.core.measure.Protractor
import com.xnotes.core.measure.RulerMode
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

/**
 * The measuring rulers over a canvas, drawn from a [MeasureController]: the screen-fixed band or the
 * two-point line, with real-unit graduations, readouts and handles.
 *
 * Drawn as Compose over the surface rather than inside the render frame, like the debug HUD, so it
 * costs the renderer nothing and can draw text. It draws nothing that a touch could land on:
 * presses are read by the canvas view underneath and forwarded to the controller.
 */
@Composable
fun RulerOverlay(measure: MeasureController) {
    val mode = measure.mode
    if (mode == RulerMode.OFF) return
    val density = LocalDensity.current.density
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD } }
    // Read here so a change recomposes this and hands the canvas below a fresh lambda to draw.
    val rev = measure.rev
    Box(modifier = Modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (rev < 0) return@Canvas
            when (mode) {
                RulerMode.BAND -> drawBand(measure, density, paint)
                RulerMode.TWO_POINT -> drawPointRuler(measure, density, paint)
                RulerMode.PROTRACTOR -> drawProtractor(measure, density, paint)
                RulerMode.OFF -> Unit
            }
        }
        if (mode == RulerMode.TWO_POINT) {
            val placed = measure.point.placed
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 18.dp)
                    .background(Color(0xE6FFFFFF), RoundedCornerShape(18.dp))
                    .then(if (placed) Modifier.clickable { measure.newLine() } else Modifier)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    if (placed) "New ruler line" else "Press and drag to measure",
                    color = Color(0xFF222222),
                    fontSize = 13.sp,
                )
            }
        }
        if (mode == RulerMode.PROTRACTOR) {
            val phase = measure.protractor.phase
            val hint = when (phase) {
                Protractor.Phase.IDLE, Protractor.Phase.BASELINE -> "Press the centre and drag out the baseline"
                Protractor.Phase.AWAIT_ARC -> "Now draw the arc from the baseline  \u2022  tap here to start over"
                Protractor.Phase.ARC -> ""
            }
            if (hint.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 18.dp)
                        .background(Color(0xE6FFFFFF), RoundedCornerShape(18.dp))
                        .then(if (phase == Protractor.Phase.AWAIT_ARC) Modifier.clickable { measure.newProtractor() } else Modifier)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text(hint, color = Color(0xFF222222), fontSize = 13.sp)
                }
            }
        }
    }
}

private val INK = Color(0xFF1E88E5)
private val HALO = Color(0xF2FFFFFF)
private val BAND_FILL = Color(0x80FFFFFF)
private val BAND_EDGE = Color(0xFF6B6B6B)
private val BAND_TICK = Color(0xFF262626)

private val METRIC_STEPS = doubleArrayOf(
    0.1, 0.2, 0.5, 1.0, 2.0, 5.0, 10.0, 20.0, 50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0, 10000.0,
)
private val INCH_STEPS = doubleArrayOf(
    0.0625, 0.125, 0.25, 0.5, 1.0, 2.0, 5.0, 10.0, 20.0, 50.0, 100.0, 200.0, 500.0, 1000.0,
)

/** Graduation spacing in real units: the smallest step that leaves room, and how often to label. */
private class Graduation(val step: Double, val stepPx: Double, val labelEvery: Int)

private fun graduation(m: MeasureController, density: Float): Graduation {
    val inches = m.useInches
    val unitPx = RulerMath.contentPxPerCm(m.dpi) * m.zoom * (if (inches) CM_PER_INCH else 1.0)
    val steps = if (inches) INCH_STEPS else METRIC_STEPS
    val minGap = 7.0 * density
    val step = steps.firstOrNull { it * unitPx >= minGap } ?: steps.last()
    val stepPx = step * unitPx
    val labelEvery = ceil(64.0 * density / stepPx).toInt().coerceAtLeast(1)
    return Graduation(step, stepPx, labelEvery)
}

private fun DrawScope.drawPointRuler(m: MeasureController, density: Float, paint: Paint) {
    val r = m.point
    if (r.lengthPx <= 0.0) return
    val a = m.toViewportPt(r.start)
    val b = m.toViewportPt(r.end)
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

    val g = graduation(m, density)
    val count = min((lenV / g.stepPx).toInt(), 3000)
    val nc = drawContext.canvas.nativeCanvas
    paint.textSize = 11f * density
    for (i in 0..count) {
        val s = (i * g.stepPx).toFloat()
        val px = ax + dx * s
        val py = ay + dy * s
        val major = i % g.labelEvery == 0
        val tick = (if (major) 11f else 5f) * density
        val ex = px + nx * tick
        val ey = py + ny * tick
        drawLine(HALO, Offset(px, py), Offset(ex, ey), strokeWidth = 2.6f * density)
        drawLine(INK, Offset(px, py), Offset(ex, ey), strokeWidth = 1.2f * density)
        if (major) {
            val lx = px + nx * (tick + 9f * density)
            val ly = py + ny * (tick + 9f * density)
            outlinedText(nc, paint, trimmed(i * g.step), lx, ly, density, INK)
        }
    }

    // Handles: the two ends, the pivot the ruler turns about, and the grip that turns it.
    val end = 7f * density
    for (p in listOf(Offset(ax, ay), Offset(bx, by))) {
        drawCircle(HALO, end + 2f * density, p)
        drawCircle(INK, end, p)
    }
    if (r.placed) {
        val pv = m.toViewportPt(r.pivot())
        val pc = Offset(pv.x.toFloat(), pv.y.toFloat())
        crosshair(pc, Offset(dx, dy), Offset(nx, ny), 9f * density, density, INK)
        val gripArm = MeasureController.GRIP_ARM_PX.toFloat()
        val gc = Offset(pc.x + nx * gripArm, pc.y + ny * gripArm)
        drawLine(INK, pc, gc, strokeWidth = 1.2f * density)
        drawCircle(HALO, 9f * density, gc)
        drawCircle(INK, 7f * density, gc)
        drawCircle(Color.White, 3f * density, gc)
    }

    // The reading, beside the middle of the line on the side opposite the graduations.
    var deg = -Math.toDegrees(r.angle())
    deg = ((deg % 360.0) + 360.0) % 360.0
    val reading = Measure.format(r.lengthPx, m.dpi, m.useInches) + "   " + "%.1f°".format(deg)
    val mx = (ax + bx) / 2f - nx * 22f * density
    val my = (ay + by) / 2f - ny * 22f * density
    paint.textSize = 15f * density
    outlinedText(nc, paint, reading, mx, my, density, INK)
}

/** A small cross on [c] along the two axes [u] and [v], over a halo so it reads on any ground. */
private fun DrawScope.crosshair(c: Offset, u: Offset, v: Offset, arm: Float, density: Float, color: Color) {
    for (axis in listOf(u, v)) {
        val p1 = Offset(c.x - axis.x * arm, c.y - axis.y * arm)
        val p2 = Offset(c.x + axis.x * arm, c.y + axis.y * arm)
        drawLine(HALO, p1, p2, strokeWidth = 3.4f * density)
        drawLine(color, p1, p2, strokeWidth = 1.4f * density)
    }
}

private fun DrawScope.drawBand(m: MeasureController, density: Float, paint: Paint) {
    val ruler = m.band
    val (vw, vh) = m.size
    val d = ruler.direction()
    // Visible along-range: project the four viewport corners onto the band's length axis.
    var sMin = Double.MAX_VALUE
    var sMax = -Double.MAX_VALUE
    for (c in listOf(Pt(0.0, 0.0), Pt(vw, 0.0), Pt(0.0, vh), Pt(vw, vh))) {
        val s = Geometry.dot(c - ruler.center, d)
        if (s < sMin) sMin = s
        if (s > sMax) sMax = s
    }
    sMin -= 4.0 * density
    sMax += 4.0 * density

    val quad = ruler.bodyQuad(sMin, sMax)
    val path = Path().apply {
        moveTo(quad[0].x.toFloat(), quad[0].y.toFloat())
        for (i in 1..3) lineTo(quad[i].x.toFloat(), quad[i].y.toFloat())
        close()
    }
    drawPath(path, BAND_FILL)
    drawLine(BAND_EDGE, Offset(quad[0].x.toFloat(), quad[0].y.toFloat()), Offset(quad[1].x.toFloat(), quad[1].y.toFloat()), strokeWidth = 1.2f * density)
    drawLine(BAND_EDGE, Offset(quad[3].x.toFloat(), quad[3].y.toFloat()), Offset(quad[2].x.toFloat(), quad[2].y.toFloat()), strokeWidth = 1.2f * density)

    // Graduations on both long edges, zero at the centre.
    val n = ruler.normal()
    val ht = ruler.thicknessPx / 2.0
    val g = graduation(m, density)
    val nc = drawContext.canvas.nativeCanvas
    paint.textSize = 11f * density
    val iMin = ceil(sMin / g.stepPx).toInt()
    val iMax = floor(sMax / g.stepPx).toInt()
    if (iMax - iMin <= 4000) {
        for (i in iMin..iMax) {
            val mid = ruler.center + d * (i * g.stepPx)
            val major = i % g.labelEvery == 0
            val len = if (major) ht * 0.46 else ht * 0.22
            val top = mid + n * ht
            val bot = mid - n * ht
            val topIn = top - n * len
            val botIn = bot + n * len
            drawLine(BAND_TICK, Offset(top.x.toFloat(), top.y.toFloat()), Offset(topIn.x.toFloat(), topIn.y.toFloat()), strokeWidth = 1.1f * density)
            drawLine(BAND_TICK, Offset(bot.x.toFloat(), bot.y.toFloat()), Offset(botIn.x.toFloat(), botIn.y.toFloat()), strokeWidth = 1.1f * density)
            if (major) outlinedText(nc, paint, trimmed(abs(i) * g.step), mid.x.toFloat(), mid.y.toFloat(), density, BAND_TICK)
        }
    }

    // Lock buttons, straddling the centre.
    val br = ruler.buttonRadiusPx().toFloat()
    for ((btn, c) in ruler.buttonCenters()) {
        val on = when (btn) {
            RulerButton.LOCK_POS -> ruler.lockPosition
            RulerButton.LOCK_ANGLE -> ruler.lockAngle
        }
        val oc = Offset(c.x.toFloat(), c.y.toFloat())
        drawCircle(if (on) INK else HALO, br, oc)
        drawCircle(BAND_EDGE, br, oc, style = androidx.compose.ui.graphics.drawscope.Stroke(1.3f * density))
        val glyph = if (on) Color.White else BAND_TICK
        paint.textSize = br * 1.1f
        outlinedText(nc, paint, if (btn == RulerButton.LOCK_POS) "P" else "∠", oc.x, oc.y, density, glyph, halo = false)
    }

    // Rotation handles with a permanent angle readout each, then the crosshair on the zero.
    val dist = m.bandHandleDist()
    val hr = ruler.handleRadiusPx().toFloat()
    val phi = Math.toDegrees(kotlin.math.atan2(d.y, d.x))
    val ccwFromPlusX = ((-phi) % 360 + 360) % 360
    val cwFromMinusX = (phi % 360 + 360) % 360
    val handles = ruler.handleCenters(dist)
    for (i in handles.indices) {
        val h = handles[i]
        val hc = Offset(h.x.toFloat(), h.y.toFloat())
        drawCircle(HALO, hr, hc)
        drawCircle(BAND_EDGE, hr, hc, style = androidx.compose.ui.graphics.drawscope.Stroke(1.6f * density))
        val outward = (h - ruler.center).normalized()
        val deg = if (i == 0) ccwFromPlusX else cwFromMinusX
        paint.textSize = 13f * density
        val lx = (h.x + outward.x * (hr + 26.0 * density)).toFloat()
        val ly = (h.y + outward.y * (hr + 26.0 * density)).toFloat()
        outlinedText(nc, paint, "%.0f°".format(deg), lx, ly, density, BAND_TICK)
    }
    val cc = Offset(ruler.center.x.toFloat(), ruler.center.y.toFloat())
    crosshair(cc, Offset(d.x.toFloat(), d.y.toFloat()), Offset(n.x.toFloat(), n.y.toFloat()), 10f * density, density, BAND_TICK)
}

/** Text with a light outline, so it reads over ink of any colour. */
private fun outlinedText(
    nc: android.graphics.Canvas,
    paint: Paint,
    text: String,
    cx: Float,
    cy: Float,
    density: Float,
    color: Color,
    halo: Boolean = true,
) {
    val x = cx - paint.measureText(text) / 2f
    val y = cy + paint.textSize / 3f
    if (halo) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f * density
        paint.color = HALO.toArgb()
        nc.drawText(text, x, y, paint)
    }
    paint.style = Paint.Style.FILL
    paint.color = color.toArgb()
    nc.drawText(text, x, y, paint)
}

private fun trimmed(v: Double): String =
    if (v == floor(v)) v.toLong().toString() else "%.4f".format(v).trimEnd('0').trimEnd('.')

private fun DrawScope.drawProtractor(m: MeasureController, density: Float, paint: Paint) {
    val p = m.protractor
    if (p.phase == Protractor.Phase.IDLE) return
    val c = m.toViewportPt(p.centre)
    val b = m.toViewportPt(p.baseEnd)
    val co = Offset(c.x.toFloat(), c.y.toFloat())
    val bo = Offset(b.x.toFloat(), b.y.toFloat())
    // The baseline, with the centre marked.
    drawLine(HALO, co, bo, strokeWidth = 5f * density)
    drawLine(INK, co, bo, strokeWidth = 2f * density)
    drawCircle(HALO, 6f * density, co)
    drawCircle(INK, 4f * density, co)
    drawCircle(HALO, 5f * density, bo)
    drawCircle(INK, 3.5f * density, bo)
    if (p.phase != Protractor.Phase.ARC) return

    // The arc as it will be committed: clean, round, and as long as the sweep so far.
    val pts = p.arcPoints().map { m.toViewportPt(it) }
    val path = Path().apply {
        moveTo(pts[0].x.toFloat(), pts[0].y.toFloat())
        for (i in 1 until pts.size) lineTo(pts[i].x.toFloat(), pts[i].y.toFloat())
    }
    drawPath(path, HALO, style = androidx.compose.ui.graphics.drawscope.Stroke(5f * density))
    drawPath(path, INK, style = androidx.compose.ui.graphics.drawscope.Stroke(2f * density))
    // The radial line to the arc's far end, so the swept wedge reads.
    val last = pts.last()
    val lo = Offset(last.x.toFloat(), last.y.toFloat())
    drawLine(INK, co, lo, strokeWidth = 1.2f * density)

    // The angle, out past the middle of the arc.
    val mid = pts[pts.size / 2]
    val away = Pt(mid.x - c.x, mid.y - c.y)
    val len = away.length()
    if (len > 1e-6) {
        val tx = (mid.x + away.x / len * 26.0 * density).toFloat()
        val ty = (mid.y + away.y / len * 26.0 * density).toFloat()
        paint.textSize = 17f * density
        val nc = drawContext.canvas.nativeCanvas
        val text = "%.${m.protractorDecimals}f°".format(p.angleDegrees())
        outlinedText(nc, paint, text, tx, ty, density, INK)
    }
}
