package com.xnotes.core.infinite

import com.xnotes.core.stroke.Sample
import com.xnotes.core.stroke.StrokeEngine
import com.xnotes.core.stroke.StrokeGeometry
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class TuningTest {

    @After fun restore() = Tuning.reset()

    private fun wavy(n: Int): StrokeGeometry {
        val c = ToolDefaults.configFor(Tool.PEN)
        val samples = (0 until n).map { Sample(it * 4.0, 30.0 * sin(it * PI / 20.0), 0.6) }
        return StrokeEngine.build(
            samples, c.baseWidth, c.pressureEnabled, c.pressureMinFactor, c.directionStrength,
            c.speedStrength, c.taperEnabled, c.taperMinFactor, holdEnds = true,
        )
    }

    private fun bounds(m: MeshData): DoubleArray {
        val b = doubleArrayOf(Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE)
        for (i in 0 until m.vertexCount) {
            b[0] = minOf(b[0], m.positions[2 * i]); b[1] = minOf(b[1], m.positions[2 * i + 1])
            b[2] = maxOf(b[2], m.positions[2 * i]); b[3] = maxOf(b[3], m.positions[2 * i + 1])
        }
        return b
    }

    @Test fun defaultsChangeNothing() {
        val g = wavy(40)
        val a = StrokeTessellator.tessellate(g)
        Tuning.reset()
        val b = StrokeTessellator.tessellate(g)
        assertEquals(a.vertexCount, b.vertexCount)
        assertEquals(a.indices.size, b.indices.size)
    }

    @Test fun sharedRailsKeepTrianglesAndDropVertices() {
        val g = wavy(40)
        val split = StrokeTessellator.tessellate(g)
        Tuning.sharedRails = true
        val shared = StrokeTessellator.tessellate(g)
        assertEquals(split.indices.size, shared.indices.size)
        assertTrue(shared.vertexCount < split.vertexCount)
        val a = bounds(split)
        val b = bounds(shared)
        for (k in 0..3) assertEquals(a[k], b[k], 1e-9)
        for (i in shared.indices) assertTrue(i in 0 until shared.vertexCount)
    }

    @Test fun coarserCapsUseFewerTriangles() {
        val g = wavy(40)
        val fine = StrokeTessellator.tessellate(g)
        Tuning.capTolerance = 0.5 / 8.0
        val coarse = StrokeTessellator.tessellate(g)
        assertTrue(coarse.indices.size < fine.indices.size)
    }

    @Test fun simplifyDropsSamplesButKeepsTheEnds() {
        val g = StrokeEngine.build(
            (0 until 40).map { Sample(it * 4.0, 0.0, 0.6) },
            ToolDefaults.configFor(Tool.PEN).baseWidth, false, 1.0, 0.0, 0.0, false, 1.0, holdEnds = false,
        )
        val full = StrokeTessellator.tessellate(g)
        Tuning.simplifyTolerance = 0.25
        val slim = StrokeTessellator.tessellate(g)
        assertTrue(slim.indices.size < full.indices.size)
        val a = bounds(full)
        val b = bounds(slim)
        for (k in 0..3) assertEquals(a[k], b[k], 1e-6)
    }

    @Test fun keptPointsAlwaysIncludeBothEnds() {
        val g = wavy(30)
        val kept = StrokeTessellator.keptPoints(g, 0, g.pointCount, 5.0)
        assertEquals(0, kept.first())
        assertEquals(g.pointCount - 1, kept.last())
        assertTrue(kept.size < g.pointCount)
        for (i in 1 until kept.size) assertTrue(kept[i] > kept[i - 1])
    }

    @Test fun lodLevelRaisesSimplifyOnlyWhenEnabled() {
        Tuning.lodLevel = 2
        assertEquals(0.0, Tuning.effectiveSimplify(), 0.0)
        Tuning.lodEnabled = true
        assertEquals(Lod.toleranceFor(2), Tuning.effectiveSimplify(), 0.0)
    }
}
