package com.xnotes.core.measure

import com.xnotes.core.geometry.Geometry
import com.xnotes.core.geometry.Pt
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Which part of a [PointRuler] a press landed on. */
enum class PointRulerPart { START, END, PIVOT, ROTATE, BODY }

/**
 * The two-point ruler: a start and an end set on the content, showing the distance between them.
 *
 * The points live in content space, so the line scrolls and zooms with the canvas and its reading
 * does not change with the view. Measurements are always taken from [start]. The [pivot] is a point
 * on the line that the whole ruler turns about; it slides along the line but never changes what is
 * measured.
 *
 * Pure Kotlin, no view and no renderer, so all of it unit-tests.
 */
class PointRuler {

    var start: Pt = Pt.ZERO
        private set
    var end: Pt = Pt.ZERO
        private set

    /** Distance from [start] to the pivot, in content px, along the line. */
    var pivotAlong: Double = 0.0
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

    fun pivot(): Pt = start + direction() * pivotAlong

    /** Where the rotate grip sits: [arm] content px off the line at the pivot. */
    fun rotateGrip(arm: Double): Pt = pivot() + direction().perp() * arm

    fun clear() {
        placed = false
        start = Pt.ZERO
        end = Pt.ZERO
        pivotAlong = 0.0
    }

    /** Begin a line at [p]; the end follows with [dragEnd] until the press lifts. */
    fun begin(p: Pt) {
        start = p
        end = p
        pivotAlong = 0.0
        placed = false
    }

    /** Move the end while placing, keeping the pivot at the line's start. */
    fun dragEnd(p: Pt) {
        end = p
    }

    /** Finish placing; a line too short to mean anything is dropped. */
    fun finishPlacing(minLen: Double): Boolean {
        placed = lengthPx >= minLen
        if (!placed) clear() else pivotAlong = lengthPx / 2.0
        return placed
    }

    /**
     * The part of the ruler [p] lands on, within [tol] content px, or null. Handles win over the
     * body, and the pivot over the ends, so a short ruler stays grabbable at both.
     */
    fun hit(p: Pt, tol: Double, gripArm: Double, allowBody: Boolean = true): PointRulerPart? {
        if (!placed) return null
        if (p.distanceTo(rotateGrip(gripArm)) <= tol) return PointRulerPart.ROTATE
        if (p.distanceTo(pivot()) <= tol) return PointRulerPart.PIVOT
        if (p.distanceTo(start) <= tol) return PointRulerPart.START
        if (p.distanceTo(end) <= tol) return PointRulerPart.END
        if (allowBody && Geometry.distancePointToSegment(p, start, end) <= tol) return PointRulerPart.BODY
        return null
    }

    /** Move the whole line by ([dx], [dy]). */
    fun translate(dx: Double, dy: Double) {
        start = Pt(start.x + dx, start.y + dy)
        end = Pt(end.x + dx, end.y + dy)
    }

    /**
     * Drag one end to [p], holding the pivot where it is in content space. The pivot is re-projected
     * onto the new line and kept inside it.
     */
    fun moveEnd(part: PointRulerPart, p: Pt) {
        val pivotPt = pivot()
        if (part == PointRulerPart.START) start = p else end = p
        val d = direction()
        pivotAlong = Geometry.dot(pivotPt - start, d).coerceIn(0.0, lengthPx)
    }

    /** Slide the pivot to the nearest point of the line under [p]. */
    fun slidePivot(p: Pt) {
        pivotAlong = Geometry.dot(p - start, direction()).coerceIn(0.0, lengthPx)
    }

    /** Turn the whole ruler about its pivot by [delta] radians. */
    fun rotateBy(delta: Double) {
        val c = pivot()
        val cs = cos(delta)
        val sn = sin(delta)
        fun turn(q: Pt): Pt {
            val v = q - c
            return Pt(c.x + v.x * cs - v.y * sn, c.y + v.x * sn + v.y * cs)
        }
        start = turn(start)
        end = turn(end)
    }

    /** Capture the line so a drag can be measured against its start and cannot compound. */
    fun snapshot(): Snapshot = Snapshot(start, end, pivotAlong)

    fun restore(s: Snapshot) {
        start = s.start
        end = s.end
        pivotAlong = s.pivotAlong
    }

    data class Snapshot(val start: Pt, val end: Pt, val pivotAlong: Double)
}
