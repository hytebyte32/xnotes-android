package com.xnotes.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.xnotes.core.model.Rgba
import com.xnotes.core.tools.Tool
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** Whether [tool] draws in a chosen colour, i.e. whether the palette has anything to change. */
fun toolUsesColour(tool: Tool): Boolean =
    tool.isStroke || tool == Tool.SHAPE || tool == Tool.TEXT || tool == Tool.TEXT_BOX

private fun Rgba.c() = Color(r, g, b, 255)

private fun sameColour(a: Rgba, b: Rgba) = a.r == b.r && a.g == b.g && a.b == b.b

/**
 * A floating colour bubble: a ring divided evenly into the palette's colours round a centre showing
 * the colour in use. Press the middle with the pen and drag toward a colour; the bigger ring that
 * opens grows the slice under the pen, and lifting picks it. With the Select tool armed, drag the bubble instead to move it; it glides on when let go and rebounds off the edges of the canvas.
 *
 * Shown while a colour-drawing tool is armed (to pick) or the Select tool is (to move it). Touches outside the bubble go straight through to
 * the canvas; a finger on the bubble is ignored unless [allowFinger].
 */
@Composable
fun ColorPalette(
    visible: Boolean,
    movable: Boolean,
    colors: List<Rgba>,
    allowFinger: Boolean,
    posX: Double,
    posY: Double,
    current: () -> Rgba,
    onPick: (Rgba) -> Unit,
    onMoved: (Double, Double) -> Unit,
) {
    if (!visible || colors.size < 2) return
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val bubble = BUBBLE_DP * density
        val margin = (RING_OUTER_DP + 14f) * density
        val wNow by rememberUpdatedState(w)
        val hNow by rememberUpdatedState(h)
        val marginNow by rememberUpdatedState(margin)
        val colorsNow by rememberUpdatedState(colors)
        val fingerNow by rememberUpdatedState(allowFinger)
        val currentNow by rememberUpdatedState(current)
        val pickNow by rememberUpdatedState(onPick)
        val movedNow by rememberUpdatedState(onMoved)
        val movableNow by rememberUpdatedState(movable)

        // Position in px. Held here (not just in prefs) so a drag and the glide after it are smooth.
        var px by remember { mutableStateOf((posX * w).toFloat()) }
        var py by remember { mutableStateOf((posY * h).toFloat()) }
        var settled by remember { mutableStateOf(true) }
        // Follow the saved spot when it changes from outside (reset position) or the area resizes.
        androidx.compose.runtime.LaunchedEffect(posX, posY, w, h) {
            if (settled) {
                px = (posX * w).toFloat()
                py = (posY * h).toFloat()
            }
        }
        fun lo(size: Float) = minOf(marginNow, size / 2f)
        fun hi(size: Float) = maxOf(size - marginNow, size / 2f)
        val cx = px.coerceIn(lo(w), hi(w))
        val cy = py.coerceIn(lo(h), hi(h))

        var pressing by remember { mutableStateOf(false) }
        var hover by remember { mutableStateOf(-1) }
        var inUse by remember { mutableStateOf(current()) }
        var glide by remember { mutableStateOf<Job?>(null) }
        val n = colors.size

        // The open ring, drawn over the canvas but not touchable.
        if (pressing) {
            Canvas(Modifier.fillMaxSize()) {
                val inner = RING_INNER_DP * density
                val outer = RING_OUTER_DP * density
                val sweep = 360f / n
                for (i in 0 until n) {
                    val on = i == hover
                    val grow = if (on) 6f * density else 0f
                    slice(Offset(cx, cy), inner, outer + grow, -90f + i * sweep - sweep / 2f, sweep, colors[i].c(), density)
                    if (on) {
                        slice(Offset(cx, cy), inner, outer + grow, -90f + i * sweep - sweep / 2f, sweep, Color.Transparent, density, rim = Color(0xFF111111))
                    }
                    if (sameColour(colors[i], inUse)) {
                        val a = Math.toRadians((-90f + i * sweep).toDouble())
                        val mid = (inner + outer) / 2f
                        val p = Offset(cx + (cos(a) * mid).toFloat(), cy + (sin(a) * mid).toFloat())
                        val col = colors[i].c()
                        val lum = 0.299f * col.red + 0.587f * col.green + 0.114f * col.blue
                        val mark = if (lum > 0.6f) Color(0xFF111111) else Color.White
                        drawLine(mark, Offset(p.x - 4f * density, p.y), Offset(p.x - 1f * density, p.y + 3f * density), 1.8f * density)
                        drawLine(mark, Offset(p.x - 1f * density, p.y + 3f * density), Offset(p.x + 5f * density, p.y - 4f * density), 1.8f * density)
                    }
                }
                val shown = if (hover >= 0) colors[hover].c() else inUse.c()
                drawCircle(Color.White, 15f * density, Offset(cx, cy))
                drawCircle(shown, 13f * density, Offset(cx, cy))
            }
        }

        // The resting bubble.
        Box(
            Modifier
                .offset { IntOffset((cx - bubble / 2f).roundToInt(), (cy - bubble / 2f).roundToInt()) }
                .size(BUBBLE_DP.dp)
                .shadow(6.dp, CircleShape)
                .background(Color.White, CircleShape)
                .then(if (movable) Modifier.border(2.dp, Color(0xFF3B82F6), CircleShape) else Modifier)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (down.type == PointerType.Touch && !fingerNow) return@awaitEachGesture
                        glide?.cancel()
                        settled = false
                        val id = down.id
                        // With the Select tool armed the bubble only moves; with a drawing tool it only picks.
                        val pickMode = !movableNow
                        var bx = cxNowValue(px, wNow, marginNow)
                        var by = cyNowValue(py, hNow, marginNow)
                        val tracker = VelocityTracker()
                        if (pickMode) {
                            inUse = currentNow()
                            hover = -1
                            pressing = true
                        }
                        while (true) {
                            val event = awaitPointerEvent()
                            val ch = event.changes.firstOrNull { it.id == id } ?: break
                            val step = ch.positionChange() // read before consuming: a consumed change reports no movement
                            ch.consume()
                            if (pickMode) {
                                val dx = ch.position.x - down.position.x
                                val dy = ch.position.y - down.position.y
                                val dist = hypot(dx, dy)
                                hover = if (dist < DEAD_ZONE_DP * density) -1 else {
                                    val ang = Math.toDegrees(atan2(dy, dx).toDouble()) + 90.0
                                    val sweep = 360.0 / colorsNow.size
                                    (((ang + sweep / 2.0) % 360.0 + 360.0) % 360.0 / sweep).toInt().coerceIn(0, colorsNow.size - 1)
                                }
                            } else {
                                bx = (bx + step.x).coerceIn(lo(wNow), hi(wNow))
                                by = (by + step.y).coerceIn(lo(hNow), hi(hNow))
                                px = bx
                                py = by
                                tracker.addPosition(ch.uptimeMillis, Offset(bx, by))
                            }
                            if (!ch.pressed) break
                        }
                        if (pickMode) {
                            val picked = hover
                            pressing = false
                            hover = -1
                            if (picked in colorsNow.indices) {
                                pickNow(colorsNow[picked])
                                inUse = colorsNow[picked]
                            }
                            settled = true
                        } else {
                            val v = tracker.calculateVelocity()
                            glide = scope.launch {
                                var vx = v.x
                                var vy = v.y
                                var last = -1L
                                while (abs(vx) > 30f || abs(vy) > 30f) {
                                    val now = androidx.compose.runtime.withFrameNanos { it }
                                    if (last < 0) { last = now; continue }
                                    val dt = ((now - last) / 1e9f).coerceAtMost(0.05f)
                                    last = now
                                    var nx = px + vx * dt
                                    var ny = py + vy * dt
                                    // Rebound off the edges, giving up some speed each time.
                                    if (nx < lo(wNow)) { nx = lo(wNow); vx = abs(vx) * REBOUND }
                                    else if (nx > hi(wNow)) { nx = hi(wNow); vx = -abs(vx) * REBOUND }
                                    if (ny < lo(hNow)) { ny = lo(hNow); vy = abs(vy) * REBOUND }
                                    else if (ny > hi(hNow)) { ny = hi(hNow); vy = -abs(vy) * REBOUND }
                                    val decay = exp(-FRICTION * dt)
                                    vx *= decay
                                    vy *= decay
                                    px = nx
                                    py = ny
                                }
                                settled = true
                                movedNow((px / wNow).toDouble(), (py / hNow).toDouble())
                            }
                            // A drag that ended without any speed still needs saving.
                            if (abs(v.x) <= 30f && abs(v.y) <= 30f) {
                                settled = true
                                movedNow((px / wNow).toDouble(), (py / hNow).toDouble())
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val outer = size.minDimension / 2f
                val inner = outer * 0.62f
                val sweep = 360f / n
                // The ring, divided evenly among the colours, with the pick region in the middle.
                for (i in 0 until n) {
                    slice(center, inner, outer - 1.5f * density, -90f + i * sweep - sweep / 2f, sweep, colors[i].c(), density, gap = 1.5f)
                }
                drawCircle(Color(0xFF111111), inner - 2.5f * density, center)
                drawCircle(currentNow().c(), inner - 4.5f * density, center)
            }
        }
    }
}

private fun cxNowValue(v: Float, w: Float, margin: Float) = v.coerceIn(minOf(margin, w / 2f), maxOf(w - margin, w / 2f))
private fun cyNowValue(v: Float, h: Float, margin: Float) = v.coerceIn(minOf(margin, h / 2f), maxOf(h - margin, h / 2f))

/** One annular slice of a ring centred on [c], starting at [startDeg] and [sweepDeg] wide. */
private fun DrawScope.slice(
    c: Offset,
    inner: Float,
    outer: Float,
    startDeg: Float,
    sweepDeg: Float,
    color: Color,
    density: Float,
    gap: Float = 2f,
    rim: Color? = null,
) {
    val mid = (inner + outer) / 2f
    val band = outer - inner
    val tl = Offset(c.x - mid, c.y - mid)
    val sz = Size(mid * 2, mid * 2)
    if (rim != null) {
        for (r in listOf(inner, outer)) {
            drawArc(rim, startDeg + gap / 2f, sweepDeg - gap, false, Offset(c.x - r, c.y - r), Size(r * 2, r * 2), style = Stroke(2.5f * density))
        }
        return
    }
    // A white edge behind the colour keeps neighbouring slices apart and readable on any paper.
    drawArc(Color.White, startDeg, sweepDeg, false, tl, sz, style = Stroke(band))
    drawArc(color, startDeg + gap / 2f, sweepDeg - gap, false, tl, sz, style = Stroke(band))
}

private const val BUBBLE_DP = 48f
private const val RING_INNER_DP = 20f
private const val RING_OUTER_DP = 50f
private const val DEAD_ZONE_DP = 12f
private const val FRICTION = 4.0f
private const val REBOUND = 0.55f

/**
 * The palette wired to the preferences held by [editor]: shown when it is enabled and [tool] draws in
 * a colour; picks go to [pick] (each canvas arms the colour its own way) and a moved bubble is saved.
 */
@Composable
fun PaletteHost(editor: Editor, tool: Tool, current: () -> Rgba, pick: (Rgba) -> Unit) {
    @Suppress("UNUSED_VARIABLE")
    val rev = editor.prefsVersion // read so a preference change redraws the palette
    val p = editor.preferences
    ColorPalette(
        visible = p.paletteEnabled && (toolUsesColour(tool) || tool == Tool.SELECT),
        movable = tool == Tool.SELECT,
        colors = p.paletteColors,
        allowFinger = p.paletteFinger,
        posX = p.paletteX,
        posY = p.paletteY,
        current = current,
        onPick = pick,
        onMoved = { x, y -> editor.applyHomePreferences(editor.preferences.copy(paletteX = x, paletteY = y)) },
    )
}
