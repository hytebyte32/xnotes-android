package com.xnotes.format

import com.xnotes.core.model.Rgba
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SvgColorsTest {

    @Test fun sixDigitHex() = assertEquals(Rgba(0x12, 0xAB, 0xEF), SvgColors.parse("#12abef"))

    @Test fun threeDigitHexDoublesEachDigit() = assertEquals(Rgba(0xFF, 0x00, 0x88), SvgColors.parse("#f08"))

    @Test fun fourDigitHexCarriesAlpha() = assertEquals(Rgba(0xFF, 0x00, 0x00, 0x88), SvgColors.parse("#f008"))

    @Test fun eightDigitHexCarriesAlpha() = assertEquals(Rgba(1, 2, 3, 4), SvgColors.parse("#01020304"))

    @Test fun hexIsCaseAndWhitespaceInsensitive() = assertEquals(Rgba(0xAA, 0xBB, 0xCC), SvgColors.parse("  #AaBbCc "))

    @Test fun badHexIsRejected() {
        assertNull(SvgColors.parse("#12"))
        assertNull(SvgColors.parse("#12345"))
        assertNull(SvgColors.parse("#gggggg"))
    }

    @Test fun rgbWithNumbers() = assertEquals(Rgba(10, 20, 30), SvgColors.parse("rgb(10, 20, 30)"))

    @Test fun rgbWithPercentages() = assertEquals(Rgba(255, 128, 0), SvgColors.parse("rgb(100%, 50%, 0%)"))

    @Test fun rgbChannelsClamp() = assertEquals(Rgba(255, 0, 0), SvgColors.parse("rgb(300, -5, 0)"))

    @Test fun rgbaAlphaAsFractionAndPercent() {
        assertEquals(Rgba(1, 2, 3, 128), SvgColors.parse("rgba(1,2,3,0.5)"))
        assertEquals(Rgba(1, 2, 3, 128), SvgColors.parse("rgb(1 2 3 / 50%)"))
    }

    @Test fun rgbWithTooFewChannelsIsRejected() {
        assertNull(SvgColors.parse("rgb(1, 2)"))
        assertNull(SvgColors.parse("rgb(1, 2, 3"))
    }

    @Test fun namedColoursAreCaseInsensitive() {
        assertEquals(Rgba(0x46, 0x82, 0xB4), SvgColors.parse("SteelBlue"))
        assertEquals(Rgba(0xD3, 0xD3, 0xD3), SvgColors.parse("lightgrey"))
        assertEquals(SvgColors.parse("gray"), SvgColors.parse("grey"))
    }

    @Test fun noPaintAndNonsenseGiveNull() {
        assertNull(SvgColors.parse(null))
        assertNull(SvgColors.parse(""))
        assertNull(SvgColors.parse("none"))
        assertNull(SvgColors.parse("transparent"))
        assertNull(SvgColors.parse("url(#grad)"))
        assertNull(SvgColors.parse("notacolour"))
    }
}
