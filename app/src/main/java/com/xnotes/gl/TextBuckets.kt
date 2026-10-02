package com.xnotes.gl

import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

/**
 * The pixel density a text box's texture is rendered at: the zoom, rounded up to a half octave so a
 * slow pinch re-renders at steps rather than every frame, and capped so a huge box at a high zoom
 * does not ask for a texture the GPU cannot hold.
 */
object TextBuckets {
    private val LN2 = ln(2.0)
    private const val BIAS = 20
    const val MAX_EDGE_PX = 4096.0

    fun bucketFor(zoom: Double, w: Double, h: Double): Int {
        val longest = max(w, h).coerceAtLeast(1.0)
        val res = minOf(zoom.coerceAtLeast(1e-4), MAX_EDGE_PX / longest)
        return (ceil(2.0 * ln(res) / LN2).toInt() + BIAS).coerceIn(0, 63)
    }

    /** Pixels per content unit a bucket stands for. */
    fun resFor(bucket: Int): Double = 2.0.pow((bucket - BIAS) / 2.0)
}
