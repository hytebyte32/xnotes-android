package com.xnotes.core.edit

import com.xnotes.core.geometry.AngleSnap
import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.tools.ShapeConfig
import com.xnotes.core.tools.ShapeKind
import kotlin.math.abs
import kotlin.math.max

/**
 * Drag out a shape: press anchors one corner, the drag steers the other, lift commits it if the
 * drag was a real one. Points are in the frame of whichever section the press landed in; the tool
 * does not know which. The surface decides where the finished item goes.
 *
 * Pure Kotlin, so the shape rules (the circle stays square, a line snaps near an axis, a tap makes
 * nothing) are unit-testable without a view.
 */
class ShapeTool {

    /** The shape being dragged out, for the live preview; null between drags. */
    var pending: ShapeItem? = null
        private set

    /** Start a drag at [start] with the styling in [config] and ink colour [ink]. */
    fun begin(start: Pt, config: ShapeConfig, ink: Rgba): ShapeItem {
        val fill = if (config.fill && config.shape.isClosed) ink.scaleAlpha(config.fillAlpha) else null
        val shape = ShapeItem(
            config.shape, start, start, ink, config.strokeWidth * PEN_PARITY, fill,
            config.neon, config.neonStrength,
            dashed = config.dashed, dashLength = config.dashLength, dashGap = config.dashGap,
        )
        pending = shape
        return shape
    }

    /** Steer the dragged corner to [raw]. Returns the shape, or null when no drag is open. */
    fun extend(raw: Pt): ShapeItem? {
        val shape = pending ?: return null
        shape.end = when {
            // Line and arrow pin flat when the dragged end lands near an axis.
            shape.shape.isEndpointShape -> snapAxisEndpoint(shape.start, raw)
            // Circle keeps its box square, so it stays a circle rather than becoming an ellipse.
            shape.shape == ShapeKind.CIRCLE -> squareCorner(shape.start, raw)
            else -> raw
        }
        return shape
    }

    /** End the drag. The shape if it was a real drag, null for a tap (which makes no shape). */
    fun finish(): ShapeItem? {
        val shape = pending
        pending = null
        return shape?.takeIf { it.start.distanceTo(it.end) > MIN_DRAG }
    }

    /** Drop the drag without committing anything. */
    fun cancel() {
        pending = null
    }

    companion object {
        /** A drag shorter than this (content px) is a tap and makes no shape. */
        const val MIN_DRAG = 3.0

        /** Shape size -> thickness: the midpoint of the default pen's pressure width range
         *  (m=0.35..1.0), so a shape reads as thick as a same-size pen, not as a flat full-width line. */
        const val PEN_PARITY = 0.675

        /** Constrain a dragged corner [p] to a square box anchored at [anchor] (the perfect circle). */
        fun squareCorner(anchor: Pt, p: Pt): Pt {
            val side = max(abs(p.x - anchor.x), abs(p.y - anchor.y))
            val sx = if (p.x >= anchor.x) 1.0 else -1.0
            val sy = if (p.y >= anchor.y) 1.0 else -1.0
            return Pt(anchor.x + sx * side, anchor.y + sy * side)
        }

        /** Pull a line or arrow's dragged end weakly onto 30, 45 and 90 degree angles from [anchor]. */
        fun snapAxisEndpoint(anchor: Pt, p: Pt): Pt = AngleSnap.snapEnd(anchor, p)
    }
}
