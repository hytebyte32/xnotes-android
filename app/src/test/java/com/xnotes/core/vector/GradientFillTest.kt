package com.xnotes.core.vector

import com.xnotes.core.geometry.Pt
import com.xnotes.core.infinite.MeshData
import com.xnotes.core.model.Rgba
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GradientFillTest {

    private val stops = listOf(GradientStop(0.0, Rgba(0, 0, 0)), GradientStop(1.0, Rgba(255, 255, 255)))

    private fun triangle(a: Pt, b: Pt, c: Pt) = Triangulator.Mesh(listOf(a, b, c), intArrayOf(0, 1, 2))

    private fun area(m: MeshData): Double {
        var sum = 0.0
        var i = 0
        while (i < m.indices.size) {
            val a = m.indices[i]; val b = m.indices[i + 1]; val c = m.indices[i + 2]
            val ax = m.positions[2 * a]; val ay = m.positions[2 * a + 1]
            val bx = m.positions[2 * b]; val by = m.positions[2 * b + 1]
            val cx = m.positions[2 * c]; val cy = m.positions[2 * c + 1]
            sum += abs((bx - ax) * (cy - ay) - (cx - ax) * (by - ay)) / 2.0
            i += 3
        }
        return sum
    }

    @Test fun aTwoStopLinearRampIsExactSoTheTriangleIsNotSplit() {
        val ramp = GradientRamp.of(VectorPaint.Linear(0.0, 0.0, 10.0, 0.0, stops, SpreadMethod.PAD))!!
        val a = Pt(0.0, 0.0); val b = Pt(10.0, 0.0); val c = Pt(0.0, 10.0)
        val out = GradientFill.refine(triangle(a, b, c), ramp)
        assertEquals(3, out.vertexCount)
        assertEquals(1, out.triangleCount)
        val colors = out.colors!!
        assertEquals(ramp.colorAt(a).toArgb(), colors[0])
        assertEquals(ramp.colorAt(b).toArgb(), colors[1])
        assertEquals(ramp.colorAt(c).toArgb(), colors[2])
    }

    @Test fun aRadialRampSplitsWhereInterpolationWouldBeWrong() {
        val ramp = GradientRamp.of(VectorPaint.Radial(0.0, 0.0, 30.0, 0.0, 0.0, stops, SpreadMethod.PAD))!!
        val out = GradientFill.refine(triangle(Pt(-20.0, -20.0), Pt(20.0, -20.0), Pt(0.0, 20.0)), ramp)
        assertTrue("split into ${out.triangleCount}", out.triangleCount > 1)
        assertTrue(out.triangleCount <= 24000 + 4)
    }

    @Test fun refiningNeverChangesTheCoveredArea() {
        val ramp = GradientRamp.of(VectorPaint.Radial(0.0, 0.0, 30.0, 0.0, 0.0, stops, SpreadMethod.PAD))!!
        val out = GradientFill.refine(triangle(Pt(-20.0, -20.0), Pt(20.0, -20.0), Pt(0.0, 20.0)), ramp)
        assertEquals(800.0, area(out), 1e-6)
    }

    @Test fun refinedVerticesAreUnsharedAndEachHasAColour() {
        val ramp = GradientRamp.of(VectorPaint.Radial(0.0, 0.0, 30.0, 0.0, 0.0, stops, SpreadMethod.PAD))!!
        val out = GradientFill.refine(triangle(Pt(-20.0, -20.0), Pt(20.0, -20.0), Pt(0.0, 20.0)), ramp)
        assertArrayEquals(IntArray(out.vertexCount) { it }, out.indices)
        assertEquals(out.vertexCount, out.colors!!.size)
        // Every vertex colour is the ramp's true value at that vertex.
        for (i in 0 until out.vertexCount) {
            val p = Pt(out.positions[2 * i], out.positions[2 * i + 1])
            assertEquals(ramp.colorAt(p).toArgb(), out.colors!![i])
        }
    }

    @Test fun anEmptyMeshRefinesToNothing() {
        val ramp = GradientRamp.of(VectorPaint.Linear(0.0, 0.0, 10.0, 0.0, stops, SpreadMethod.PAD))!!
        val out = GradientFill.refine(Triangulator.Mesh(emptyList(), IntArray(0)), ramp)
        assertEquals(0, out.vertexCount)
        assertTrue(out.isEmpty)
    }

    @Test fun colourKeepsTheGeometryAndColoursEachVertexFromTheRamp() {
        val ramp = GradientRamp.of(VectorPaint.Linear(0.0, 0.0, 10.0, 0.0, stops, SpreadMethod.PAD))!!
        val src = MeshData(doubleArrayOf(0.0, 0.0, 10.0, 0.0, 5.0, 5.0), DoubleArray(6), intArrayOf(0, 1, 2))
        val out = GradientFill.color(src, ramp)
        assertArrayEquals(src.positions, out.positions, 0.0)
        assertArrayEquals(src.indices, out.indices)
        assertNotNull(out.colors)
        assertEquals(Rgba(0, 0, 0).toArgb(), out.colors!![0])
        assertEquals(Rgba(255, 255, 255).toArgb(), out.colors!![1])
        assertEquals(Rgba(127, 127, 127).toArgb(), out.colors!![2])
    }
}
