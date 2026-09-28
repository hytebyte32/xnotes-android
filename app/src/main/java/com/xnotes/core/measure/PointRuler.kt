package com.xnotes.core.measure

import com.xnotes.core.geometry.Geometry
import com.xnotes.core.geometry.Pt
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Which part of a [PointRuler] a press landed on. */
enum class PointRulerPart { START, END, ROTATE, BODY }

/**
 * The two-point ruler: a start and an end set on the content, showing the distance between them.
 *
 * The points live in content space, so the line scrolls and zooms with the canvas and its reading
 * does not change with the view. Measurements are always taken from [start], which is also the
 * point the whole ruler turns about.
 *
 * Pure Kotlin, no view and no renderer, so all of it unit-tests.
 */
class PointRuler {

    var start: Pt = Pt.ZERO
        private set
    var end: Pt = Pt.ZERO
        private set

    var placed: Boolean = false
        private set

    val lengthPx: Double get() = start.distanceTo(end)

    /** Unit vector from start to end; along +x when the two coincide. */
    fun direction(): Pt {
        val d = end - start
        val len = d.length()
        return if (len < 1e-9) Pt(1.0, 0.0) else Pt(d.x / len, d.y / len)
    }

    /** Angle of the line, radians, from +x. */
    fun angle(): Double = atan2(end.y - start.y, end.x - start.x)

    /** Where the rotate grip sits: [arm] px off the line at its middle. */
    fun rotateGrip(arm: Double): Pt = (start + end) * 0.5 + direction().perp() * arm

    fun clear() {
        placed = false
        start = Pt.ZERO
        end = Pt.ZERO
    }

    /** Begin a line at [p]; the end follows with [dragEnd] until the press lifts. */
    fun begin(p: Pt) {
        start = p
        end = p
        placed = false
    }

    fun dragEnd(p: Pt) {
        end = p
    }

    /** Finish placing; a line too short to mean anything is dropped. */
    fun finishPlacing(minLen: Double): Boolean {
        placed = lengthPx >= minLen
        if (!placed) clear()
        return placed
    }

    /** The part of the ruler [p] lands on, within [tol] px, or null. Handles win over the body. */
    fun hit(
        p: Pt,
        tol: Double,
        gripArm: Double,
        bodyTol: Double = tol,
        allowBody: Boolean = true,
    ): PointRulerPart? {
        if (!placed) return null
        if (p.distanceTo(rotateGrip(gripArm)) <= tol) return PointRulerPart.ROTATE
        if (p.distanceTo(start) <= tol) return PointRulerPart.START
        if (p.distanceTo(end) <= tol) return PointRulerPart.END
        if (allowBody && Geometry.distancePointToSegment(p, start, end) <= bodyTol) return PointRulerPart.BODY
        return null
    }

    /** Move the whole line by ([dx], [dy]). */
    fun translate(dx: Double, dy: Double) {
        start = Pt(start.x + dx, start.y + dy)
        end = Pt(end.x + dx, end.y + dy)
    }

    /** Drag one end to [p]. */
    fun moveEnd(part: PointRulerPart, p: Pt) {
        if (part == PointRulerPart.START) start = p else end = p
    }

    /** Turn the whole ruler about its start by [delta] radians. */
    fun rotateBy(delta: Double) {
        val cs = cos(delta)
        val sn = sin(delta)
        val v = end - start
        end = Pt(start.x + v.x * cs - v.y * sn, start.y + v.x * sn + v.y * cs)
    }

    /** Capture the line so a drag can be measured against its start and cannot compound. */
    fun snapshot(): Snapshot = Snapshot(start, end)

    fun restore(s: Snapshot) {
        start = s.start
        end = s.end
    }

    data class Snapshot(val start: Pt, val end: Pt)
}
