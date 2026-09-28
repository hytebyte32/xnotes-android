package com.xnotes.core.measure

import com.xnotes.canvas.RulerMath
import kotlin.math.abs

/**
 * Real-world measuring shared by every measuring tool on both canvases (ruler, protractor).
 *
 * The rule is one and the same everywhere: a length in content pixels is a physical length at the
 * document's dpi, so a reading never depends on which canvas it was taken on. What zoom does is
 * decide how big that length looks on the glass; [ScreenCalibration] says which zoom makes it look
 * exactly its real size.
 *
 * Pure Kotlin, so all of it unit-tests.
 */
object Measure {

    /** A length in content pixels, in centimetres at [dpi]. */
    fun cm(contentPx: Double, dpi: Int): Double = RulerMath.contentPxToCm(contentPx, dpi)

    /**
     * A reading for display: millimetres under a centimetre, otherwise centimetres, or inches when
     * [inches] is set. Precision follows the size so a reading never looks falsely exact.
     */
    fun format(contentPx: Double, dpi: Int, inches: Boolean): String {
        val cm = abs(cm(contentPx, dpi))
        if (inches) {
            val inch = cm / CM_PER_INCH
            return "%.2f in".format(inch)
        }
        return if (cm < 1.0) "%.1f mm".format(cm * 10.0) else "%.2f cm".format(cm)
    }
}

const val CM_PER_INCH = 2.54

/**
 * How many screen pixels make a real centimetre on this device's glass.
 *
 * The tablet reports its density, which is right to a few percent. [fromReference] takes a
 * measured one for anyone who wants it exact: an object of known real length is matched on screen
 * and the ratio is the answer.
 */
object ScreenCalibration {

    /** Screen pixels per centimetre from a density in pixels per inch. */
    fun pxPerCmFromDpi(dpi: Double): Double = dpi / CM_PER_INCH

    /** The hand calibration when there is one (> 0), otherwise the density the device reports. */
    fun resolve(calibratedPxPerCm: Double, deviceXdpi: Double): Double =
        if (calibratedPxPerCm > 0.0) calibratedPxPerCm else pxPerCmFromDpi(deviceXdpi)

    /** Screen pixels per centimetre from a reference of [realCm] matched by [onScreenPx] pixels. */
    fun fromReference(onScreenPx: Double, realCm: Double): Double? {
        if (onScreenPx <= 0.0 || realCm <= 0.0) return null
        return onScreenPx / realCm
    }

    /**
     * The zoom at which a content centimetre is a real centimetre on the glass, for a document at
     * [docDpi] on a screen of [screenPxPerCm].
     */
    fun realSizeZoom(screenPxPerCm: Double, docDpi: Int): Double =
        screenPxPerCm / RulerMath.contentPxPerCm(docDpi)

    /** Reference objects to match. Lengths are of the long edge, in centimetres. */
    val REFERENCES: List<Pair<String, Double>> = listOf(
        "Credit / bank card" to 8.56,
        "A4 paper, short edge" to 21.0,
    )
}
