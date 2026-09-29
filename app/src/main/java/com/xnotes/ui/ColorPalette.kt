package com.xnotes.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.xnotes.core.model.Rgba
import com.xnotes.core.tools.Tool
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** Whether [tool] draws in a chosen colour, i.e. whether the palette has anything to change. */
fun toolUsesColour(tool: Tool): Boolean =
    tool.isStroke || tool == Tool.SHAPE || tool == Tool.TEXT || tool == Tool.TEXT_BOX

private fun Rgba.c() = Color(r, g, b, 255)

/**
 * A floating colour bubble. Press its middle with the pen and drag toward a colour on the ring that
 * opens; the one under the pen is enlarged and lifting picks it. Press the outer rim and drag to move
 * the bubble instead. The ring shows a check on the colour already in use.
 *
 * Only shown while a colour-drawing tool is armed. Touches outside the bubble go straight through to
 * the canvas; a finger on the bubble is ignored unless [allowFinger].
 */
@Composable
fun ColorPalette(
    visible: Boolean,
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
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val bubble = 64f * density
        val ringR = RING_OUTER_DP * density
        val margin = ringR + 4f * density
        var fx by remember(posX) { mutableStateOf(posX) }
        var fy by remember(posY) { mutableStateOf(posY) }
        // Keep the whole ring on screen wherever the bubble is parked.
        fun clampX(v: Float) = v.coerceIn(minOf(margin, w / 2f), maxOf(w - margin, w / 2f))
        fun clampY(v: Float) = v.coerceIn(minOf(margin, h / 2f), maxOf(h - margin, h / 2f))
        val cx = clampX((fx * w).toFloat())
        val cy = clampY((fy * h).toFloat())

        var pressing by remember { mutableStateOf(false) }
        var hover by remember { mutableStateOf(-1) }
        var inUse by remember { mutableStateOf(current()) }
        val n = colors.size
        val colorsNow by rememberUpdatedState(colors)
        val fingerNow by rememberUpdatedState(allowFinger)
        val currentNow by rememberUpdatedState(current)
        val pickNow by rememberUpdatedState(onPick)
        val movedNow by rememberUpdatedState(onMoved)
        val cxNow by rememberUpdatedState(cx)
        val cyNow by rememberUpdatedState(cy)
        val wNow by rememberUpdatedState(w)
        val hNow by rememberUpdatedState(h)

        // The ring, drawn over the canvas but not touchable.
        if (pressing) {
            Canvas(Modifier.fillMaxSize()) {
                val inner = RING_INNER_DP * density
                val outer = RING_OUTER_DP * density
                val sweep = 360f / n
                for (i in 0 until n) {
                    val on = i == hover
                    val start = -90f + i * sweep - sweep / 2f + 1.5f
                    val grow = if (on) 10f * density else 0f
                    val r0 = inner
                    val r1 = outer + grow
                    val mid = (r0 + r1) / 2f
                    val stroke = r1 - r0
                    val col = colors[i].c()
                    drawArc(
                        color = Color.White,
                        startAngle = start - 1f, sweepAngle = sweep - 1f, useCenter = false,
                        topLeft = Offset(cx - mid, cy - mid), size = Size(mid * 2, mid * 2),
                        style = Stroke(stroke + 4f * density),
                    )
                    drawArc(
                        color = col,
                        startAngle = start, sweepAngle = sweep - 3f, useCenter = false,
                        topLeft = Offset(cx - mid, cy - mid), size = Size(mid * 2, mid * 2),
                        style = Stroke(stroke),
                    )
                    // A dark rim on the hovered slice so it stands out on any colour.
                    if (on) {
                        drawArc(
                            color = Color(0xFF111111),
                            startAngle = start, sweepAngle = sweep - 3f, useCenter = false,
                            topLeft = Offset(cx - r1 + 1.5f * density, cy - r1 + 1.5f * density),
                            size = Size((r1 - 1.5f * density) * 2, (r1 - 1.5f * density) * 2),
                            style = Stroke(3f * density),
                        )
                    }
                    // A check-style dot marks the colour already in use.
                    if (colors[i].r == inUse.r && colors[i].g == inUse.g && colors[i].b == inUse.b) {
                        val a = Math.toRadians((-90f + i * sweep).toDouble())
                        val p = Offset(cx + (cos(a) * mid).toFloat(), cy + (sin(a) * mid).toFloat())
                        val lum = 0.299f * col.red + 0.587f * col.green + 0.114f * col.blue
                        val mark = if (lum > 0.6f) Color(0xFF111111) else Color.White
                        drawCircle(mark, 6f * density, p)
                        drawCircle(col, 3f * density, p)
                    }
                }
                // The centre shows what would be picked, or the colour in use.
                val shown = if (hover >= 0) colors[hover].c() else inUse.c()
                drawCircle(Color.White, 26f * density, Offset(cx, cy))
                drawCircle(shown, 22f * density, Offset(cx, cy))
            }
        }

        // The resting bubble: the colour in use inside a ring of the palette's colours.
        Box(
            Modifier
                .offset { IntOffset((cx - bubble / 2f).roundToInt(), (cy - bubble / 2f).roundToInt()) }
                .size((bubble / density).dp)
                .shadow(6.dp, CircleShape)
                .background(Color.White, CircleShape)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val finger = down.type == PointerType.Touch
                        if (finger && !fingerNow) return@awaitEachGesture
                        down.consume()
                        val centre = Offset(bubble / 2f, bubble / 2f)
                        val fromCentre = hypot(down.position.x - centre.x, down.position.y - centre.y)
                        val moveMode = fromCentre > bubble * 0.32f
                        val start = down.position
                        if (moveMode) {
                            var px = cxNow
                            var py = cyNow
                            drag(down.id) { ch ->
                                ch.consume()
                                val d = ch.positionChange()
                                // The bubble moves under the pointer, so positions are in bubble-local space.
                                px = (px + d.x).coerceIn(minOf(margin, wNow / 2f), maxOf(wNow - margin, wNow / 2f))
                                py = (py + d.y).coerceIn(minOf(margin, hNow / 2f), maxOf(hNow - margin, hNow / 2f))
                                fx = (px / wNow).toDouble()
                                fy = (py / hNow).toDouble()
                            }
                            movedNow(fx, fy)
                        } else {
                            inUse = currentNow()
                            hover = -1
                            pressing = true
                            drag(down.id) { ch ->
                                ch.consume()
                                val dx = ch.position.x - start.x
                                val dy = ch.position.y - start.y
                                val dist = hypot(dx, dy)
                                hover = if (dist < DEAD_ZONE_DP * density) -1 else {
                                    val ang = Math.toDegrees(atan2(dy, dx).toDouble()) + 90.0
                                    val sweep = 360.0 / colorsNow.size
                                    (((ang + sweep / 2.0) % 360.0 + 360.0) % 360.0 / sweep).toInt().coerceIn(0, colorsNow.size - 1)
                                }
                            }
                            val picked = hover
                            pressing = false
                            hover = -1
                            if (picked in colorsNow.indices) {
                                pickNow(colorsNow[picked])
                                inUse = colorsNow[picked]
                            }
                        }
                    }
                },
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2f
                val sweep = 360f / n
                // Ring of small colour pips round the rim, then the colour in use in the middle.
                for (i in 0 until n) {
                    val a = Math.toRadians((-90f + i * sweep).toDouble())
                    val p = Offset(center.x + (cos(a) * (r - 7f * density)).toFloat(), center.y + (sin(a) * (r - 7f * density)).toFloat())
                    drawCircle(colors[i].c(), 5f * density, p)
                }
                drawCircle(Color(0xFF111111), r * 0.42f + 1.5f * density, center)
                drawCircle(current().c(), r * 0.42f, center)
            }
        }
    }
}

private const val RING_INNER_DP = 34f
private const val RING_OUTER_DP = 84f
private const val DEAD_ZONE_DP = 20f

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
        visible = p.paletteEnabled && toolUsesColour(tool),
        colors = p.paletteColors,
        allowFinger = p.paletteFinger,
        posX = p.paletteX,
        posY = p.paletteY,
        current = current,
        onPick = pick,
        onMoved = { x, y -> editor.applyHomePreferences(editor.preferences.copy(paletteX = x, paletteY = y)) },
    )
}
