package com.xnotes.core.model

import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Area-erase split ([Stroke.erasedBy]): cutting a stroke's path where it crosses an eraser circle. */
class StrokeEraseTest {

    private fun stroke(vararg pts: Pair<Double, Double>): Stroke =
        Stroke(Tool.PEN, ToolConfig(), pts.map { Sample(it.first, it.second, 1.0) }.toMutableList())

    @Test fun untouchedReturnsNull() {
        val s = stroke(0.0 to 0.0, 10.0 to 0.0, 20.0 to 0.0)
        assertNull(s.erasedBy(100.0, 100.0, 5.0)) // far away -> bbox reject -> null
    }

    @Test fun emptyStrokeReturnsNull() {
        assertNull(Stroke(Tool.PEN, ToolConfig()).erasedBy(0.0, 0.0, 5.0))
    }

    @Test fun fullCoverReturnsEmpty() {
        val s = stroke(0.0 to 0.0, 1.0 to 0.0, 2.0 to 0.0)
        val frags = s.erasedBy(1.0, 0.0, 10.0) // every sample inside -> whole-removal signal
        assertNotNull(frags)
        assertTrue(frags!!.isEmpty())
    }

    @Test fun endTouchTrimsToOneFragment() {
        // x = 0,10,20,30,40,50; circle at (5,0) r=8 covers x in [-3,13], so the survivor starts at the cut x=13.
        val s = stroke(0.0 to 0.0, 10.0 to 0.0, 20.0 to 0.0, 30.0 to 0.0, 40.0 to 0.0, 50.0 to 0.0)
        val frags = s.erasedBy(5.0, 0.0, 8.0)!!
        assertEquals(1, frags.size)
        assertEquals(listOf(13.0, 20.0, 30.0, 40.0, 50.0), frags[0].samples.map { it.x })
    }

    @Test fun midHoleSplitsInTwo() {
        // circle at (25,0) r=8 covers x in [17,33] -> two fragments cut at the edge.
        val s = stroke(0.0 to 0.0, 10.0 to 0.0, 20.0 to 0.0, 30.0 to 0.0, 40.0 to 0.0, 50.0 to 0.0)
        val frags = s.erasedBy(25.0, 0.0, 8.0)!!
        assertEquals(2, frags.size)
        assertEquals(listOf(0.0, 10.0, 17.0), frags[0].samples.map { it.x }) // order preserved
        assertEquals(listOf(33.0, 40.0, 50.0), frags[1].samples.map { it.x })
    }

    @Test fun multipleHolesProduceThreeFragments() {
        // A path that crosses the eraser circle (origin, r=5) twice: samples 1 and 4 are inside.
        val s = stroke(
            10.0 to 0.0,   // out
            0.0 to 0.0,    // in  (erased)
            -10.0 to 0.0,  // out
            -10.0 to 1.0,  // out
            0.0 to 1.0,    // in  (erased)
            10.0 to 1.0,   // out
        )
        val frags = s.erasedBy(0.0, 0.0, 5.0)!!
        assertEquals(3, frags.size)
        assertEquals(listOf(2, 4, 2), frags.map { it.samples.size })
    }

    @Test fun survivorsRunUpToTheCircleEdgeNotToADot() {
        // Erase the middle of three collinear samples: each side keeps a real piece up to x=5 / x=15.
        val s = stroke(0.0 to 0.0, 10.0 to 0.0, 20.0 to 0.0)
        val frags = s.erasedBy(10.0, 0.0, 5.0)!!
        assertEquals(2, frags.size)
        assertEquals(listOf(0.0, 5.0), frags[0].samples.map { it.x })
        assertEquals(listOf(15.0, 20.0), frags[1].samples.map { it.x })
    }

    @Test fun aLongSegmentBetweenSamplesIsStillCut() {
        val s = stroke(0.0 to 0.0, 100.0 to 0.0)
        val frags = s.erasedBy(50.0, 0.0, 10.0)!!
        assertEquals(2, frags.size)
        assertEquals(40.0, frags[0].samples.last().x, 1e-4)
        assertEquals(60.0, frags[1].samples.first().x, 1e-4)
    }

    @Test fun cutPointsInterpolatePressure() {
        val s = Stroke(Tool.PEN, ToolConfig(), mutableListOf(Sample(0.0, 0.0, 0.2), Sample(100.0, 0.0, 1.0)))
        val frags = s.erasedBy(50.0, 0.0, 10.0)!!
        assertEquals(0.2 + 0.8 * 0.4, frags[0].samples.last().pressure, 1e-4)
        assertEquals(0.2 + 0.8 * 0.6, frags[1].samples.first().pressure, 1e-4)
    }

    @Test fun fragmentsShareToolConfigAndSpeedScale() {
        val cfg = ToolConfig(baseWidth = 7.0)
        val orig = Stroke(
            Tool.HIGHLIGHTER, cfg,
            mutableListOf(Sample(0.0, 0.0, 1.0), Sample(10.0, 0.0, 1.0), Sample(20.0, 0.0, 1.0)),
            speedScale = 2.5,
        )
        for (frag in orig.erasedBy(0.0, 0.0, 5.0)!!) {
            assertEquals(Tool.HIGHLIGHTER, frag.tool)
            assertSame(cfg, frag.config) // same config reference, not a copy
            assertEquals(2.5, frag.speedScale, 1e-12)
        }
    }

    @Test fun fragmentsAreIndependentCopies() {
        val orig = stroke(0.0 to 0.0, 10.0 to 0.0, 20.0 to 0.0)
        val frag = orig.erasedBy(0.0, 0.0, 5.0)!!.first() // survivors: the cut at x=5, then x=10,20
        val sizeBefore = frag.samples.size
        orig.setSamples(emptyList()) // mutating the original must not disturb the fragment's copy
        assertEquals(sizeBefore, frag.samples.size)
        assertEquals(5.0, frag.samples.first().x, 1e-4)
    }

    @Test fun agreesWithIntersectsCircle() {
        // The load-bearing invariant: erasedBy(..) != null  <=>  intersectsCircle(..) == true.
        val s = stroke(0.0 to 0.0, 10.0 to 0.0, 20.0 to 10.0, 30.0 to 10.0)
        val cases = listOf(
            Triple(5.0, 0.0, 6.0),     // hits sample 0
            Triple(100.0, 100.0, 5.0), // far miss (bbox reject)
            Triple(20.0, 10.0, 4.0),   // hits sample 2
            Triple(15.0, 5.0, 2.0),    // on the segment between samples 1 and 2
            Triple(25.0, 10.0, 50.0),  // covers everything
        )
        for ((cx, cy, r) in cases) {
            assertEquals(
                "center=($cx,$cy) r=$r",
                s.intersectsCircle(cx, cy, r),
                s.erasedBy(cx, cy, r) != null,
            )
        }
    }
}
