package com.xnotes.core.edit

import com.xnotes.core.model.Stroke
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StrokeToolTest {

    private fun stroke(vararg pts: Pair<Double, Double>): Stroke = Stroke(
        Tool.PEN,
        ToolDefaults.configFor(Tool.PEN),
        pts.map { Sample(it.first, it.second, 1.0) }.toMutableList(),
    )

    @Test fun gateIsScreenSpaceAndCappedWhenZoomedOut() {
        assertEquals(1.0, StrokeTool.captureGate(0.25), 1e-9)
        assertEquals(1.0, StrokeTool.captureGate(1.0), 1e-9)
        assertEquals(0.5, StrokeTool.captureGate(2.0), 1e-9)
    }

    @Test fun tooFewSamplesNeverSnap() {
        val s = stroke(*(0 until 5).map { it * 10.0 to 0.0 }.toTypedArray())
        assertNull(StrokeTool.snapToShape(s))
    }

    @Test fun aStraightHeldStrokeSnapsToAShapeThatKeepsItsInk() {
        val s = stroke(*(0 until 30).map { it * 5.0 to 0.0 }.toTypedArray())
        val shape = StrokeTool.snapToShape(s)
        assertNotNull(shape)
        assertEquals(s.config.rgba, shape!!.strokeRgba)
        assertEquals(s.config.baseWidth * ShapeTool.PEN_PARITY, shape.strokeWidth, 1e-9)
    }

    @Test fun simplifyNeverGrowsAStroke() {
        val s = stroke(*(0 until 200).map { it * 1.0 to 0.0 }.toTypedArray())
        val before = s.sampleCount
        StrokeTool.simplifyForCommit(s, 1.0)
        assertTrue(s.sampleCount <= before)
        assertTrue(s.sampleCount >= 2)
    }
}
