package com.xnotes.core.measure

import com.xnotes.core.geometry.AngleSnap
import com.xnotes.core.geometry.Geometry
import com.xnotes.core.geometry.Pt
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * The protractor: a vertex and two arms of one shared length, with the angle between them read off live.
 *
 * Flow: [beginBaseline] at the vertex, [dragBaseline] out to set the first arm, [endBaseline]. Both
 * arms now exist, lying on each other at zero angle; either free end can then be dragged
 * ([hit], [moveArm1], [dragArm2]). The two arms always have the same length, so stretching one
 * stretches the other. The sweep is tracked continuously rather than recomputed from the pen, so it
 * can go all the way round past 180 degrees and back, up to a full turn either way.
 *
 * Pure Kotlin, no view and no renderer, so all of it unit-tests.
 */
class Protractor {

    enum class Phase { IDLE, BASELINE, ARC }

    enum class Part { ARM1, ARM2, BODY }

    var phase: Phase = Phase.IDLE
        private set
    var centre: Pt = Pt.ZERO
        private set
    var baseEnd: Pt = Pt.ZERO
        private set

    /** Length of both arms, content px. */
    val radius: Double get() = maxOf(centre.distanceTo(baseEnd), 1.0)

    /** The sweep the pen has actually made, before any snapping. */
    private var rawSweep: Double = 0.0

    /**
     * Signed sweep from the first arm, radians: positive turns the way +angle does (clockwise on
     * screen). Pulled weakly onto 30, 45 and 90 degrees; the pen's own sweep is kept apart so the
     * pull can be pushed through.
     */
    val sweep: Double get() = AngleSnap.snapAngle(rawSweep)

    private var lastTheta = 0.0
    private var lengthGrab = 0.0

    fun baseAngle(): Double = atan2(baseEnd.y - centre.y, baseEnd.x - centre.x)

    fun reset() {
        phase = Phase.IDLE
        rawSweep = 0.0
    }

    fun beginBaseline(p: Pt) {
        centre = p
        baseEnd = p
        rawSweep = 0.0
        phase = Phase.BASELINE
    }

    fun dragBaseline(p: Pt) {
        baseEnd = p
    }

    /** Finish the first arm; one too short to give a direction is dropped. Otherwise the second arm now lies on it. */
    fun endBaseline(minLen: Double): Boolean {
        if (centre.distanceTo(baseEnd) < minLen) {
            reset()
            return false
        }
        rawSweep = 0.0
        phase = Phase.ARC
        return true
    }

    /** Free end of the second arm. */
    fun arm2End(): Pt {
        val a = baseAngle() + sweep
        return Pt(centre.x + radius * cos(a), centre.y + radius * sin(a))
    }

    /**
     * The part [p] lands on within [tol]: an arm end, or else the vertex, an arm or the arc itself,
     * which move the whole tool. When both ends are under the pen (they start on top of each other)
     * the second arm wins, so the angle can be swung out at once. Null when nothing is near.
     */
    fun hit(p: Pt, tol: Double): Part? {
        if (phase != Phase.ARC) return null
        if (p.distanceTo(arm2End()) <= tol) return Part.ARM2
        if (p.distanceTo(baseEnd) <= tol) return Part.ARM1
        if (p.distanceTo(centre) <= tol) return Part.BODY
        if (Geometry.distancePointToSegment(p, centre, baseEnd) <= tol) return Part.BODY
        if (Geometry.distancePointToSegment(p, centre, arm2End()) <= tol) return Part.BODY
        val pts = arcPoints(4.0)
        for (i in 0 until pts.size - 1) {
            if (Geometry.distancePointToSegment(p, pts[i], pts[i + 1]) <= tol) return Part.BODY
        }
        return null
    }

    /** Start dragging the second arm from [p], so the handle does not jump to the pen. */
    fun beginArm2(p: Pt) {
        lastTheta = angleOf(p)
        lengthGrab = radius - centre.distanceTo(p)
    }

    /** Drag the second arm: the angle follows the pen, and the shared length does too. */
    fun dragArm2(p: Pt) {
        val len = maxOf(centre.distanceTo(p) + lengthGrab, 1.0)
        val a = baseAngle()
        baseEnd = Pt(centre.x + len * cos(a), centre.y + len * sin(a))
        if (centre.distanceTo(p) < 1e-6) return
        val theta = angleOf(p)
        rawSweep = (rawSweep + wrap(theta - lastTheta)).coerceIn(-2.0 * PI, 2.0 * PI)
        lastTheta = theta
    }

    /** Drag the first arm's end to [p] (weakly snapped), leaving the second arm's direction where it is on the page. */
    fun moveArm1(p: Pt) {
        val before = baseAngle()
        baseEnd = AngleSnap.snapEnd(centre, p)
        rawSweep = (rawSweep - wrap(baseAngle() - before)).coerceIn(-2.0 * PI, 2.0 * PI)
    }

    /** Put the vertex and first arm end at [c] and [b]; the second arm follows, being relative. */
    fun placeBase(c: Pt, b: Pt) {
        centre = c
        baseEnd = b
    }

    /** The swept angle in degrees, 0 to 360. */
    fun angleDegrees(): Double = Math.toDegrees(abs(sweep))

    /** The arc as points, [stepDeg] apart, from the first arm round by the sweep. */
    fun arcPoints(stepDeg: Double = 2.0): List<Pt> {
        val n = maxOf(2, Math.ceil(Math.toDegrees(abs(sweep)) / stepDeg).toInt() + 1)
        val a0 = baseAngle()
        val r = radius
        return (0 until n).map { i ->
            val a = a0 + sweep * i / (n - 1)
            Pt(centre.x + r * cos(a), centre.y + r * sin(a))
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
