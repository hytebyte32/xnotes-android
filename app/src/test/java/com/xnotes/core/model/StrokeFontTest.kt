package com.xnotes.core.model

import com.xnotes.core.geometry.Pt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StrokeFontTest {

    @Test fun everyDigitHasStrokesInsideItsBox() {
        for (c in '0'..'9') {
            val g = StrokeFont.glyph(c)
            assertTrue("$c has strokes", g.strokes.isNotEmpty())
            for (s in g.strokes) for (p in s) {
                assertTrue("$c y=${p.y}", p.y in -0.001..1.001)
                assertTrue("$c x=${p.x}", p.x in -0.001..g.advance + 0.001)
            }
        }
    }

    @Test fun aNineIsASixTurnedHalfAround() {
        val six = StrokeFont.glyph('6').strokes.single()
        val nine = StrokeFont.glyph('9').strokes.single()
        assertEquals(six.size, nine.size)
        for (i in six.indices) {
            assertEquals(0.6 - six[i].x, nine[i].x, 1e-12)
            assertEquals(1.0 - six[i].y, nine[i].y, 1e-12)
        }
    }

    @Test fun aSpaceIsBlankButTakesRoom() {
        val g = StrokeFont.glyph(' ')
        assertTrue(g.strokes.isEmpty())
        assertTrue(g.advance > 0.0)
    }

    @Test fun anUnknownCharacterFallsBackToABlank() {
        val g = StrokeFont.glyph('€')
        assertTrue(g.strokes.isEmpty())
        assertEquals(StrokeFont.glyph(' ').advance, g.advance, 0.0)
    }

    @Test fun widthIsTheSumOfAdvances() {
        assertEquals(StrokeFont.glyph('1').advance + StrokeFont.glyph('.').advance + StrokeFont.glyph('5').advance,
            StrokeFont.width("1.5"), 1e-12)
        assertEquals(0.0, StrokeFont.width(""), 0.0)
    }

    @Test fun layoutPlacesGlyphsAtTheOriginScaledByHeight() {
        val one = StrokeFont.glyph('1').strokes.single()
        val out = StrokeFont.layout("1", 10.0, 20.0, 100.0)
        assertEquals(1, out.size)
        assertEquals(Pt(10.0 + one[0].x * 100.0, 20.0 + one[0].y * 100.0), out[0][0])
    }

    @Test fun laterGlyphsAreOffsetByEarlierAdvances() {
        val out = StrokeFont.layout("11", 0.0, 0.0, 10.0)
        assertEquals(2, out.size)
        val adv = StrokeFont.glyph('1').advance * 10.0
        for (i in out[0].indices) {
            assertEquals(out[0][i].x + adv, out[1][i].x, 1e-9)
            assertEquals(out[0][i].y, out[1][i].y, 1e-9)
        }
    }

    @Test fun spacesAddOffsetButNoStrokes() {
        val tight = StrokeFont.layout("11", 0.0, 0.0, 10.0)
        val spaced = StrokeFont.layout("1 1", 0.0, 0.0, 10.0)
        assertEquals(2, spaced.size)
        assertEquals(tight[1][0].x + StrokeFont.glyph(' ').advance * 10.0, spaced[1][0].x, 1e-9)
    }

    @Test fun emptyTextHasNoLayout() = assertTrue(StrokeFont.layout("", 0.0, 0.0, 10.0).isEmpty())

    @Test fun theUnitLettersAndSymbolsMeasurementLabelsNeedAreThere() {
        for (c in "0123456789.-°cmin ") assertTrue("missing $c", c == ' ' || StrokeFont.glyph(c).strokes.isNotEmpty())
    }
}
