package com.xnotes.core.measure

import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.DrawStyle
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.tools.ShapeKind

/**
 * What a finished protractor leaves on the page, as ordinary shapes so each one erases, moves and
 * restyles like any other: an X marking the origin, the two radius lines dotted (the baseline and
 * the line out to the arc's far end) and the arc itself solid.
 *
 * All points arrive in the space the shapes will live in; [xArm] is half the X's width in that space.
 */
object MeasureShapes {

    fun protractor(centre: Pt, baseEnd: Pt, arc: List<Pt>, ink: Rgba, width: Double, xArm: Double): List<ShapeItem> {
        val out = ArrayList<ShapeItem>(5)
        out.add(dotted(centre, baseEnd, ink, width))
        arc.lastOrNull()?.let { out.add(dotted(centre, it, ink, width)) }
        out.add(ShapeItem.poly(ShapeKind.POLYLINE, arc, ink, width))
        out.addAll(cross(centre, xArm, ink, width))
        return out
    }

    /** Two solid strokes crossing at [c], each [arm] out either side. */
    fun cross(c: Pt, arm: Double, ink: Rgba, width: Double): List<ShapeItem> = listOf(
        ShapeItem(ShapeKind.LINE, Pt(c.x - arm, c.y - arm), Pt(c.x + arm, c.y + arm), ink, width),
        ShapeItem(ShapeKind.LINE, Pt(c.x - arm, c.y + arm), Pt(c.x + arm, c.y - arm), ink, width),
    )

    private fun dotted(a: Pt, b: Pt, ink: Rgba, width: Double) = ShapeItem(
        ShapeKind.LINE, a, b, ink, width,
        dashed = true,
        dashLength = width * DrawStyle.DOT_LENGTH_FACTOR,
        dashGap = width * DrawStyle.DOT_GAP_FACTOR,
    )
}
