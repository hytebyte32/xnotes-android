package com.xnotes.core.vector

import com.xnotes.core.geometry.Pt
import com.xnotes.core.infinite.MeshBuilder
import com.xnotes.core.infinite.MeshData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import kotlin.math.abs

class StrokeOutlinerTest {

    private fun outline(
        pts: List<Pt>,
        closed: Boolean = false,
        hw: Double = 1.0,
        cap: LineCap = LineCap.BUTT,
        join: LineJoin = LineJoin.MITER,
        miter: Double = 4.0,
    ): MeshData {
        val mb = MeshBuilder()
        StrokeOutliner.outline(mb, pts, closed, hw, cap, join, miter, 0.05)
        return mb.build()
    }

    private fun area(m: MeshData): Double {
        var sum = 0.0
        var i = 0
        while (i < m.indices.size) {
            val a = m.indices[i]; val b = m.indices[i + 1]; val c = m.indices[i + 2]
            sum += abs(
                (m.positions[2 * b] - m.positions[2 * a]) * (m.positions[2 * c + 1] - m.positions[2 * a + 1]) -
                    (m.positions[2 * c] - m.positions[2 * a]) * (m.positions[2 * b + 1] - m.positions[2 * a + 1]),
            ) / 2.0
            i += 3
        }
        return sum
    }

    private fun hasVertex(m: MeshData, x: Double, y: Double): Boolean =
        (0 until m.vertexCount).any { abs(m.positions[2 * it] - x) < 1e-9 && abs(m.positions[2 * it + 1] - y) < 1e-9 }

    private fun xRange(m: MeshData): Pair<Double, Double> {
        val xs = (0 until m.vertexCount).map { m.positions[2 * it] }
        return xs.min() to xs.max()
    }

    private val seg = listOf(Pt(0.0, 0.0), Pt(10.0, 0.0))
    private val corner = listOf(Pt(0.0, 0.0), Pt(10.0, 0.0), Pt(10.0, 10.0))

    @Test fun aZeroWidthStrokeDrawsNothing() {
        assertTrue(outline(seg, hw = 0.0).isEmpty)
        assertTrue(outline(seg, hw = -1.0).isEmpty)
    }

    @Test fun aButtSegmentIsOneRibbon() {
        val m = outline(seg)
        assertEquals(2, m.triangleCount)
        assertEquals(20.0, area(m), 1e-9) // length 10 x width 2
    }

    @Test fun verticesCarryTheirDisplacementFromTheSpine() {
        val m = outline(seg)
        for (i in 0 until m.vertexCount) assertEquals(1.0, abs(m.offsets[2 * i + 1]), 1e-12)
    }

    @Test fun squareCapsExtendByHalfAWidthAtEachEnd() {
        val m = outline(seg, cap = LineCap.SQUARE)
        assertEquals(24.0, area(m), 1e-9)
        val (lo, hi) = xRange(m)
        assertEquals(-1.0, lo, 1e-9)
        assertEquals(11.0, hi, 1e-9)
    }

    @Test fun roundCapsAddFanTrianglesAndStayWithinTheirRadius() {
        val butt = outline(seg)
        val round = outline(seg, cap = LineCap.ROUND)
        assertTrue(round.triangleCount > butt.triangleCount)
        val (lo, hi) = xRange(round)
        assertTrue(lo >= -1.0 - 1e-9 && lo < -0.9)
        assertTrue(hi <= 11.0 + 1e-9 && hi > 10.9)
    }

    @Test fun aSinglePointOnlyDrawsWithRoundCaps() {
        val p = listOf(Pt(5.0, 5.0))
        assertTrue(outline(p).isEmpty)
        assertTrue(outline(p, cap = LineCap.SQUARE).isEmpty)
        assertFalse(outline(p, cap = LineCap.ROUND).isEmpty)
    }

    @Test fun repeatedPointsDoNotMakeDegenerateSegments() {
        val m = outline(listOf(Pt(0.0, 0.0), Pt(0.0, 0.0), Pt(10.0, 0.0)))
        assertEquals(20.0, area(m), 1e-9)
    }

    @Test fun aMiterJoinReachesTheOuterCorner() {
        assertTrue(hasVertex(outline(corner), 11.0, -1.0))
    }

    @Test fun aMiterBeyondTheLimitIsCutToABevel() {
        val m = outline(corner, miter = 1.2)
        assertFalse(hasVertex(m, 11.0, -1.0))
        assertTrue(hasVertex(m, 10.0, -1.0)) // the two outer offsets still meet the bevel
        assertTrue(hasVertex(m, 11.0, 0.0))
    }

    @Test fun aBevelJoinNeverMitres() {
        assertFalse(hasVertex(outline(corner, join = LineJoin.BEVEL), 11.0, -1.0))
    }

    @Test fun aRoundJoinPutsADiscOnTheCorner() {
        val round = outline(corner, join = LineJoin.ROUND)
        assertTrue(round.triangleCount > outline(corner, join = LineJoin.BEVEL).triangleCount)
    }

    @Test fun aStraightThroughVertexNeedsNoJoin() {
        val m = outline(listOf(Pt(0.0, 0.0), Pt(5.0, 0.0), Pt(10.0, 0.0)))
        assertEquals(4, m.triangleCount) // two ribbons, no join fill
        assertEquals(20.0, area(m), 1e-9)
    }

    @Test fun aClosedRingJoinsEveryCornerIncludingTheWrapAround() {
        val ring = listOf(Pt(0.0, 0.0), Pt(10.0, 0.0), Pt(10.0, 10.0), Pt(0.0, 10.0))
        val m = outline(ring, closed = true)
        assertEquals(16, m.triangleCount) // 4 ribbons x 2 + 4 mitres x 2
    }

    @Test fun aClosingPointEqualToTheStartIsNotDoubled() {
        val ring = listOf(Pt(0.0, 0.0), Pt(10.0, 0.0), Pt(10.0, 10.0), Pt(0.0, 10.0))
        val explicit = outline(ring + ring.first(), closed = true)
        assertEquals(outline(ring, closed = true).triangleCount, explicit.triangleCount)
    }

    // --- dashing ---

    private fun runs(pattern: DoubleArray, offset: Double = 0.0, len: Double = 10.0) =
        StrokeOutliner.dash(listOf(Pt(0.0, 0.0), Pt(len, 0.0)), false, pattern, offset)

    private fun spans(r: List<List<Pt>>) = r.map { it.first().x to it.last().x }

    @Test fun anEvenPatternAlternatesDrawnAndGap() {
        assertEquals(listOf(0.0 to 2.0, 4.0 to 6.0, 8.0 to 10.0), spans(runs(doubleArrayOf(2.0, 2.0))))
    }

    @Test fun anOffsetStartsPartWayThroughThePattern() {
        assertEquals(listOf(2.0 to 4.0, 6.0 to 8.0), spans(runs(doubleArrayOf(2.0, 2.0), offset = 2.0)))
    }

    @Test fun aNegativeOffsetWrapsAround() {
        assertEquals(spans(runs(doubleArrayOf(2.0, 2.0), offset = 2.0)), spans(runs(doubleArrayOf(2.0, 2.0), offset = -2.0)))
    }

    @Test fun anOffsetPastOnePeriodWrapsToo() {
        assertEquals(spans(runs(doubleArrayOf(2.0, 2.0), offset = 0.0)), spans(runs(doubleArrayOf(2.0, 2.0), offset = 8.0)))
    }

    @Test fun anOddPatternIsWalkedAsDrawnThenGap() {
        assertEquals(listOf(0.0 to 3.0, 6.0 to 9.0), spans(runs(doubleArrayOf(3.0))))
    }

    @Test fun anUnusablePatternLeavesTheLineWhole() {
        val pts = listOf(Pt(0.0, 0.0), Pt(10.0, 0.0))
        assertEquals(listOf(pts), StrokeOutliner.dash(pts, false, DoubleArray(0), 0.0))
        assertEquals(listOf(pts), StrokeOutliner.dash(pts, false, doubleArrayOf(0.0, 0.0), 0.0))
    }

    @Test fun aDashLongerThanThePathIsTheWholePath() {
        val r = runs(doubleArrayOf(50.0, 5.0))
        assertEquals(1, r.size)
        assertEquals(listOf(0.0 to 10.0), spans(r))
    }

    @Test fun dashesFollowACornerKeepingTheirLength() {
        val r = StrokeOutliner.dash(corner, false, doubleArrayOf(15.0, 5.0), 0.0)
        assertEquals(1, r.size)
        assertEquals(listOf(Pt(0.0, 0.0), Pt(10.0, 0.0), Pt(10.0, 5.0)), r[0])
    }
}
