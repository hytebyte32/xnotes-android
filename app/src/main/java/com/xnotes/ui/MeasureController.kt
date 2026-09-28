package com.xnotes.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xnotes.canvas.Ruler
import com.xnotes.canvas.RulerButton
import com.xnotes.core.geometry.Pt
import com.xnotes.core.measure.PointRuler
import com.xnotes.core.measure.PointRulerPart
import com.xnotes.core.measure.Protractor
import com.xnotes.core.measure.RulerMode
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max

/**
 * The measuring rulers, shared by both canvases: the state, the touch handling, and nothing that
 * draws. Each canvas hands over how it maps between viewport pixels and content, and forwards
 * presses here first; [RulerOverlay] draws whatever this holds.
 *
 * Two rulers live here. The band is a screen-fixed straightedge whose graduations follow the zoom.
 * The two-point ruler is a line set on the content itself, so its reading holds still however the
 * canvas is panned or zoomed. Presses arrive in viewport pixels.
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
    val band = Ruler()
    val protractor = Protractor()

    /** Decimal places on the protractor readout. */
    var protractorDecimals by mutableStateOf(1)

    /** A finished protractor: centre, baseline end and the clean arc, all in content space. */
    var onProtractor: (com.xnotes.core.geometry.Pt, com.xnotes.core.geometry.Pt, List<com.xnotes.core.geometry.Pt>) -> Unit = { _, _, _ -> }

    var mode by mutableStateOf(RulerMode.OFF)
        private set

    /** Bumped on every change to a ruler or to the view under it, so the overlay redraws. */
    var rev by mutableStateOf(0)
        private set

    var useInches by mutableStateOf(false)

    val zoom: Double get() = zoomNow()
    val dpi: Int get() = documentDpi()
    val size: Pair<Double, Double> get() = viewportSize()
    fun toViewportPt(p: Pt): Pt = toViewport(p)

    fun switchTo(next: RulerMode) {
        mode = next
        band.visible = next == RulerMode.BAND
        if (next == RulerMode.BAND && !band.initialized) {
            val (w, h) = viewportSize()
            band.placeDefault(w, h, density)
        }
        if (next != RulerMode.PROTRACTOR) protractor.reset()
        active = Active.NONE
        rev++
    }

    /** Off, then the band, then the two-point line, then off again. */
    fun cycle() {
        switchTo(
            when (mode) {
                RulerMode.OFF -> RulerMode.BAND
                RulerMode.BAND -> RulerMode.TWO_POINT
                RulerMode.TWO_POINT -> RulerMode.OFF
                RulerMode.PROTRACTOR -> RulerMode.BAND
            },
        )
    }

    /** The protractor on, or off if it already is. */
    fun toggleProtractor() {
        switchTo(if (mode == RulerMode.PROTRACTOR) RulerMode.OFF else RulerMode.PROTRACTOR)
    }

    /** Drop the protractor in progress so the next press sets a new centre. */
    fun newProtractor() {
        protractor.reset()
        active = Active.NONE
        rev++
    }

    /** Throw the two-point line away so the next press places a new one. */
    fun newLine() {
        point.clear()
        rev++
    }

    /** The view moved or zoomed: whatever is drawn in viewport pixels needs redrawing. */
    fun viewChanged() {
        if (mode != RulerMode.OFF) rev++
    }

    /** How far the band's rotation handles sit from its centre, kept on screen. */
    fun bandHandleDist(): Double {
        val (w, h) = viewportSize()
        return 0.30 * minOf(w, h)
    }

    // --- touch ---

    private enum class Active { NONE, PLACING, POINT_PART, BAND_MOVE, BAND_ROTATE, PROT_BASE, PROT_ARC }

    private var active = Active.NONE
    private var part: PointRulerPart? = null
    private var partStart: PointRuler.Snapshot? = null
    private var grab = Pt.ZERO
    private var grabAngle = 0.0
    private var bandGrabOffset = Pt.ZERO
    private var bandRotateSign = 1.0

    /** A press at viewport [vp]. True when a ruler took it, which then drives the gesture. */
    fun down(vp: Pt, finger: Boolean): Boolean = when (mode) {
        RulerMode.OFF -> false
        RulerMode.TWO_POINT -> pointDown(vp, finger)
        RulerMode.BAND -> bandDown(vp, finger)
        RulerMode.PROTRACTOR -> protractorDown(vp)
    }

    fun move(vp: Pt) {
        when (active) {
            Active.PLACING -> {
                point.dragEnd(toContent(vp))
                rev++
            }
            Active.POINT_PART -> pointMove(toContent(vp))
            Active.BAND_MOVE -> {
                band.center = vp + bandGrabOffset
                rev++
            }
            Active.BAND_ROTATE -> {
                if (!band.lockAngle) {
                    val v = (vp - band.center) * bandRotateSign
                    band.angleRad = Ruler.snapToAxes(atan2(v.y, v.x))
                }
                rev++
            }
            Active.PROT_BASE -> {
                protractor.dragBaseline(toContent(vp))
                rev++
            }
            Active.PROT_ARC -> {
                protractor.dragArc(toContent(vp))
                rev++
            }
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
        rev++
    }

    private fun protractorDown(vp: Pt): Boolean {
        val at = toContent(vp)
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

    /** The pen came up: a real sweep is committed as a baseline and a clean arc; a sliver is dropped. */
    private fun finishArc() {
        if (protractor.angleDegrees() < MIN_ARC_DEG) {
            protractor.cancelArc()
            return
        }
        val c = protractor.centre
        val b = protractor.baseEnd
        val arc = protractor.arcPoints()
        protractor.reset()
        onProtractor(c, b, arc)
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
        // A pen never grabs the line itself, so drawing right along it is still drawing.
        val hit = point.hit(at, HIT_DP * density / z, GRIP_ARM_PX / z, allowBody = finger) ?: return false
        part = hit
        partStart = point.snapshot()
        grab = at
        val c = point.pivot()
        grabAngle = atan2(at.y - c.y, at.x - c.x)
        active = Active.POINT_PART
        return true
    }

    private fun pointMove(at: Pt) {
        val p = part ?: return
        val snap = partStart ?: return
        point.restore(snap)
        when (p) {
            PointRulerPart.START, PointRulerPart.END -> point.moveEnd(p, at)
            PointRulerPart.PIVOT -> point.slidePivot(at)
            PointRulerPart.BODY -> point.translate(at.x - grab.x, at.y - grab.y)
            PointRulerPart.ROTATE -> {
                val c = point.pivot()
                point.rotateBy(atan2(at.y - c.y, at.x - c.x) - grabAngle)
            }
        }
        rev++
    }

    private fun bandDown(vp: Pt, finger: Boolean): Boolean {
        val handleTol = max(HIT_DP * density, band.handleRadiusPx())
        val hi = band.hitHandle(vp, bandHandleDist(), handleTol)
        if (hi != null && !band.lockAngle) {
            bandRotateSign = if (hi == 0) 1.0 else -1.0
            active = Active.BAND_ROTATE
            return true
        }
        val btn = band.hitButton(vp, max(HIT_DP * density, band.buttonRadiusPx()))
        if (btn != null) {
            when (btn) {
                RulerButton.LOCK_POS -> band.lockPosition = !band.lockPosition
                RulerButton.LOCK_ANGLE -> band.lockAngle = !band.lockAngle
            }
            active = Active.NONE
            rev++
            return true
        }
        // The body moves it, minus a margin along each edge for the pen, so drawing right along the
        // edge never grabs the ruler.
        val moveHalf = if (finger) band.thicknessPx / 2.0
        else (band.thicknessPx / 2.0 - EDGE_MARGIN_DP * density).coerceAtLeast(0.0)
        if (abs(band.signedAcross(vp)) <= moveHalf) {
            if (band.lockPosition) {
                active = Active.NONE // locked: swallowed, so it neither pans nor draws under the band
            } else {
                active = Active.BAND_MOVE
                bandGrabOffset = band.center - vp
            }
            return true
        }
        return false
    }

    companion object {
        /** How near a handle a press has to land, in dp. */
        const val HIT_DP = 14.0

        /** How far the two-point ruler's rotate grip sits off the line, in viewport pixels. */
        const val GRIP_ARM_PX = 34.0

        /** Shortest two-point line kept when placed, in dp. */
        const val MIN_LINE_DP = 12.0

        /** Margin along the band's edges where a pen draws instead of grabbing, in dp. */
        const val EDGE_MARGIN_DP = 24.0

        /** Least sweep, in degrees, that counts as an arc. */
        const val MIN_ARC_DEG = 0.5
    }
}
