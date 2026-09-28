package com.xnotes.core.model

import com.xnotes.core.geometry.Pt

/**
 * A tiny single-stroke font for measurement labels: digits, a decimal point, a minus, the degree
 * sign and the few letters units need. Each glyph is a set of polylines in a box one unit tall
 * (y down, 0 at the top, 1 on the baseline), so a label is just lines and draws sharp at any zoom
 * through the same tessellator as every other line, with no font file or text engine involved.
 */
object StrokeFont {

    class Glyph(val advance: Double, val strokes: List<List<Pt>>)

    private fun p(x: Double, y: Double) = Pt(x, y)

    private fun line(vararg xy: Double): List<Pt> = (xy.indices step 2).map { Pt(xy[it], xy[it + 1]) }

    /** Rotate a glyph half a turn in its own box, which turns a 6 into a 9. */
    private fun turned(w: Double, strokes: List<List<Pt>>) =
        strokes.map { s -> s.map { Pt(w - it.x, 1.0 - it.y) } }

    private val SIX = listOf(
        line(0.52, 0.05, 0.3, 0.0, 0.1, 0.15, 0.0, 0.55, 0.08, 0.9, 0.3, 1.0, 0.52, 0.9, 0.6, 0.7, 0.5, 0.5, 0.3, 0.45, 0.08, 0.6),
    )

    private val glyphs: Map<Char, Glyph> = mapOf(
        '0' to Glyph(0.8, listOf(line(0.3, 0.0, 0.5, 0.1, 0.6, 0.5, 0.5, 0.9, 0.3, 1.0, 0.1, 0.9, 0.0, 0.5, 0.1, 0.1, 0.3, 0.0))),
        '1' to Glyph(0.8, listOf(line(0.12, 0.22, 0.35, 0.0, 0.35, 1.0))),
        '2' to Glyph(0.8, listOf(line(0.02, 0.2, 0.15, 0.03, 0.35, 0.0, 0.52, 0.1, 0.58, 0.28, 0.5, 0.48, 0.02, 1.0, 0.6, 1.0))),
        '3' to Glyph(0.8, listOf(line(0.03, 0.08, 0.3, 0.0, 0.55, 0.1, 0.55, 0.35, 0.3, 0.5, 0.58, 0.62, 0.58, 0.88, 0.3, 1.0, 0.02, 0.92))),
        '4' to Glyph(0.8, listOf(line(0.45, 1.0, 0.45, 0.0, 0.0, 0.68, 0.62, 0.68))),
        '5' to Glyph(0.8, listOf(line(0.55, 0.0, 0.1, 0.0, 0.06, 0.45, 0.3, 0.4, 0.52, 0.48, 0.6, 0.7, 0.5, 0.92, 0.28, 1.0, 0.02, 0.92))),
        '6' to Glyph(0.8, SIX),
        '7' to Glyph(0.8, listOf(line(0.0, 0.0, 0.6, 0.0, 0.22, 1.0))),
        '8' to Glyph(0.8, listOf(line(0.3, 0.5, 0.08, 0.4, 0.08, 0.12, 0.3, 0.0, 0.52, 0.12, 0.52, 0.4, 0.3, 0.5, 0.05, 0.62, 0.02, 0.88, 0.3, 1.0, 0.58, 0.88, 0.55, 0.62, 0.3, 0.5))),
        '9' to Glyph(0.8, turned(0.6, SIX)),
        '.' to Glyph(0.4, listOf(line(0.12, 0.97, 0.12, 1.0))),
        '-' to Glyph(0.8, listOf(line(0.05, 0.5, 0.55, 0.5))),
        '°' to Glyph(0.55, listOf(line(0.15, 0.02, 0.28, 0.07, 0.33, 0.2, 0.28, 0.33, 0.15, 0.38, 0.02, 0.33, -0.03, 0.2, 0.02, 0.07, 0.15, 0.02))),
        ' ' to Glyph(0.4, emptyList()),
        'c' to Glyph(0.75, listOf(line(0.5, 0.58, 0.3, 0.5, 0.08, 0.6, 0.0, 0.75, 0.08, 0.9, 0.3, 1.0, 0.5, 0.92))),
        'm' to Glyph(0.95, listOf(
            line(0.0, 0.5, 0.0, 1.0),
            line(0.0, 0.65, 0.12, 0.5, 0.27, 0.5, 0.35, 0.62, 0.35, 1.0),
            line(0.35, 0.62, 0.47, 0.5, 0.62, 0.5, 0.7, 0.62, 0.7, 1.0),
        )),
        'i' to Glyph(0.35, listOf(line(0.05, 0.5, 0.05, 1.0), line(0.05, 0.22, 0.05, 0.25))),
        'n' to Glyph(0.65, listOf(line(0.0, 0.5, 0.0, 1.0), line(0.0, 0.65, 0.12, 0.5, 0.3, 0.5, 0.4, 0.62, 0.4, 1.0))),
    )

    /** The glyph for [c], or a blank of about a space's width for anything the font lacks. */
    fun glyph(c: Char): Glyph = glyphs[c] ?: glyphs.getValue(' ')

    /** Width of [text] in font units (multiply by the glyph height for content px). */
    fun width(text: String): Double = text.sumOf { glyph(it).advance }

    /**
     * [text] as polylines laid out from ([x], [y]) at the top-left, glyph height [height], in
     * absolute coordinates.
     */
    fun layout(text: String, x: Double, y: Double, height: Double): List<List<Pt>> {
        val out = ArrayList<List<Pt>>()
        var cx = x
        for (c in text) {
            val g = glyph(c)
            for (s in g.strokes) out.add(s.map { Pt(cx + it.x * height, y + it.y * height) })
            cx += g.advance * height
        }
        return out
    }
}
