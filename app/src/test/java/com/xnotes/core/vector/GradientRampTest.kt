package com.xnotes.core.vector

import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.Rgba
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GradientRampTest {

    private val black = Rgba(0, 0, 0)
    private val white = Rgba(255, 255, 255)
    private val stops = listOf(GradientStop(0.0, black), GradientStop(1.0, white))

    private fun linear(spread: SpreadMethod = SpreadMethod.PAD, s: List<GradientStop> = stops) =
        GradientRamp.of(VectorPaint.Linear(0.0, 0.0, 10.0, 0.0, s, spread))!!

    private fun radial(r: Double = 10.0, fx: Double = 0.0, fy: Double = 0.0, spread: SpreadMethod = SpreadMethod.PAD) =
        GradientRamp.of(VectorPaint.Radial(0.0, 0.0, r, fx, fy, stops, spread))!!

    @Test fun aSolidPaintHasNoRamp() = assertNull(GradientRamp.of(VectorPaint.Solid(black)))

    @Test fun aGradientWithNoStopsHasNoRamp() {
        assertNull(GradientRamp.of(VectorPaint.Linear(0.0, 0.0, 1.0, 0.0, emptyList(), SpreadMethod.PAD)))
        assertNull(GradientRamp.of(VectorPaint.Radial(0.0, 0.0, 1.0, 0.0, 0.0, emptyList(), SpreadMethod.PAD)))
    }

    @Test fun linearInterpolatesAlongTheVector() {
        val r = linear()
        assertEquals(black, r.colorAt(Pt(0.0, 0.0)))
        assertEquals(white, r.colorAt(Pt(10.0, 0.0)))
        assertEquals(Rgba(127, 127, 127), r.colorAt(Pt(5.0, 0.0)))
    }

    @Test fun linearIsConstantAcrossTheVector() {
        val r = linear()
        assertEquals(r.colorAt(Pt(5.0, 0.0)), r.colorAt(Pt(5.0, 999.0)))
    }

    @Test fun padHoldsTheEndColoursBeyondTheVector() {
        val r = linear(SpreadMethod.PAD)
        assertEquals(black, r.colorAt(Pt(-50.0, 0.0)))
        assertEquals(white, r.colorAt(Pt(50.0, 0.0)))
    }

    @Test fun repeatRestartsTheRamp() {
        val r = linear(SpreadMethod.REPEAT)
        assertEquals(r.colorAt(Pt(5.0, 0.0)), r.colorAt(Pt(15.0, 0.0)))
        assertEquals(r.colorAt(Pt(5.0, 0.0)), r.colorAt(Pt(-5.0, 0.0)))
    }

    @Test fun reflectRunsTheRampBackwards() {
        val r = linear(SpreadMethod.REFLECT)
        assertEquals(r.colorAt(Pt(5.0, 0.0)), r.colorAt(Pt(15.0, 0.0)))
        assertEquals(r.colorAt(Pt(2.5, 0.0)), r.colorAt(Pt(17.5, 0.0))) // t = 1.75 reflects to 0.25
    }

    @Test fun aZeroLengthLinearGradientIsTheLastStopEverywhere() {
        val r = GradientRamp.of(VectorPaint.Linear(3.0, 3.0, 3.0, 3.0, stops, SpreadMethod.REPEAT))!!
        assertEquals(white, r.colorAt(Pt(0.0, 0.0)))
        assertEquals(white, r.colorAt(Pt(100.0, -4.0)))
    }

    @Test fun stopsAreSortedClampedAndKeptNonDecreasing() {
        val unsorted = listOf(GradientStop(1.5, white), GradientStop(-1.0, black))
        val r = linear(s = unsorted)
        assertEquals(black, r.colorAt(Pt(0.0, 0.0)))
        assertEquals(white, r.colorAt(Pt(10.0, 0.0)))
    }

    @Test fun alphaInterpolatesLikeAnyChannel() {
        val s = listOf(GradientStop(0.0, Rgba(10, 20, 30, 0)), GradientStop(1.0, Rgba(10, 20, 30, 255)))
        assertEquals(Rgba(10, 20, 30, 127), linear(s = s).colorAt(Pt(5.0, 0.0)))
    }

    @Test fun threeStopsInterpolateWithinTheirOwnSegments() {
        val red = Rgba(255, 0, 0)
        val s = listOf(GradientStop(0.0, black), GradientStop(0.5, red), GradientStop(1.0, white))
        val r = linear(s = s)
        assertEquals(red, r.colorAt(Pt(5.0, 0.0)))
        assertEquals(Rgba(127, 0, 0), r.colorAt(Pt(2.5, 0.0)))
    }

    @Test fun nonFinitePositionsFallBackToTheFirstStop() {
        assertEquals(black, linear().colorAt(Pt(Double.NaN, 0.0)))
    }

    @Test fun radialGoesFromCentreToCircle() {
        val r = radial()
        assertEquals(black, r.colorAt(Pt(0.0, 0.0)))
        assertEquals(Rgba(127, 127, 127), r.colorAt(Pt(5.0, 0.0)))
        assertEquals(r.colorAt(Pt(0.0, 5.0)), r.colorAt(Pt(5.0, 0.0)))
        assertEquals(white, r.colorAt(Pt(20.0, 0.0)))
    }

    @Test fun aZeroRadiusRadialIsTheLastStop() {
        assertEquals(white, radial(r = 0.0).colorAt(Pt(0.0, 0.0)))
    }

    @Test fun aFocalPointStartsTheRampAtTheFocusAndEndsOnTheCircle() {
        val r = radial(fx = 5.0)
        assertEquals(black, r.colorAt(Pt(5.0, 0.0)))
        assertEquals(white, r.colorAt(Pt(10.0, 0.0)))
    }

    @Test fun aFocusOutsideTheCircleIsPulledBackInsideAndStillSolvable() {
        val r = radial(fx = 50.0)
        assertNotNull(r.colorAt(Pt(3.0, 3.0)))
    }

    @Test fun averageIsTheMeanOfTheStops() {
        assertEquals(Rgba(127, 127, 127, 255), linear().average())
    }
}
