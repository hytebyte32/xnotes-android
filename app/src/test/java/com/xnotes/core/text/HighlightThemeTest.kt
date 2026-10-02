package com.xnotes.core.text

import com.xnotes.core.model.Rgba
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HighlightThemeTest {

    private val kw = Rgba(1, 2, 3)
    private val kwReturn = Rgba(4, 5, 6)
    private val theme = HighlightTheme(mapOf("keyword" to kw, "keyword.return" to kwReturn))

    @Test fun anExactCaptureWins() = assertEquals(kwReturn, theme.colorFor("keyword.return"))

    @Test fun aDeeperCaptureFallsBackToItsLongestKnownPrefix() {
        assertEquals(kwReturn, theme.colorFor("keyword.return.async"))
        assertEquals(kw, theme.colorFor("keyword.control.flow"))
    }

    @Test fun anUnknownCaptureIsNull() {
        assertNull(theme.colorFor("string"))
        assertNull(theme.colorFor(""))
    }

    @Test fun aPrefixIsMatchedOnDotBoundariesOnly() = assertNull(theme.colorFor("keywords"))

    @Test fun backgroundIsOptional() {
        assertNull(theme.background)
        assertNotNull(HighlightTheme.DARK.background)
        assertNull(HighlightTheme.LIGHT.background)
    }

    @Test fun bothPresetsColourTheCommonCaptures() {
        for (t in listOf(HighlightTheme.DARK, HighlightTheme.LIGHT)) {
            for (c in listOf("keyword", "function", "type", "string", "number", "comment", "variable", "punctuation.bracket")) {
                assertNotNull("$c", t.colorFor(c))
            }
        }
    }

    @Test fun darkAndLightDifferForTheSameCapture() {
        assertNotEquals(HighlightTheme.DARK.colorFor("comment"), HighlightTheme.LIGHT.colorFor("comment"))
    }
}
