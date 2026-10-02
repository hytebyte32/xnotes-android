package com.xnotes.core.vector

import com.xnotes.core.geometry.Pt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PolygonClipTest {

    // Counter-clockwise in a y-up reading, i.e. positive signed area, as `wound` produces.
    private val square = listOf(Pt(0.0, 0.0), Pt(10.0, 0.0), Pt(10.0, 10.0), Pt(0.0, 10.0))

    private fun area(r: List<Pt>): Double {
        var s = 0.0
        for (i in r.indices) {
            val a = r[i]; val b = r[(i + 1) % r.size]
            s += a.x * b.y - b.x * a.y
        }
        return kotlin.math.abs(s) / 2
    }

    @Test fun aRingInsideIsUnchanged() {
        val ring = listOf(Pt(2.0, 2.0), Pt(5.0, 2.0), Pt(5.0, 5.0))
        assertEquals(area(ring), area(PolygonClip.polygon(ring, PolygonClip.wound(square))), 1e-9)
    }

    @Test fun aRingOutsideIsEmpty() {
        val ring = listOf(Pt(20.0, 20.0), Pt(30.0, 20.0), Pt(30.0, 30.0))
        assertTrue(PolygonClip.polygon(ring, PolygonClip.wound(square)).isEmpty())
    }

    @Test fun anOverhangingSquareIsCutToTheOverlap() {
        val ring = listOf(Pt(5.0, 5.0), Pt(15.0, 5.0), Pt(15.0, 15.0), Pt(5.0, 15.0))
        assertEquals(25.0, area(PolygonClip.polygon(ring, PolygonClip.wound(square))), 1e-9)
    }

    @Test fun aRingThatCoversTheClipGivesTheClip() {
        val ring = listOf(Pt(-5.0, -5.0), Pt(15.0, -5.0), Pt(15.0, 15.0), Pt(-5.0, 15.0))
        assertEquals(100.0, area(PolygonClip.polygon(ring, PolygonClip.wound(square))), 1e-9)
    }

    @Test fun degenerateInputsGiveEmpty() {
        assertTrue(PolygonClip.polygon(listOf(Pt(0.0, 0.0), Pt(1.0, 1.0)), square).isEmpty())
        assertTrue(PolygonClip.polygon(square, listOf(Pt(0.0, 0.0), Pt(1.0, 1.0))).isEmpty())
    }

    @Test fun woundReversesAClockwiseRingOnly() {
        val cw = square.asReversed().toList()
        assertEquals(square.toSet(), PolygonClip.wound(cw).toSet())
        assertEquals(square, PolygonClip.wound(square))
        // And the result clips correctly whichever way the input was wound.
        val ring = listOf(Pt(5.0, 5.0), Pt(15.0, 5.0), Pt(15.0, 15.0), Pt(5.0, 15.0))
        assertEquals(25.0, area(PolygonClip.polygon(ring, PolygonClip.wound(cw))), 1e-9)
    }

    @Test fun aLineThroughTheClipKeepsOnlyTheInsidePart() {
        val runs = PolygonClip.polyline(listOf(Pt(-5.0, 5.0), Pt(15.0, 5.0)), false, PolygonClip.wound(square))
        assertEquals(1, runs.size)
        assertEquals(Pt(0.0, 5.0), runs[0].first())
        assertEquals(Pt(10.0, 5.0), runs[0].last())
    }

    @Test fun aLineOutsideGivesNoRuns() {
        assertTrue(PolygonClip.polyline(listOf(Pt(20.0, 0.0), Pt(30.0, 0.0)), false, PolygonClip.wound(square)).isEmpty())
    }

    @Test fun aLineThatLeavesAndReentersGivesTwoRuns() {
        val line = listOf(Pt(2.0, 5.0), Pt(15.0, 5.0), Pt(15.0, 8.0), Pt(5.0, 8.0))
        val runs = PolygonClip.polyline(line, false, PolygonClip.wound(square))
        assertEquals(2, runs.size)
    }

    @Test fun aLineEntirelyInsideIsOneRunWithAllPoints() {
        val line = listOf(Pt(1.0, 1.0), Pt(5.0, 5.0), Pt(9.0, 1.0))
        val runs = PolygonClip.polyline(line, false, PolygonClip.wound(square))
        assertEquals(1, runs.size)
        assertEquals(line, runs[0])
    }

    @Test fun aClosedLineIncludesItsClosingSegment() {
        val tri = listOf(Pt(1.0, 1.0), Pt(9.0, 1.0), Pt(5.0, 9.0))
        val runs = PolygonClip.polyline(tri, true, PolygonClip.wound(square))
        assertEquals(1, runs.size)
        assertEquals(tri + tri.first(), runs[0])
    }
}
