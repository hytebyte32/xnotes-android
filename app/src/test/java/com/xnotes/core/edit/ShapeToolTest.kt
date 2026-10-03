package com.xnotes.core.edit

import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.Rgba
import com.xnotes.core.tools.ShapeConfig
import com.xnotes.core.tools.ShapeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.abs

class ShapeToolTest {
    private val ink = Rgba(0, 0, 0, 255)

    @Test fun aTapMakesNoShape() {
        val t = ShapeTool()
        t.begin(Pt(10.0, 10.0), ShapeConfig(shape = ShapeKind.RECTANGLE), ink)
        t.extend(Pt(11.0, 11.0))
        assertNull(t.finish())
        assertNull(t.pending)
    }

    @Test fun aRealDragCommitsOnce() {
        val t = ShapeTool()
        t.begin(Pt(0.0, 0.0), ShapeConfig(shape = ShapeKind.RECTANGLE), ink)
        t.extend(Pt(40.0, 30.0))
        val s = t.finish()
        assertNotNull(s)
        assertEquals(Pt(40.0, 30.0), s!!.end)
        assertNull(t.finish())
    }

    @Test fun circleStaysSquare() {
        val t = ShapeTool()
        t.begin(Pt(0.0, 0.0), ShapeConfig(shape = ShapeKind.CIRCLE), ink)
        val s = t.extend(Pt(40.0, -10.0))!!
        assertEquals(40.0, s.end.x, 1e-9)
        assertEquals(-40.0, s.end.y, 1e-9)
    }

    @Test fun lineSnapsNearAnAxis() {
        val t = ShapeTool()
        t.begin(Pt(0.0, 0.0), ShapeConfig(shape = ShapeKind.LINE), ink)
        val s = t.extend(Pt(100.0, 1.0))!!
        assertEquals(0.0, abs(s.end.y), 1e-6)
    }

    @Test fun cancelDropsTheDrag() {
        val t = ShapeTool()
        t.begin(Pt(0.0, 0.0), ShapeConfig(), ink)
        t.cancel()
        assertNull(t.extend(Pt(50.0, 50.0)))
        assertNull(t.finish())
    }
}
