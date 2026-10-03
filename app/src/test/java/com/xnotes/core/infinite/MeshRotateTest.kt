package com.xnotes.core.infinite

import org.junit.Assert.assertEquals
import org.junit.Test

class MeshRotateTest {
    @Test fun quarterTurnsMapAxes() {
        assertEquals(-2.0, rotX(1, 1.0, 2.0), 0.0); assertEquals(1.0, rotY(1, 1.0, 2.0), 0.0)
        assertEquals(-1.0, rotX(2, 1.0, 2.0), 0.0); assertEquals(-2.0, rotY(2, 1.0, 2.0), 0.0)
        assertEquals(2.0, rotX(3, 1.0, 2.0), 0.0); assertEquals(-1.0, rotY(3, 1.0, 2.0), 0.0)
        assertEquals(1.0, rotX(4, 1.0, 2.0), 0.0)
    }

    @Test fun meshTransformRotatesPositionsThenShifts() {
        val m = MeshData(doubleArrayOf(1.0, 2.0), doubleArrayOf(1.0, 0.0), intArrayOf(0, 0, 0))
        val t = m.transformed(1, 10.0, 20.0)
        assertEquals(8.0, t.positions[0], 1e-9); assertEquals(21.0, t.positions[1], 1e-9)
        assertEquals(0.0, t.offsets[0], 1e-9); assertEquals(1.0, t.offsets[1], 1e-9)
    }
}
