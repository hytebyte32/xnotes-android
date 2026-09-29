package com.xnotes.core.measure

import com.xnotes.core.geometry.Pt
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * The protractor: a centre and a baseline set on the content, then an arc swept from the baseline
 * round to wherever the pen is, with the angle read off live.
 *
 * Flow: [beginBaseline] at the centre, [dragBaseline] out to set its length and direction,
 * [endBaseline]; then [beginArc] and [dragArc] as the arc is drawn. The sweep is tracked
 * continuously rather than recomputed from the pointer, so it can go all the way round past 180
 * degrees and back, up to a full turn either way. The arc's radius is where the pen came down, so
 * what is committed is a clean circular arc however the hand wobbled.
 *
 * Pure Kotlin, no view and no renderer, so all of it unit-tests.
 */
class Protractor {

    enum class Phase { IDLE, BASELINE, AWAIT_ARC, ARC }

    var phase: Phase = Phase.IDLE
        private set
    var centre: Pt = Pt.ZERO
        private set
    var baseEnd: Pt = Pt.ZERO
        private set
    var radius: Double = 0.0
        private set

    /** The sweep the pen has actually made, before any snapping. */
    private var rawSweep: Double = 0.0

    /**
     * Signed sweep from the baseline, radians: positive turns the way +angle does (clockwise on
     * screen). Pulled weakly onto 30, 45 and 90 degrees; the pen's own sweep is kept apart so the
     * pull can be pushed through.
     */
    val sweep: Double get() = com.xnotes.core.geometry.AngleSnap.snapAngle(rawSweep)

    private var lastTheta = 0.0

    fun baseAngle(): Double = atan2(baseEnd.y - centre.y, baseEnd.x - centre.x)

    fun reset() {
        phase = Phase.IDLE
        rawSweep = 0.0
        radius = 0.0
    }

    fun beginBaseline(p: Pt) {
        centre = p
        baseEnd = p
        rawSweep = 0.0
        radius = 0.0
        phase = Phase.BASELINE
    }

    fun dragBaseline(p: Pt) {
        baseEnd = p
    }

    /** Finish the baseline; one too short to give a direction is dropped. */
    fun endBaseline(minLen: Double): Boolean {
        if (centre.distanceTo(baseEnd) < minLen) {
            reset()
            return false
        }
        phase = Phase.AWAIT_ARC
        return true
    }

    /** Start the arc at [p]: its distance from the centre is the radius, and the sweep starts there. */
    fun beginArc(p: Pt) {
        radius = maxOf(centre.distanceTo(p), 1.0)
        val theta = angleOf(p)
        rawSweep = wrap(theta - baseAngle())
        lastTheta = theta
        phase = Phase.ARC
    }

    fun dragArc(p: Pt) {
        // Too close to the centre to have a direction: leave the sweep where it was.
        if (centre.distanceTo(p) < 1e-6) return
        val theta = angleOf(p)
        rawSweep = (rawSweep + wrap(theta - lastTheta)).coerceIn(-2.0 * PI, 2.0 * PI)
        lastTheta = theta
    }

    // --- editing a placed protractor ---

    enum class Part { ARM1, ARM2, BODY }

    /** Free end of the second arm: where the arc stops. */
    fun arm2End(): Pt {
        val a = baseAngle() + sweep
        return Pt(centre.x + radius * cos(a), centre.y + radius * sin(a))
    }

    /**
     * The part [p] lands on within [tol]: an arm end (either), or else the vertex, an arm or the arc
     * itself, which move the whole tool. Null when nothing is near.
     */
    fun hit(p: Pt, tol: Double): Part? {
        if (phase != Phase.ARC && phase != Phase.AWAIT_ARC) return null
        val arc = phase == Phase.ARC
        if (arc && p.distanceTo(arm2End()) <= tol) return Part.ARM2
        if (p.distanceTo(baseEnd) <= tol) return Part.ARM1
        if (p.distanceTo(centre) <= tol) return Part.BODY
        if (com.xnotes.core.geometry.Geometry.distancePointToSegment(p, centre, baseEnd) <= tol) return Part.BODY
        if (arc) {
            if (com.xnotes.core.geometry.Geometry.distancePointToSegment(p, centre, arm2End()) <= tol) return Part.BODY
            val pts = arcPoints(4.0)
            for (i in 0 until pts.size - 1) {
                if (com.xnotes.core.geometry.Geometry.distancePointToSegment(p, pts[i], pts[i + 1]) <= tol) return Part.BODY
            }
        }
        return null
    }

    private var radiusGrab = 0.0

    /** Start dragging the second arm from [p], so the handle does not jump to the pen. */
    fun beginArm2(p: Pt) {
        lastTheta = angleOf(p)
        radiusGrab = radius - centre.distanceTo(p)
    }

    /** Drag the second arm: its angle and its length both follow the pen. */
    fun dragArm2(p: Pt) {
        radius = maxOf(centre.distanceTo(p) + radiusGrab, 1.0)
        dragArc(p)
    }

    /** Drag the first arm's end to [p] (weakly snapped), leaving the second arm where it is on the page. */
    fun moveArm1(p: Pt) {
        val before = baseAngle()
        baseEnd = com.xnotes.core.geometry.AngleSnap.snapEnd(centre, p)
        if (phase == Phase.ARC) {
            rawSweep = (rawSweep - wrap(baseAngle() - before)).coerceIn(-2.0 * PI, 2.0 * PI)
        }
    }

    /** Put the vertex and first arm end at [c] and [b]; the arc and second arm follow, being relative. */
    fun placeBase(c: Pt, b: Pt) {
        centre = c
        baseEnd = b
    }

    /** Abandon a half-drawn arc and wait for another. */
    fun cancelArc() {
        rawSweep = 0.0
        phase = Phase.AWAIT_ARC
    }

    /** The swept angle in degrees, 0 to 360. */
    fun angleDegrees(): Double = Math.toDegrees(abs(sweep))

    /** The arc as points, [stepDeg] apart, from the baseline round by the sweep. */
    fun arcPoints(stepDeg: Double = 2.0): List<Pt> {
        val n = maxOf(2, Math.ceil(Math.toDegrees(abs(sweep)) / stepDeg).toInt() + 1)
        val a0 = baseAngle()
        return (0 until n).map { i ->
            val a = a0 + sweep * i / (n - 1)
            Pt(centre.x + radius * cos(a), centre.y + radius * sin(a))
        }
    }

    private fun angleOf(p: Pt): Double = atan2(p.y - centre.y, p.x - centre.x)

    /** [a] folded into (-pi, pi]. */
    private fun wrap(a: Double): Double {
        var x = a % (2.0 * PI)
        if (x > PI) x -= 2.0 * PI
        if (x <= -PI) x += 2.0 * PI
        return x
    }
}
