package com.xnotes.gl

import com.xnotes.core.geometry.Rect
import com.xnotes.core.infinite.MeshData
import com.xnotes.core.infinite.translated
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PageQuadsTest {

    private fun quad(name: String, y: Double) = PageQuad(name, Rect(0.0, y, 100.0, 100.0), 1L, 0xFFFFFF)

    @Test fun fullViewQuadSpansClipSpace() {
        val c = PageQuads.corners(Rect(0.0, 0.0, 200.0, 100.0), 1.0, 0.0, 0.0, 200, 100)
        assertArrayEquals(floatArrayOf(-1f, 1f, 1f, 1f, -1f, -1f, 1f, -1f), c, 1e-6f)
    }

    @Test fun scrollAndZoomMoveTheQuad() {
        // At zoom 2 with scroll (50,0), a rect at x=50 starts at the left edge.
        val c = PageQuads.corners(Rect(50.0, 0.0, 50.0, 50.0), 2.0, 50.0, 0.0, 200, 200)
        assertEquals(-1f, c[0], 1e-6f)
        assertEquals(0f, c[2], 1e-6f)  // right edge at 100px of 200 = centre
        assertEquals(1f, c[1], 1e-6f)
        assertEquals(0f, c[5], 1e-6f)  // bottom edge at 100px of 200 = centre
    }

    @Test fun farDownTheDocumentStaysExact() {
        val y = 5_000_000.0
        val c = PageQuads.corners(Rect(0.0, y, 100.0, 100.0), 1.0, 0.0, y, 100, 100)
        assertEquals(1f, c[1], 1e-6f)
        assertEquals(-1f, c[5], 1e-6f)
    }

    @Test fun inViewKeepsOnlyPagesThatMeetTheView() {
        val pages = listOf(quad("a", 0.0), quad("b", 150.0), quad("c", 400.0))
        val seen = PageQuads.inView(pages, 1.0, 0.0, 0.0, 100, 200).map { it.key }
        assertEquals(listOf<Any>("a", "b"), seen)
    }

    @Test fun marginPullsInNeighbours() {
        val pages = listOf(quad("a", 0.0), quad("b", 150.0), quad("c", 400.0))
        val seen = PageQuads.inView(pages, 1.0, 0.0, 0.0, 100, 200, marginPx = 250.0).map { it.key }
        assertEquals(listOf<Any>("a", "b", "c"), seen)
    }

    @Test fun translatingMeshMovesPositionsOnly() {
        val m = MeshData(doubleArrayOf(0.0, 0.0, 1.0, 0.0, 0.0, 1.0), doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0), intArrayOf(0, 1, 2))
        val t = m.translated(10.0, 20.0)
        assertArrayEquals(doubleArrayOf(10.0, 20.0, 11.0, 20.0, 10.0, 21.0), t.positions, 0.0)
        assertSame(m.offsets, t.offsets)
        assertSame(m.indices, t.indices)
        assertSame(m, m.translated(0.0, 0.0))
    }
}
