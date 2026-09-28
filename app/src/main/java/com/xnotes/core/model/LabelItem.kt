package com.xnotes.core.model

import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.pal.Pen
import com.xnotes.core.pal.Renderer
import com.xnotes.core.tools.ShapeKind

/**
 * A short line of text on the infinite canvas, such as the reading a ruler or protractor leaves
 * behind. It is drawn as vector lines from [StrokeFont], so it stays sharp at any zoom and goes
 * through the ordinary line tessellator, and it saves as plain text plus a size.
 *
 * [height] is the glyph height in content px and [pos] the top-left of the first glyph.
 */
class LabelItem(
    var pos: Pt,
    var text: String,
    var height: Double,
    var rgba: Rgba = DEFAULT_COLOR,
) : CanvasItem {

    override val kind = KIND
    override val resizable = false
    override var locked = false

    private var cached: List<ShapeItem>? = null
    private var cachedKey: Triple<Pt, String, Double>? = null

    private val lineWidth: Double get() = height * LINE_FRAC

    /** The label as line shapes, rebuilt only when its position, text or size changed. */
    fun strokes(): List<ShapeItem> {
        val key = Triple(pos, text, height)
        cached?.let { if (cachedKey == key) return it }
        val built = StrokeFont.layout(text, pos.x, pos.y, height)
            .filter { it.size >= 2 }
            .map { ShapeItem.poly(ShapeKind.POLYLINE, it, rgba, lineWidth) }
        cached = built
        cachedKey = key
        return built
    }

    override fun paint(r: Renderer) {
        val pen = Pen(rgba, lineWidth, cosmetic = false)
        for (s in strokes()) r.strokePolyline(s.vertices() ?: continue, pen)
    }

    override fun bounds(): Rect {
        val pad = lineWidth / 2.0 + 1.0
        return Rect(pos.x, pos.y, StrokeFont.width(text) * height, height).outset(pad)
    }

    override fun translate(dx: Double, dy: Double) {
        pos = Pt(pos.x + dx, pos.y + dy)
    }

    override fun contains(p: Pt): Boolean = bounds().contains(p)

    override fun centroid(): Pt = bounds().center

    override fun intersectsCircle(cx: Double, cy: Double, radius: Double): Boolean =
        bounds().distanceTo(Pt(cx, cy)) <= radius

    override fun snapshotGeometry(): GeometrySnapshot = LabelSnapshot(pos, height)

    override fun restoreGeometry(snap: GeometrySnapshot) {
        if (snap !is LabelSnapshot) return
        pos = snap.pos
        height = snap.height
    }

    /** A label never rotates; a scale moves it and grows or shrinks its type. */
    override fun applyTransform(t: Affine) {
        pos = t.apply(pos)
        height *= (t.scaleX + t.scaleY) / 2.0
    }

    companion object {
        const val KIND = "label"

        /** Line width as a share of the glyph height. */
        const val LINE_FRAC = 0.09

        val DEFAULT_COLOR = Rgba(30, 136, 229, 255)
    }
}

private data class LabelSnapshot(val pos: Pt, val height: Double) : GeometrySnapshot
