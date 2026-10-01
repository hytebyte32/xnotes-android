package com.xnotes.bench

import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.Document
import com.xnotes.core.model.Orientation
import com.xnotes.core.model.PageSize
import com.xnotes.core.model.Stroke
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Synthetic ink for the renderer spike. The same strokes, at the same density, are laid out two
 * ways: as pages (the paged notebook) and as one tall column of the same width (what an infinite
 * region of page width would be), so the two renderers draw identical content.
 */
object BenchData {
    /** Strokes per page-sized area: dense handwriting, roughly a full page of notes. */
    const val PER_PAGE = 1000

    /** One page in document pixels (A4 portrait at the app's default dpi). */
    val page: Pair<Double, Double> = PageSize.A4.pixels(Orientation.PORTRAIT, PageSize.DEFAULT_DPI)

    private const val SAMPLES = 40
    private const val STEP = 7.0

    /** A wandering stroke of about [SAMPLES] samples that starts inside a [w] x [h] box at ([ox], [oy]). */
    private fun stroke(rnd: Random, ox: Double, oy: Double, w: Double, h: Double): Stroke {
        var x = ox + 20.0 + rnd.nextDouble() * (w - 340.0)
        var y = oy + 20.0 + rnd.nextDouble() * (h - 340.0)
        var heading = rnd.nextDouble() * 2.0 * PI
        val turn = (rnd.nextDouble() - 0.5) * 0.35
        val list = ArrayList<Sample>(SAMPLES)
        for (i in 0 until SAMPLES) {
            val pressure = 0.35 + 0.65 * sin(PI * i / (SAMPLES - 1))
            list.add(Sample(x, y, pressure))
            heading += turn + (rnd.nextDouble() - 0.5) * 0.15
            x += cos(heading) * STEP
            y += sin(heading) * STEP
        }
        return Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN), list)
    }

    /** [strokes] strokes spread over as many pages as they need, [PER_PAGE] to a page. */
    fun pagedDocument(strokes: Int, perPage: Int = PER_PAGE, seed: Int = 1): Document {
        val pages = ((strokes + perPage - 1) / perPage).coerceAtLeast(1)
        val doc = Document.blank(count = pages)
        val rnd = Random(seed)
        var left = strokes
        for (p in doc.pages) {
            val here = minOf(perPage, left)
            for (i in 0 until here) p.items.add(stroke(rnd, 0.0, 0.0, p.width, p.height))
            left -= here
        }
        return doc
    }

    /** The same ink as one column page-wide: page [k] of [pagedDocument] sits at y = k * page height. */
    fun canvasDocument(strokes: Int, perPage: Int = PER_PAGE, seed: Int = 1): InfiniteDocument {
        val (w, h) = page
        val pages = ((strokes + perPage - 1) / perPage).coerceAtLeast(1)
        val doc = InfiniteDocument()
        val rnd = Random(seed)
        var left = strokes
        val batch = ArrayList<CanvasItem>(minOf(strokes, 5000))
        for (k in 0 until pages) {
            val here = minOf(perPage, left)
            for (i in 0 until here) batch.add(stroke(rnd, 0.0, k * h, w, h))
            left -= here
            if (batch.size >= 5000) {
                doc.addAll(ArrayList(batch))
                batch.clear()
            }
        }
        if (batch.isNotEmpty()) doc.addAll(batch)
        return doc
    }
}
