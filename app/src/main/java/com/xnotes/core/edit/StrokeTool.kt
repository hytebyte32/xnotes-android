package com.xnotes.core.edit

import com.xnotes.core.model.ShapeItem
import com.xnotes.core.model.Stroke
import com.xnotes.core.stroke.RecognizedShape
import com.xnotes.core.stroke.ShapeRecognizer
import com.xnotes.core.stroke.StrokeSimplify
import com.xnotes.core.tools.Tool

/**
 * The parts of freehand drawing that do not depend on how the pen reached the screen: how densely
 * samples are kept, how a finished stroke is slimmed, and what a held stroke snaps into. The
 * controllers keep the pointer plumbing (events, timers, which section the pen is over).
 */
object StrokeTool {
    /** Decimation gate in viewport px between kept samples (see [captureGate]). */
    const val MIN_SAMPLE_DIST = 1.0

    /** Pen-up reduction tolerance, viewport px at the draw zoom (see [simplifyForCommit]). */
    const val SIMPLIFY_EPS = 0.2

    /** Hold-to-snap: minimum samples before recognition is even attempted (mirrors the recognizer). */
    const val SHAPE_MIN_SAMPLES = 8

    /**
     * The spacing, in content px, a new sample must clear to be kept. Screen-space (viewport px ÷
     * zoom), capped so it never coarsens past the old 1-content-px floor when zoomed out. A fixed
     * content-px gate discarded ever-finer detail the more you zoomed in, so strokes drawn while
     * zoomed faceted into ~zoom-px chords.
     */
    fun captureGate(zoom: Double): Double = (MIN_SAMPLE_DIST / zoom).coerceAtMost(MIN_SAMPLE_DIST)

    /**
     * Pen-up sample reduction: like the capture gate, the tolerance is screen-space, capped so
     * zoomed-out ink keeps content fidelity. The stroke's just-built geometry supplies the
     * half-width channel, so pressure/speed width variation survives the reduction.
     */
    fun simplifyForCommit(stroke: Stroke, zoom: Double) {
        if (StrokeSimplify.enabled && !stroke.straight) {
            val eps = (SIMPLIFY_EPS / zoom).coerceAtMost(SIMPLIFY_EPS)
            val slim = StrokeSimplify.simplify(
                stroke.samples, stroke.geometry().halfWidths, eps,
                stroke.smoothScale, stroke.config.directionStrength,
            )
            if (slim.size != stroke.sampleCount) {
                stroke.setSamples(slim) // allocates exactly, so no trim needed
                stroke.invalidate()
                return
            }
        }
        // Nothing was dropped, so the stroke still carries the slack capture doubling left behind.
        stroke.trimToSize()
    }

    /**
     * What a stroke the pen has held still on snaps into, or null when it is too short or not a
     * confident match (the next movement re-arms the hold). A neon or dashed pen snaps to a neon or
     * dashed shape, and a highlighter keeps its look: translucent, outline only.
     */
    fun snapToShape(stroke: Stroke): ShapeItem? {
        if (stroke.samples.size < SHAPE_MIN_SAMPLES) return null
        val rec = ShapeRecognizer.recognize(stroke.samples) ?: return null
        return shapeFor(stroke, rec)
    }

    /** The [ShapeItem] a recognised [rec] becomes when it replaces [stroke]. */
    fun shapeFor(stroke: Stroke, rec: RecognizedShape): ShapeItem {
        val width = stroke.config.baseWidth * ShapeTool.PEN_PARITY
        val color = stroke.config.rgba // the as-drawn ink colour, not the alpha-scaled render one
        val dashed = stroke.tool == Tool.DASHED // a dashed pen snaps to a dashed shape
        val verts = rec.vertices
        val shape = if (verts != null) {
            // Polygon/polyline: keep the recognized corners.
            ShapeItem.poly(
                rec.kind, verts, color, width, null, stroke.config.neon, stroke.config.neonStrength,
                dashed, stroke.config.dashLength, stroke.config.dashGap,
            )
        } else {
            ShapeItem(
                shape = rec.kind,
                start = rec.start,
                end = rec.end,
                strokeRgba = color,
                strokeWidth = width,
                fillRgba = null,
                neon = stroke.config.neon,
                neonStrength = stroke.config.neonStrength,
                dashed = dashed,
                dashLength = stroke.config.dashLength,
                dashGap = stroke.config.dashGap,
            )
        }
        if (stroke.tool == Tool.HIGHLIGHTER) {
            shape.highlighterAlpha = stroke.config.highlighterAlpha
            shape.highlighterInverse = stroke.config.highlighterInverse
            shape.neon = false
            shape.dashed = false
        }
        return shape
    }
}
