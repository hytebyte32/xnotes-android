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
import com.xnotes.canvas.RulerMath
import com.xnotes.core.geometry.Pt
import com.xnotes.core.measure.CM_PER_INCH
import com.xnotes.core.measure.Measure
import com.xnotes.core.measure.Protractor
import com.xnotes.core.measure.RulerMode
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

/**
 * The measuring rulers over a canvas, drawn from a [MeasureController]: the two-point ruler or the
 * protractor, with real-unit graduations, readouts and handles.
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

private fun Pt.o() = Offset(x.toFloat(), y.toFloat())

/**
 * The two-point ruler, dressed as a frosted strip along the line: a translucent body, a line along
 * each edge, real-unit graduations on both edges counted from the start, a handle at each end, a
 * grip beside it that turns it about the start, and its reading past the far end.
 */
private fun DrawScope.drawPointRuler(m: MeasureController, density: Float, paint: Paint) {
    val r = m.point
    if (r.lengthPx <= 0.0) return
    val a = m.toViewportPt(r.start)
    val b = m.toViewportPt(r.end)
    val lenV = a.distanceTo(b)
    if (lenV < 1e-6) return
    val d = Pt((b.x - a.x) / lenV, (b.y - a.y) / lenV)
    val n = d.perp()
    val ht = m.stripPx() / 2.0

    // Body and edges.
    val q0 = a + n * ht
    val q1 = b + n * ht
    val q2 = b - n * ht
    val q3 = a - n * ht
    val body = Path().apply {
        moveTo(q0.x.toFloat(), q0.y.toFloat())
        lineTo(q1.x.toFloat(), q1.y.toFloat())
        lineTo(q2.x.toFloat(), q2.y.toFloat())
        lineTo(q3.x.toFloat(), q3.y.toFloat())
        close()
    }
    drawPath(body, BAND_FILL)
    val edgeW = 1.2f * density
    drawLine(BAND_EDGE, q0.o(), q1.o(), strokeWidth = edgeW)
    drawLine(BAND_EDGE, q3.o(), q2.o(), strokeWidth = edgeW)
    drawLine(BAND_EDGE, q0.o(), q3.o(), strokeWidth = edgeW)
    drawLine(BAND_EDGE, q1.o(), q2.o(), strokeWidth = edgeW)

    // Graduations on both long edges, zero at the start.
    val g = graduation(m, density)
    val count = min((lenV / g.stepPx).toInt(), 4000)
    val nc = drawContext.canvas.nativeCanvas
    paint.textSize = 11f * density
    for (i in 0..count) {
        val mid = a + d * (i * g.stepPx)
        val major = i % g.labelEvery == 0
        val len = if (major) ht * 0.46 else ht * 0.22
        val top = mid + n * ht
        val bot = mid - n * ht
        drawLine(BAND_TICK, top.o(), (top - n * len).o(), strokeWidth = 1.1f * density)
        drawLine(BAND_TICK, bot.o(), (bot + n * len).o(), strokeWidth = 1.1f * density)
        if (major) outlinedText(nc, paint, trimmed(i * g.step), mid.x.toFloat(), mid.y.toFloat(), density, BAND_TICK)
    }

    // End handles, then the rotate grip beside the middle.
    val hr = 9f * density
    for (p in listOf(a, b)) {
        drawCircle(HALO, hr, p.o())
        drawCircle(BAND_EDGE, hr, p.o(), style = androidx.compose.ui.graphics.drawscope.Stroke(1.6f * density))
    }
    val mid = (a + b) * 0.5
    val grip = mid + n * m.gripArmPx()
    drawLine(BAND_EDGE, (mid + n * ht).o(), grip.o(), strokeWidth = edgeW)
    drawCircle(HALO, hr, grip.o())
    drawCircle(BAND_EDGE, hr, grip.o(), style = androidx.compose.ui.graphics.drawscope.Stroke(1.6f * density))
    paint.textSize = 12f * density
    outlinedText(nc, paint, "↻", grip.x.toFloat(), grip.y.toFloat(), density, BAND_TICK, halo = false)

    // The zero point, marked with a crosshair along the strip's own axes.
    crosshair(a.o(), Offset(d.x.toFloat(), d.y.toFloat()), Offset(n.x.toFloat(), n.y.toFloat()), 10f * density, density, BAND_TICK)

    // The reading, past the far end along the line.
    var deg = -Math.toDegrees(r.angle())
    deg = ((deg % 360.0) + 360.0) % 360.0
    val reading = Measure.format(r.lengthPx, m.dpi, m.useInches) + "   " + "%.1f°".format(deg)
    paint.textSize = 15f * density
    val off = hr + 14f * density + paint.measureText(reading) / 2f
    outlinedText(nc, paint, reading, (b.x + d.x * off).toFloat(), (b.y + d.y * off).toFloat(), density, BAND_TICK)
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

/**
 * The protractor in the same dress: a frosted wedge from the baseline round to the arc, dark edge
 * lines, degree graduations along the arc, handles on the centre and baseline end, and the angle
 * beside the vertex rather than out at the rim.
 */
private fun DrawScope.drawProtractor(m: MeasureController, density: Float, paint: Paint) {
    val p = m.protractor
    if (p.phase == Protractor.Phase.IDLE) return
    val c = m.toViewportPt(p.centre)
    val b = m.toViewportPt(p.baseEnd)
    val nc = drawContext.canvas.nativeCanvas
    val edgeW = 1.6f * density
    val hr = 9f * density

    var wedge: List<Pt>? = null
    if (p.phase == Protractor.Phase.ARC) {
        val pts = p.arcPoints().map { m.toViewportPt(it) }
        wedge = pts
        // The swept wedge: the centre, then along the arc.
        val fill = Path().apply {
            moveTo(c.x.toFloat(), c.y.toFloat())
            for (q in pts) lineTo(q.x.toFloat(), q.y.toFloat())
            close()
        }
        drawPath(fill, BAND_FILL)
        val arcPath = Path().apply {
            moveTo(pts[0].x.toFloat(), pts[0].y.toFloat())
            for (i in 1 until pts.size) lineTo(pts[i].x.toFloat(), pts[i].y.toFloat())
        }
        drawPath(arcPath, HALO, style = androidx.compose.ui.graphics.drawscope.Stroke(edgeW + 3f * density))
        drawPath(arcPath, BAND_EDGE, style = androidx.compose.ui.graphics.drawscope.Stroke(edgeW))
        drawLine(BAND_EDGE, c.o(), pts.last().o(), strokeWidth = edgeW)

        // Degree graduations inward from the arc: every degree the size allows, every ten longer.
        val radiusV = p.radius * m.zoom
        val whole = Math.toDegrees(kotlin.math.abs(p.sweep))
        val perDeg = radiusV * Math.PI / 180.0
        val stepDeg = when {
            perDeg >= 5.0 * density -> 1
            perDeg * 5 >= 5.0 * density -> 5
            else -> 10
        }
        val a0 = p.baseAngle()
        val sign = if (p.sweep >= 0) 1.0 else -1.0
        var deg = 0
        while (deg <= whole + 1e-9) {
            val ang = a0 + sign * Math.toRadians(deg.toDouble())
            val dir = Pt(cos(ang), sin(ang))
            val len = (if (deg % 10 == 0) 0.09 else 0.045) * radiusV
            val outer = c + dir * radiusV
            drawLine(BAND_TICK, outer.o(), (outer - dir * len).o(), strokeWidth = 1.1f * density)
            deg += stepDeg
        }
    }

    // The baseline, drawn as a strip edge would be, with handles on both ends.
    drawLine(HALO, c.o(), b.o(), strokeWidth = edgeW + 3f * density)
    drawLine(BAND_EDGE, c.o(), b.o(), strokeWidth = edgeW)
    drawCircle(HALO, hr, b.o())
    drawCircle(BAND_EDGE, hr, b.o(), style = androidx.compose.ui.graphics.drawscope.Stroke(1.6f * density))
    val dv = Pt(b.x - c.x, b.y - c.y)
    val len = dv.length()
    if (len > 1e-6) {
        val u = Pt(dv.x / len, dv.y / len)
        crosshair(c.o(), u.o(), u.perp().o(), 10f * density, density, BAND_TICK)
    }

    if (wedge != null) {
        // The angle beside the vertex, along the bisector, clear of the crosshair.
        val mid = p.baseAngle() + p.sweep / 2.0
        val radiusV = p.radius * m.zoom
        val dist = minOf(38.0 * density, radiusV * 0.6).coerceAtLeast(22.0 * density)
        val t = Pt(c.x + cos(mid) * dist, c.y + sin(mid) * dist)
        paint.textSize = 17f * density
        val text = "%.${m.protractorDecimals}f°".format(p.angleDegrees())
        outlinedText(nc, paint, text, t.x.toFloat(), t.y.toFloat(), density, BAND_TICK)
    }
}
