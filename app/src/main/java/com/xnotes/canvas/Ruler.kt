package com.xnotes.canvas

import com.xnotes.core.model.PageSize

/** Pure unit conversions for the ruler's graduations and length readout. */
object RulerMath {
    /** Content-space pixels per centimetre at [dpi]. */
    fun contentPxPerCm(dpi: Int): Double = PageSize.mmToPx(10.0, dpi)

    /** Content-space pixel length to centimetres at [dpi]. */
    fun contentPxToCm(px: Double, dpi: Int): Double = PageSize.pxToMm(px, dpi) / 10.0

    /**
     * Centimetres spanned by a screen-fixed [viewportPx] length at [zoom]: the ruler
     * stays a constant size on screen, so the same screen span covers fewer page
     * centimetres the further you zoom in.
     */
    fun viewportLenToCm(viewportPx: Double, zoom: Double, dpi: Int): Double =
        contentPxToCm(viewportPx / zoom, dpi)
}
