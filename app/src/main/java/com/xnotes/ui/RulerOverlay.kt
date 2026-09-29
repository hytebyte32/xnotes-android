package com.xnotes.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.border
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import com.xnotes.ui.icons.XnotesIcons
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
        val d = density.toDouble()
        val (vw, vh) = measure.size
        val btn = 40.0
        val gap = 8.0
        // Round buttons in a row centred on ([cx], [cy]) viewport px, kept on screen.
        @Composable
        fun ButtonRow(cx: Double, cy: Double, content: @Composable () -> Unit, count: Int) {
            val w = (count * btn + (count - 1) * gap) * d
            val x = (cx - w / 2).coerceIn(4.0 * d, maxOf(4.0 * d, vw - w - 4.0 * d))
            val y = (cy - btn * d / 2).coerceIn(4.0 * d, maxOf(4.0 * d, vh - btn * d - 4.0 * d))
            Row(
                modifier = Modifier.offset { androidx.compose.ui.unit.IntOffset(x.toInt(), y.toInt()) },
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(gap.dp),
            ) { content() }
        }
        if (mode == RulerMode.TWO_POINT && measure.point.placed) {
            val a = measure.toViewportPt(measure.point.start)
            val b = measure.toViewportPt(measure.point.end)
            // Centred under the ruler, on whichever side of the line faces down the screen.
            val len = a.distanceTo(b).coerceAtLeast(1e-6)
            var nx = -(b.y - a.y) / len
            var ny = (b.x - a.x) / len
            if (ny < 0) { nx = -nx; ny = -ny }
            val off = (34.0 + btn / 2 + 8.0) * d
            val cx = (a.x + b.x) / 2 + nx * off
            val cy = (a.y + b.y) / 2 + ny * off
            ButtonRow(cx, cy, count = 4, content = {
                CircleButton(XnotesIcons.lockLength, measure.lockLength) { measure.lockLength = !measure.lockLength }
                CircleButton(XnotesIcons.lockRotation, measure.lockRotation) { measure.lockRotation = !measure.lockRotation }
                CircleButton(XnotesIcons.lockPosition, measure.lockPosition) { measure.lockPosition = !measure.lockPosition }
                CircleButton(XnotesIcons.check, true, accent = Color(0xFF1B8A3A)) { measure.confirm() }
            })
        }
        if (mode == RulerMode.PROTRACTOR && measure.canConfirm) {
            val p = measure.protractor
            val c = measure.toViewportPt(p.centre)
            // Just behind the vertex, opposite the middle of the wedge, clear of the angle readout.
            val mid = p.baseAngle() + p.sweep / 2.0
            val off = (btn / 2 + 26.0) * d
            ButtonRow(c.x - cos(mid) * off, c.y - sin(mid) * off, count = 1, content = {
                CircleButton(XnotesIcons.check, true, accent = Color(0xFF1B8A3A)) { measure.confirm() }
            })
        }
        if (mode == RulerMode.PROTRACTOR || !measure.point.placed) {
            val hint = when {
                mode == RulerMode.TWO_POINT -> "Press and drag to measure"
                measure.protractor.phase == Protractor.Phase.ARC -> "Drag either arm end to set the angle"
                else -> "Press the centre and drag out the first arm"
            }
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp)) { PillButton(hint, false, null) }
        }
    }
}

/** A 40dp round button with a solid fill, a strong rim and a shadow, so it reads on any paper. Filled dark when [on]; [accent] overrides the fill (confirm). */
@Composable
private fun CircleButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    on: Boolean,
    accent: Color? = null,
    onClick: () -> Unit,
) {
    val fill = accent ?: if (on) Color(0xFF111111) else Color.White
    val glyph = if (accent != null || on) Color.White else Color(0xFF111111)
    val rim = if (accent != null) Color(0xFFFFFFFF) else if (on) Color(0xFFFFFFFF) else Color(0xFF111111)
    Box(
        modifier = Modifier
            .size(40.dp)
            .shadow(6.dp, androidx.compose.foundation.shape.CircleShape)
            .background(fill, androidx.compose.foundation.shape.CircleShape)
            .border(2.dp, rim, androidx.compose.foundation.shape.CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Icon(icon, null, tint = glyph, modifier = Modifier.size(22.dp))
    }
}

/** A rounded pill: a plain label when [onClick] is null, otherwise a button that shows [on] as filled. */
@Composable
private fun PillButton(text: String, on: Boolean, onClick: (() -> Unit)?) {
    Box(
        modifier = Modifier
            .background(if (on) Color(0xF2263238) else Color(0xE6FFFFFF), RoundedCornerShape(18.dp))
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(text, color = if (on) Color.White else Color(0xFF222222), fontSize = 13.sp)
    }
}

private val HALO = Color(0xF2FFFFFF)

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

/** Halo colour that keeps [ink] readable: light round dark ink, dark round light ink. */
private fun haloFor(ink: Color): Color {
    val lum = 0.299f * ink.red + 0.587f * ink.green + 0.114f * ink.blue
    return if (lum > 0.6f) Color(0xE6202020) else HALO
}

/**
 * The two-point ruler: a plain line between its two points in the pen colour, a round handle at
 * each end, real-unit ticks across the line counted from the start, and the reading past the far end.
 */
private fun DrawScope.drawPointRuler(m: MeasureController, density: Float, paint: Paint) {
    val r = m.point
    if (r.lengthPx <= 0.0) return
    val ink = Color(m.ink()).copy(alpha = 1f)
    val halo = haloFor(ink)
    val a = m.toViewportPt(r.start)
    val b = m.toViewportPt(r.end)
    val lenV = a.distanceTo(b)
    if (lenV < 1e-6) return
    val d = Pt((b.x - a.x) / lenV, (b.y - a.y) / lenV)
    val n = d.perp()
    val lineW = 1.8f * density

    drawLine(halo, a.o(), b.o(), strokeWidth = lineW + 3f * density)
    drawLine(ink, a.o(), b.o(), strokeWidth = lineW)

    // Ticks across the line, zero at the start; the long ones carry their number.
    val g = graduation(m, density)
    val count = min((lenV / g.stepPx).toInt(), 4000)
    val nc = drawContext.canvas.nativeCanvas
    paint.textSize = 11f * density
    val major = 8f * density
    val minor = 4.5f * density
    for (i in 0..count) {
        val mid = a + d * (i * g.stepPx)
        val isMajor = i % g.labelEvery == 0
        val len = (if (isMajor) major else minor).toDouble()
        drawLine(halo, (mid - n * len).o(), (mid + n * len).o(), strokeWidth = 3.4f * density)
        drawLine(ink, (mid - n * len).o(), (mid + n * len).o(), strokeWidth = 1.2f * density)
        if (isMajor && i > 0) {
            val at = mid + n * (major + 9f * density).toDouble()
            outlinedText(nc, paint, trimmed(i * g.step), at.x.toFloat(), at.y.toFloat(), density, ink, haloColor = halo)
        }
    }

    // A handle at each end.
    val hr = 9f * density
    for (p in listOf(a, b)) {
        drawCircle(halo, hr, p.o())
        drawCircle(ink, hr, p.o(), style = androidx.compose.ui.graphics.drawscope.Stroke(1.8f * density))
    }

    // The reading, past the far end along the line.
    var deg = -Math.toDegrees(r.angle())
    deg = ((deg % 360.0) + 360.0) % 360.0
    val reading = Measure.format(r.lengthPx, m.dpi, m.useInches) + "   " + "%.1f°".format(deg)
    paint.textSize = 15f * density
    val off = hr + 14f * density + paint.measureText(reading) / 2f
    outlinedText(nc, paint, reading, (b.x + d.x * off).toFloat(), (b.y + d.y * off).toFloat(), density, ink, haloColor = halo)
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
    haloColor: Color = HALO,
) {
    val x = cx - paint.measureText(text) / 2f
    val y = cy + paint.textSize / 3f
    if (halo) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f * density
        paint.color = haloColor.toArgb()
        nc.drawText(text, x, y, paint)
    }
    paint.style = Paint.Style.FILL
    paint.color = color.toArgb()
    nc.drawText(text, x, y, paint)
}

private fun trimmed(v: Double): String =
    if (v == floor(v)) v.toLong().toString() else "%.4f".format(v).trimEnd('0').trimEnd('.')

/**
 * The protractor in the same dress: a faint wedge from the baseline round to the arc, dark edge
 * lines, degree graduations along the arc, handles on the centre and baseline end, and the angle
 * beside the vertex rather than out at the rim.
 */
private fun DrawScope.drawProtractor(m: MeasureController, density: Float, paint: Paint) {
    val p = m.protractor
    if (p.phase == Protractor.Phase.IDLE) return
    val ink = Color(m.ink()).copy(alpha = 1f)
    val halo = haloFor(ink)
    val fillC = ink.copy(alpha = 0.12f)
    val c = m.toViewportPt(p.centre)
    val b = m.toViewportPt(p.baseEnd)
    val nc = drawContext.canvas.nativeCanvas
    val edgeW = 1.6f * density
    val hr = 9f * density
    val co = c.o()
    val dots = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(2f * density, 5f * density))

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
        drawPath(fill, fillC)
        val arcPath = Path().apply {
            moveTo(pts[0].x.toFloat(), pts[0].y.toFloat())
            for (i in 1 until pts.size) lineTo(pts[i].x.toFloat(), pts[i].y.toFloat())
        }
        drawPath(arcPath, halo, style = androidx.compose.ui.graphics.drawscope.Stroke(edgeW + 3f * density))
        drawPath(arcPath, ink, style = androidx.compose.ui.graphics.drawscope.Stroke(edgeW))
        drawLine(halo, c.o(), pts.last().o(), strokeWidth = edgeW + 3f * density)
        drawLine(ink, c.o(), pts.last().o(), strokeWidth = edgeW, pathEffect = dots)
        drawCircle(halo, hr, pts.last().o())
        drawCircle(ink, hr, pts.last().o(), style = androidx.compose.ui.graphics.drawscope.Stroke(1.6f * density))

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
            drawLine(ink, outer.o(), (outer - dir * len).o(), strokeWidth = 1.1f * density)
            deg += stepDeg
        }
    }

    // The baseline, drawn as a strip edge would be, with handles on both ends.
    drawLine(halo, c.o(), b.o(), strokeWidth = edgeW + 3f * density)
    drawLine(ink, c.o(), b.o(), strokeWidth = edgeW, pathEffect = dots)
    drawCircle(halo, hr, b.o())
    drawCircle(ink, hr, b.o(), style = androidx.compose.ui.graphics.drawscope.Stroke(1.6f * density))
    // The origin, marked with an X.
    val xa = 8f * density
    for (s in listOf(1f, -1f)) {
        val p1 = Offset(co.x - xa, co.y - xa * s)
        val p2 = Offset(co.x + xa, co.y + xa * s)
        drawLine(halo, p1, p2, strokeWidth = 3.6f * density)
        drawLine(ink, p1, p2, strokeWidth = 1.6f * density)
    }

    if (wedge != null) {
        // The angle beside the vertex, along the bisector, clear of the X.
        val mid = p.baseAngle() + p.sweep / 2.0
        val radiusV = p.radius * m.zoom
        val dist = minOf(38.0 * density, radiusV * 0.6).coerceAtLeast(22.0 * density)
        val t = Pt(c.x + cos(mid) * dist, c.y + sin(mid) * dist)
        paint.textSize = 17f * density
        val text = "%.${m.protractorDecimals}f°".format(p.angleDegrees())
        outlinedText(nc, paint, text, t.x.toFloat(), t.y.toFloat(), density, ink, haloColor = halo)
    }
}
