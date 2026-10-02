package com.xnotes.platform

import com.xnotes.canvas.PdfColorFilter
import com.xnotes.canvas.ViewOverrides
import com.xnotes.canvas.ViewSettings
import com.xnotes.canvas.ViewingMode
import com.xnotes.core.model.Rgba
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewSettingsJsonTest {

    @Test fun defaultsAreOmittedOnWrite() {
        assertEquals(0, ViewSettingsJson.write(JSONObject(), ViewSettings()).length())
    }

    @Test fun anEmptyObjectReadsAsTheDefaults() {
        assertEquals(ViewSettings(), ViewSettingsJson.read(JSONObject()))
    }

    @Test fun everyFieldRoundTrips() {
        val s = ViewSettings(
            mode = ViewingMode.COVER, verticalScroll = false, contrast = 130, invert = 40,
            brightness = 80, sepia = 150, multiply = Rgba(255, 128, 0), screen = Rgba(0, 64, 255),
            keepImages = true, rotation = 90, scrollbar = true,
        )
        assertEquals(s, ViewSettingsJson.read(ViewSettingsJson.write(JSONObject(), s)))
    }

    @Test fun onlyTheChangedFieldsAreWritten() {
        val o = ViewSettingsJson.write(JSONObject(), ViewSettings(contrast = 120, scrollbar = true))
        assertEquals(setOf("contrast", "scrollbar"), o.keys().asSequence().toSet())
    }

    @Test fun outOfRangeValuesAreClamped() {
        val s = ViewSettingsJson.read(JSONObject("""{"contrast":999,"invert":-5,"brightness":-1,"sepia":500}"""))
        assertEquals(200, s.contrast)
        assertEquals(0, s.invert)
        assertEquals(0, s.brightness)
        assertEquals(200, s.sepia)
    }

    @Test fun anUnknownModeFallsBackToSingle() {
        assertEquals(ViewingMode.SINGLE, ViewSettingsJson.read(JSONObject("""{"mode":"sideways"}""")).mode)
    }

    @Test fun anOddRotationSnapsToAQuarterTurn() {
        val r = ViewSettingsJson.read(JSONObject("""{"rotation":100}""")).rotation
        assertTrue(r in ViewSettings.ROTATIONS)
    }

    @Test fun aMalformedBlendColourIsOff() {
        val s = ViewSettingsJson.read(JSONObject("""{"multiply":"red","screen":"#12"}"""))
        assertEquals(PdfColorFilter.MULTIPLY_OFF, s.multiply)
        assertEquals(PdfColorFilter.SCREEN_OFF, s.screen)
    }

    @Test fun overridesThatAreAllNullWriteNothingAndReadBackNull() {
        assertEquals(0, ViewOverridesJson.write(JSONObject(), ViewOverrides()).length())
        assertEquals(ViewOverrides(), ViewOverridesJson.read(JSONObject()))
    }

    @Test fun anOverrideEqualToTheDefaultIsStillWrittenAndKept() {
        val o = ViewOverridesJson.write(JSONObject(), ViewOverrides(contrast = 100, verticalScroll = true))
        assertEquals(100, o.getInt("contrast"))
        val back = ViewOverridesJson.read(o)
        assertEquals(100, back.contrast)
        assertEquals(true, back.verticalScroll)
        assertNull(back.sepia)
    }

    @Test fun overridesRoundTripAndResolveAgainstDefaults() {
        val ov = ViewOverrides(
            mode = ViewingMode.DOUBLE, contrast = 150, multiply = Rgba(1, 2, 3), rotation = 180, keepImages = false,
        )
        val back = ViewOverridesJson.read(ViewOverridesJson.write(JSONObject(), ov))
        assertEquals(ov, back)
        val resolved = back.resolve(ViewSettings(sepia = 20))
        assertEquals(ViewingMode.DOUBLE, resolved.mode)
        assertEquals(150, resolved.contrast)
        assertEquals(20, resolved.sepia)
    }

    @Test fun overrideBlendColoursAreForcedOpaque() {
        val back = ViewOverridesJson.read(JSONObject("""{"multiply":"#ff800040"}"""))
        assertTrue(back.multiply == null || back.multiply!!.a == 255)
    }
}
