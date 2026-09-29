package com.xnotes.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xnotes.canvas.RulerMath
import com.xnotes.core.geometry.AngleSnap
import com.xnotes.core.geometry.Pt
import com.xnotes.core.measure.Measure
import com.xnotes.core.measure.PointRuler
import com.xnotes.core.measure.PointRulerPart
import com.xnotes.core.measure.Protractor
import com.xnotes.core.measure.RulerMode
import kotlin.math.cos
import kotlin.math.sin

/**
 * The reading a finished measurement leaves on the canvas: [text] centred on [centre], both in
 * content space, with [heightPx] the glyph height in content px that makes it about half a real
 * centimetre tall. Each canvas turns it into whatever text item it has.
 */
class MeasureLabel(val text: String, val centre: Pt, val heightPx: Double)

/**
 * The measuring tools, shared by both canvases: the state, the touch handling, and nothing that
 * draws. Each canvas hands over how it maps between viewport pixels and content, and forwards
 * presses here first; [RulerOverlay] draws whatever this holds.
 *
 * Two tools live here. The two-point ruler is a line set on the content itself, so its reading
 * holds still however the canvas is panned or zoomed; the protractor sets a baseline and then an
 * arc. Presses arrive in viewport pixels. When a measurement is finished it is handed to the
 * canvas to keep, along with a text label of its reading.
 */
@Stable
class MeasureController(
    private val toContent: (Pt) -> Pt,
    private val toViewport: (Pt) -> Pt,
    private val zoomNow: () -> Double,
    private val viewportSize: () -> Pair<Double, Double>,
    private val documentDpi: () -> Int,
    /** Device pixels per dp, so touch targets keep their physical size. */
    val density: Double,
) {
    val point = PointRuler()
    val protractor = Protractor()

    /** Decimal places on the protractor readout. */
    var protractorDecimals by mutableStateOf(1)

    /** A finished protractor: centre, baseline end and the clean arc, all in content space, with its reading. */
    var onProtractor: (Pt, Pt, List<Pt>, MeasureLabel) -> Unit = { _, _, _, _ -> }

    /** A finished two-point ruler: its start and end in content space, with its reading. */
    var onRuler: (Pt, Pt, MeasureLabel) -> Unit = { _, _, _ -> }

    var mode by mutableStateOf(RulerMode.OFF)
        private set

    /** Bumped on every change to a tool or to the view under it, so the overlay redraws. */
    var rev by mutableStateOf(0)
        private set

    var useInches by mutableStateOf(false)

    /** The pen colour, packed ARGB, that the overlay draws in; each canvas points this at its own ink. */
    var ink: () -> Int = { 0xFF262626.toInt() }

    val zoom: Double get() = zoomNow()
    val dpi: Int get() = documentDpi()
    val size: Pair<Double, Double> get() = viewportSize()
    fun toViewportPt(p: Pt): Pt = toViewport(p)

    fun switchTo(next: RulerMode) {
        // Leaving a tool keeps its last reading on the canvas; nothing is drawn until then.
        if (mode == RulerMode.TWO_POINT && next != RulerMode.TWO_POINT) keepRuler()
        if (mode == RulerMode.PROTRACTOR && next != RulerMode.PROTRACTOR) keepProtractor()
        mode = next
        if (next != RulerMode.PROTRACTOR) protractor.reset()
        active = Active.NONE
        rev++
    }

    /** Off, then the ruler, then off again; from the protractor, to the ruler. */
    fun cycle() {
        switchTo(if (mode == RulerMode.TWO_POINT) RulerMode.OFF else RulerMode.TWO_POINT)
    }

    /** The protractor on, or off if it already is. */
    fun toggleProtractor() {
        switchTo(if (mode == RulerMode.PROTRACTOR) RulerMode.OFF else RulerMode.PROTRACTOR)
    }

    /** Keep the protractor as it stands and clear it, so the next press sets a new centre. */
    fun newProtractor() {
        keepProtractor()
        active = Active.NONE
        rev++
    }

    /** Keep the current line's reading and clear it, so the next press places a new one. */
    fun newLine() {
        keepRuler()
        rev++
    }

    /** The view moved or zoomed: whatever is drawn in viewport pixels needs redrawing. */
    fun viewChanged() {
        if (mode != RulerMode.OFF) rev++
    }

    // --- keeping a measurement ---

    /** Glyph height for a saved reading: about half a real centimetre. */
    private fun labelHeight(): Double = RulerMath.contentPxPerCm(dpi) * LABEL_CM

    private fun keepRuler() {
        if (!point.placed) {
            point.clear()
            return
        }
        val a = point.start
        val b = point.end
        val h = labelHeight()
        val normal = point.direction().perp()
        val centre = (a + b) * 0.5 + normal * (h * 1.3)
        val text = Measure.format(point.lengthPx, dpi, useInches)
        point.clear()
        onRuler(a, b, MeasureLabel(text, centre, h))
    }

    // --- touch ---

    private enum class Active { NONE, PLACING, POINT_PART, PROT_BASE, PROT_ARC, PROT_PART }

    private var active = Active.NONE
    private var part: PointRulerPart? = null
    private var partStart: PointRuler.Snapshot? = null
    private var grab = Pt.ZERO
    private var protPart: Protractor.Part? = null
    private var protBase: Pair<Pt, Pt>? = null

    /** A press at viewport [vp]. True when a tool took it, which then drives the gesture. */
    fun down(vp: Pt, finger: Boolean): Boolean = if (finger) false else when (mode) {
        RulerMode.OFF -> false
        RulerMode.TWO_POINT -> pointDown(vp, finger)
        RulerMode.PROTRACTOR -> protractorDown(vp)
    }

    fun move(vp: Pt) {
        when (active) {
            Active.PLACING -> {
                point.dragEnd(AngleSnap.snapEnd(point.start, toContent(vp)))
                rev++
            }
            Active.POINT_PART -> pointMove(toContent(vp))
            Active.PROT_BASE -> {
                protractor.dragBaseline(AngleSnap.snapEnd(protractor.centre, toContent(vp)))
                rev++
            }
            Active.PROT_ARC -> {
                protractor.dragArc(toContent(vp))
                rev++
            }
            Active.PROT_PART -> protMove(toContent(vp))
            Active.NONE -> Unit
        }
    }

    fun up() {
        if (active == Active.PLACING) point.finishPlacing(MIN_LINE_DP * density / zoomNow())
        if (active == Active.PROT_BASE) protractor.endBaseline(MIN_LINE_DP * density / zoomNow())
        if (active == Active.PROT_ARC) finishArc()
        active = Active.NONE
        part = null
        partStart = null
        protPart = null
        protBase = null
        rev++
    }

    private fun protractorDown(vp: Pt): Boolean {
        val at = toContent(vp)
        protractor.hit(at, HIT_DP * density / zoomNow())?.let { hit ->
            protPart = hit
            protBase = protractor.centre to protractor.baseEnd
            grab = at
            if (hit == Protractor.Part.ARM2) protractor.beginArm2(at)
            active = Active.PROT_PART
            return true
        }
        when (protractor.phase) {
            Protractor.Phase.IDLE, Protractor.Phase.BASELINE -> {
                protractor.beginBaseline(at)
                active = Active.PROT_BASE
            }
            Protractor.Phase.AWAIT_ARC, Protractor.Phase.ARC -> {
                protractor.beginArc(at)
                active = Active.PROT_ARC
            }
        }
        rev++
        return true
    }

    private fun protMove(at: Pt) {
        when (protPart) {
            Protractor.Part.ARM1 -> protractor.moveArm1(at)
            Protractor.Part.ARM2 -> protractor.dragArm2(at)
            Protractor.Part.BODY -> {
                val (c, b) = protBase ?: return
                val dx = at.x - grab.x
                val dy = at.y - grab.y
                protractor.placeBase(Pt(c.x + dx, c.y + dy), Pt(b.x + dx, b.y + dy))
            }
            null -> Unit
        }
        rev++
    }

    /** The pen came up: a sliver is dropped; a real sweep stays on screen, adjustable, until the tool is left. */
    private fun finishArc() {
        if (protractor.angleDegrees() < MIN_ARC_DEG) protractor.cancelArc()
    }

    /** Hand the finished protractor to the canvas to keep as shapes and a reading, then clear it. */
    private fun keepProtractor() {
        if (protractor.phase != Protractor.Phase.ARC || protractor.angleDegrees() < MIN_ARC_DEG) {
            protractor.reset()
            return
        }
        val c = protractor.centre
        val b = protractor.baseEnd
        val arc = protractor.arcPoints()
        val h = labelHeight()
        val mid = protractor.baseAngle() + protractor.sweep / 2.0
        val d = minOf(h * 2.5, protractor.radius * 0.6)
        val centre = Pt(c.x + cos(mid) * d, c.y + sin(mid) * d)
        val text = "%.${protractorDecimals}f°".format(protractor.angleDegrees())
        protractor.reset()
        onProtractor(c, b, arc, MeasureLabel(text, centre, h))
    }

    private fun pointDown(vp: Pt, finger: Boolean): Boolean {
        val at = toContent(vp)
        if (!point.placed) {
            point.begin(at)
            active = Active.PLACING
            rev++
            return true
        }
        val z = zoomNow()
        val hit = point.hit(
            at,
            HIT_DP * density / z,
            bodyTol = HIT_DP * density / z,
        ) ?: return false
        part = hit
        partStart = point.snapshot()
        grab = at
        active = Active.POINT_PART
        return true
    }

    private fun pointMove(at: Pt) {
        val p = part ?: return
        val snap = partStart ?: return
        point.restore(snap)
        when (p) {
            PointRulerPart.START -> point.moveEnd(p, AngleSnap.snapEnd(point.end, at))
            PointRulerPart.END -> point.moveEnd(p, AngleSnap.snapEnd(point.start, at))
            PointRulerPart.BODY -> point.translate(at.x - grab.x, at.y - grab.y)
        }
        rev++
    }

    companion object {
        /** How near a handle a press has to land, in dp. */
        const val HIT_DP = 14.0

        /** Shortest two-point line kept when placed, in dp. */
        const val MIN_LINE_DP = 12.0

        /** Least sweep, in degrees, that counts as an arc. */
        const val MIN_ARC_DEG = 0.5

        /** Height of a saved reading, in real centimetres. */
        const val LABEL_CM = 0.5
    }
}
