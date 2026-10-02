package com.xnotes.core.model

import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Pt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class LabelItemTest {

    private fun label(text: String = "10") = LabelItem(Pt(100.0, 200.0), text, 100.0)

    @Test fun isAResizableFalseLabelKind() {
        val l = label()
        assertEquals("label", l.kind)
        assertFalse(l.resizable)
        assertFalse(l.locked)
    }

    @Test fun oneLineShapePerDrawnGlyphStroke() {
        assertEquals(2, label("10").strokes().size)
        assertEquals(2, label("1 0").strokes().size) // the space draws nothing
        assertTrue(label("").strokes().isEmpty())
    }

    @Test fun lineWidthIsAShareOfTheHeight() {
        val s = label().strokes().first()
        assertEquals(100.0 * LabelItem.LINE_FRAC, s.strokeWidth, 1e-9)
    }

    @Test fun strokesAreCachedUntilSomethingChanges() {
        val l = label()
        val a = l.strokes()
        assertSame(a, l.strokes())
        l.text = "11"
        val b = l.strokes()
        assertNotSame(a, b)
        l.translate(1.0, 1.0)
        assertNotSame(b, l.strokes())
    }

    @Test fun boundsCoverTheTextBoxPlusHalfALineWidth() {
        val b = label("10").bounds()
        val pad = 100.0 * LabelItem.LINE_FRAC / 2.0 + 1.0
        assertEquals(100.0 - pad, b.left, 1e-9)
        assertEquals(200.0 - pad, b.top, 1e-9)
        assertEquals(StrokeFont.width("10") * 100.0 + 2 * pad, b.w, 1e-9)
        assertEquals(100.0 + 2 * pad, b.h, 1e-9)
    }

    @Test fun hitTestingUsesTheBounds() {
        val l = label()
        assertTrue(l.contains(Pt(120.0, 250.0)))
        assertFalse(l.contains(Pt(0.0, 0.0)))
        assertTrue(l.intersectsCircle(0.0, 250.0, 101.0))
        assertFalse(l.intersectsCircle(0.0, 250.0, 50.0))
        assertEquals(l.bounds().center, l.centroid())
    }

    @Test fun translateMovesThePosition() {
        val l = label()
        l.translate(5.0, -7.0)
        assertEquals(Pt(105.0, 193.0), l.pos)
    }

    @Test fun snapshotRestoresPositionAndHeight() {
        val l = label()
        val snap = l.snapshotGeometry()
        l.translate(50.0, 50.0)
        l.height = 10.0
        l.restoreGeometry(snap)
        assertEquals(Pt(100.0, 200.0), l.pos)
        assertEquals(100.0, l.height, 0.0)
    }

    @Test fun aTransformMovesTheLabelAndScalesItsType() {
        val l = label()
        l.applyTransform(Affine(2.0, 0.0, 0.0, 2.0, 10.0, 0.0))
        assertEquals(Pt(210.0, 400.0), l.pos)
        assertEquals(200.0, l.height, 1e-9)
    }
}
