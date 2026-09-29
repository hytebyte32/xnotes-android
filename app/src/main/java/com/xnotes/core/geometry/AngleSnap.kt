package com.xnotes.core.geometry

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * A weak pull toward the angles a drawing wants to sit at: every multiple of 30 and of 45 degrees,
 * which includes 0 and 90. It only acts within [TOLERANCE_DEG] of one, so it is easy to break away
 * from and never gets in the way of a deliberate in-between angle.
 */
object AngleSnap {

    const val TOLERANCE_DEG = 2.0

    /** Every target in degrees over a full turn, ascending: 0, 30, 45, 60, 90, 120, 135 and so on. */
    private val TARGETS: DoubleArray = (0 until 360)
        .filter { it % 30 == 0 || it % 45 == 0 }
        .map { it.toDouble() }
        .toDoubleArray()

    /** [rad] pulled onto the nearest target when within tolerance, otherwise unchanged. */
    fun snapAngle(rad: Double): Double {
        val deg = Math.toDegrees(rad)
        val turn = ((deg % 360.0) + 360.0) % 360.0
        var best = Double.MAX_VALUE
        var target = turn
        for (t in TARGETS) {
            for (cand in doubleArrayOf(t, t + 360.0)) {
                val d = abs(cand - turn)
                if (d < best) {
                    best = d
                    target = cand
                }
            }
        }
        if (best > TOLERANCE_DEG) return rad
        // Back to the caller's own turn count, so a sweep past a full turn keeps its extra laps.
        return Math.toRadians(deg - turn + target)
    }

    /** [p] turned about [anchor] onto a target angle when close to one, keeping its distance. */
    fun snapEnd(anchor: Pt, p: Pt): Pt {
        val dx = p.x - anchor.x
        val dy = p.y - anchor.y
        val len = kotlin.math.hypot(dx, dy)
        if (len < 1e-9) return p
        val a = atan2(dy, dx)
        val s = snapAngle(a)
        if (s == a) return p
        return Pt(anchor.x + cos(s) * len, anchor.y + sin(s) * len)
    }

}
