package com.xnotes.format

import com.xnotes.core.geometry.Pt
import com.xnotes.core.vector.VectorSeg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class SvgPathDataTest {

    private fun assertPt(x: Double, y: Double, p: Pt, eps: Double = 1e-9) {
        assertEquals("x", x, p.x, eps)
        assertEquals("y", y, p.y, eps)
    }

    private fun line(s: VectorSeg) = s as VectorSeg.Line
    private fun cubic(s: VectorSeg) = s as VectorSeg.Cubic

    @Test fun emptyAndCommandlessInputGiveNothing() {
        assertTrue(SvgPathData.parse("").isEmpty())
        assertTrue(SvgPathData.parse("   ").isEmpty())
        assertTrue(SvgPathData.parse("10 20 30").isEmpty())
    }

    @Test fun aMovetoAloneDrawsNothing() = assertTrue(SvgPathData.parse("M5 5").isEmpty())

    @Test fun absoluteMovetoAndLineto() {
        val c = SvgPathData.parse("M 10 20 L 30 40").single()
        assertPt(10.0, 20.0, c.start)
        assertFalse(c.closed)
        assertPt(30.0, 40.0, line(c.segments.single()).end)
    }

    @Test fun relativeCommandsOffsetFromTheCurrentPoint() {
        val c = SvgPathData.parse("m10 20 l5 5 l-5 0").single()
        assertPt(10.0, 20.0, c.start)
        assertPt(15.0, 25.0, c.segments[0].end)
        assertPt(10.0, 25.0, c.segments[1].end)
    }

    @Test fun extraPairsAfterAMovetoAreLinetos() {
        val c = SvgPathData.parse("M0 0 10 0 10 10").single()
        assertEquals(2, c.segments.size)
        assertPt(10.0, 0.0, c.segments[0].end)
        assertPt(10.0, 10.0, c.segments[1].end)
    }

    @Test fun extraPairsAfterARelativeMovetoAreRelativeLinetos() {
        val c = SvgPathData.parse("m1 1 2 0 0 2").single()
        assertPt(3.0, 1.0, c.segments[0].end)
        assertPt(3.0, 3.0, c.segments[1].end)
    }

    @Test fun horizontalAndVerticalLines() {
        val c = SvgPathData.parse("M0 0 H10 V5 h-2 v-1").single()
        assertEquals(listOf(10.0 to 0.0, 10.0 to 5.0, 8.0 to 5.0, 8.0 to 4.0), c.segments.map { it.end.x to it.end.y })
    }

    @Test fun closepathClosesTheContour() {
        val c = SvgPathData.parse("M0 0 L10 0 L10 10 Z").single()
        assertTrue(c.closed)
        assertEquals(2, c.segments.size)
    }

    @Test fun drawingAfterAClosepathStartsAFreshContourAtTheSubpathStart() {
        val cs = SvgPathData.parse("M2 3 L10 0 Z L5 5")
        assertEquals(2, cs.size)
        assertTrue(cs[0].closed)
        assertPt(2.0, 3.0, cs[1].start)
        assertFalse(cs[1].closed)
    }

    @Test fun eachMovetoStartsAContour() {
        val cs = SvgPathData.parse("M0 0 L1 1 M5 5 L6 6")
        assertEquals(2, cs.size)
        assertPt(5.0, 5.0, cs[1].start)
    }

    @Test fun cubicKeepsItsControlPoints() {
        val s = cubic(SvgPathData.parse("M0 0 C1 2 3 4 5 6").single().segments.single())
        assertPt(1.0, 2.0, s.c1)
        assertPt(3.0, 4.0, s.c2)
        assertPt(5.0, 6.0, s.end)
    }

    @Test fun smoothCubicReflectsThePreviousSecondControlPoint() {
        val segs = SvgPathData.parse("M0 0 C0 10 10 10 10 0 S 20 -10 20 0").single().segments
        val s = cubic(segs[1])
        assertPt(10.0, -10.0, s.c1) // (10,10) mirrored through (10,0)
        assertPt(20.0, -10.0, s.c2)
    }

    @Test fun smoothCubicWithNoPreviousCurveUsesTheCurrentPoint() {
        val s = cubic(SvgPathData.parse("M3 4 S 10 10 20 0").single().segments.single())
        assertPt(3.0, 4.0, s.c1)
    }

    @Test fun aQuadraticBecomesTheExactEquivalentCubic() {
        val s = cubic(SvgPathData.parse("M0 0 Q 6 6 12 0").single().segments.single())
        assertPt(4.0, 4.0, s.c1)
        assertPt(8.0, 4.0, s.c2)
        assertPt(12.0, 0.0, s.end)
    }

    @Test fun smoothQuadraticReflectsTheControlPoint() {
        val segs = SvgPathData.parse("M0 0 Q 6 6 12 0 T 24 0").single().segments
        val s = cubic(segs[1])
        // Control point (18,-6) from the start (12,0): c1 = start + 2/3 (q - start).
        assertPt(16.0, -4.0, s.c1)
        assertPt(24.0, 0.0, s.end)
    }

    @Test fun aQuarterArcIsOneCubicThroughTheEndpoint() {
        val segs = SvgPathData.parse("M0 0 A10 10 0 0 1 10 10").single().segments
        assertEquals(1, segs.size)
        assertPt(10.0, 10.0, segs[0].end, 1e-9)
    }

    @Test fun aHalfArcIsTwoQuarterPiecesAllOnTheCircle() {
        val segs = SvgPathData.parse("M0 0 A5 5 0 0 1 10 0").single().segments
        assertEquals(2, segs.size)
        for (s in segs) assertEquals(5.0, hypot(s.end.x - 5.0, s.end.y), 1e-9)
        assertPt(10.0, 0.0, segs.last().end, 1e-9)
        assertEquals(5.0, Math.abs(segs[0].end.y), 1e-9) // the apex
    }

    @Test fun anArcWhoseRadiiAreTooSmallIsScaledUpToReachItsEnd() {
        val segs = SvgPathData.parse("M0 0 A1 1 0 0 1 10 0").single().segments
        assertEquals(2, segs.size)
        assertPt(10.0, 0.0, segs.last().end, 1e-9)
    }

    @Test fun anArcWithAZeroRadiusIsAStraightLine() {
        val s = line(SvgPathData.parse("M0 0 A0 5 0 0 1 10 0").single().segments.single())
        assertPt(10.0, 0.0, s.end)
    }

    @Test fun anArcToItsOwnStartDrawsNothing() = assertTrue(SvgPathData.parse("M5 5 A5 5 0 0 1 5 5").isEmpty())

    @Test fun arcFlagsMayRunTogetherWithTheNumberAfterThem() {
        val segs = SvgPathData.parse("M0 0 a5 5 0 1110 0").single().segments
        assertPt(10.0, 0.0, segs.last().end, 1e-9)
    }

    @Test fun numbersMayRunTogetherBySignAndDecimalPoint() {
        val c = SvgPathData.parse("M10-20L30.5.5").single()
        assertPt(10.0, -20.0, c.start)
        assertPt(30.5, 0.5, c.segments.single().end)
    }

    @Test fun exponentsAreRead() {
        val c = SvgPathData.parse("M1e1 2E-1 L0 0").single()
        assertPt(10.0, 0.2, c.start)
    }

    @Test fun commasAndWhitespaceAreInterchangeable() {
        val c = SvgPathData.parse("M0,0\nL 1,2\t3 ,4").single()
        assertEquals(2, c.segments.size)
        assertPt(3.0, 4.0, c.segments[1].end)
    }

    @Test fun aTruncatedCommandKeepsWhatCameBefore() {
        val c = SvgPathData.parse("M0 0 L10 10 L").single()
        assertEquals(1, c.segments.size)
    }

    @Test fun anUnknownCommandStopsTheReadWithoutLosingTheStart() {
        val c = SvgPathData.parse("M0 0 L10 10 X 5 5 L9 9").single()
        assertEquals(1, c.segments.size)
    }
}
