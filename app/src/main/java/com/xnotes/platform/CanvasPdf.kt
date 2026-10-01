package com.xnotes.platform

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.xnotes.core.infinite.BackgroundPattern
import com.xnotes.core.infinite.CanvasPagination
import com.xnotes.core.infinite.CanvasPdfLayout
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.infinite.isFinite
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.PagePattern
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.paintPagePattern
import com.xnotes.core.pal.Renderer
import java.io.OutputStream

/**
 * Flattens an infinite canvas into a PDF, the canvas counterpart of [PdfExporter].
 *
 * The canvas is cut into pages by [CanvasPagination]: page boundaries only fall in empty gaps between
 * what you drew, whole blocks of work are packed onto each page, and one too tall for a page is
 * shrunk to fit rather than split. What lands there is vector: the items are the same [CanvasItem]s the paged
 * note holds, so they paint themselves through [PdfBoxRenderer] and the ink stays real paths rather
 * than a screenshot of the canvas. The GL pipeline that draws them live is not involved at all — its
 * meshes are a render artifact, and reading pixels back off the GPU would give a raster of whatever
 * happened to be on screen at whatever zoom it was at.
 *
 * The few looks that have no vector form (neon, the highlighter's multiply, translucent ink, text)
 * are rasterized in place by [PdfItemRaster], exactly as they are on a paged export.
 */
object CanvasPdfExporter {

    /** Cap on PdfBox's in-RAM scratch during the write; the rest spills to temp files in the cache
     *  dir, so a canvas carrying many large images can't exhaust the heap. */
    private const val SCRATCH_MAIN_MEM_BYTES = 32L * 1024 * 1024

    /**
     * [paperColor] fills the page (the on-screen paper: the canvas's own colour, or the theme's).
     * [onProgress] reports `(itemsDone, totalItems)`, starting at `(0, total)`, and [isCancelled] is
     * polled per item so a dense canvas can show a dialog and abort before [out] is written.
     */
    fun export(
        context: Context,
        doc: InfiniteDocument,
        out: OutputStream,
        paperColor: Rgba,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ) {
        PDFBoxResourceLoader.init(context.applicationContext)
        // A non-finite item is outside every page by construction (contentBounds skips it too), and
        // drawn anyway it would put a NaN coordinate into the content stream and corrupt the file.
        val items = doc.items.filter { it.paintBounds().isFinite() }
        val bounds = items.map { it.paintBounds() }
        val pages = CanvasPagination.plan(bounds, doc.dpi, doc.originX, doc.originY, doc.limitW, doc.limitH)
        // Which page each item is last needed on, so its geometry is freed as soon as that page is done.
        val lastPage = IntArray(items.size) { i -> pages.indexOfLast { it.cover.intersects(bounds[i]) } }
        val visits = items.indices.sumOf { i -> pages.count { it.cover.intersects(bounds[i]) } }
        onProgress(0, visits)
        val mem = MemoryUsageSetting.setupMixed(SCRATCH_MAIN_MEM_BYTES).setTempDir(context.cacheDir)
        val outDoc = PDDocument(mem)
        var done = 0
        try {
            for ((pi, pg) in pages.withIndex()) {
                val wPts = pg.widthPoints.toFloat()
                val hPts = pg.heightPoints.toFloat()
                val page = PDPage(PDRectangle(wPts, hPts))
                outDoc.addPage(page)
                PDPageContentStream(outDoc, page).use { cs ->
                    cs.setNonStrokingColor(paperColor.r / 255f, paperColor.g / 255f, paperColor.b / 255f)
                    cs.addRect(0f, 0f, wPts, hPts)
                    cs.fill()
                    val layout = CanvasPdfLayout.Layout(pg.cover, pg.scale)
                    val r = PdfBoxRenderer(cs, outDoc, -pg.cover.left * pg.scale, hPts + pg.cover.top * pg.scale, pg.scale)
                    paintRuling(doc, r, layout)
                    for ((index, item) in items.withIndex()) {
                        if (!pg.cover.intersects(bounds[index])) continue
                        if (isCancelled()) return
                        if (PdfItemRaster.needsRaster(item)) {
                            PdfItemRaster.item(item, pg.cover)?.let { raster ->
                                r.drawItemBitmap(raster.bmp, raster.rect, raster.multiply)
                                raster.bmp.recycle()
                            }
                        } else {
                            item.paint(r)
                        }
                        // Let the ribbon go once the last page that needs it is past it.
                        if (lastPage[index] == pi) PdfItemRaster.releaseInkGeometry(item)
                        onProgress(++done, visits)
                    }
                }
            }
            outDoc.save(out)
        } finally {
            outDoc.runCatching { close() }
        }
    }

    /**
     * The canvas ruling as real vector lines, where the screen draws it procedurally in a fragment
     * shader. [BackgroundPattern] still chooses the level, handed the page's own scale as a zoom: a
     * canvas too wide to fit 1:1 gets the coarser grid it would show zoomed out that far, instead of
     * a mesh too fine for any reader to resolve. Line weight comes from the paged ruling, so a grid
     * looks the same whichever kind of document it was exported from.
     */
    private fun paintRuling(doc: InfiniteDocument, r: Renderer, layout: CanvasPdfLayout.Layout) {
        val bg = doc.background
        if (bg.pattern == PagePattern.NONE) return
        val spacing = bg.clampedSpacing
        val period = spacing * BackgroundPattern.levelMultiplier(spacing, layout.zoomEquivalent(doc.dpi))
        paintPagePattern(r, bg.pattern, bg.patternColor, period, layout.cover, layout.cover)
    }
}
