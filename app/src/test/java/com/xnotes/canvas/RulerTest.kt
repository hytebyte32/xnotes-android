package com.xnotes.canvas

import org.junit.Assert.assertEquals
import org.junit.Test

class RulerTest {

    @Test fun pxToCm() {
        // 150 px at 150 dpi = 1 inch = 2.54 cm.
        assertEquals(2.54, RulerMath.contentPxToCm(150.0, 150), 1e-9)
    }

    @Test fun viewportLenToCmScalesWithZoom() {
        assertEquals(2.54, RulerMath.viewportLenToCm(150.0, 1.0, 150), 1e-9)
        assertEquals(1.27, RulerMath.viewportLenToCm(150.0, 2.0, 150), 1e-9)
    }
}
