package com.xnotes.core.infinite

import com.xnotes.core.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasPaginationTest {

    private val dpi = 150
    private val base = 72.0 / dpi

    private fun plan(bounds: List<Rect>, limitW: Double? = null, limitH: Double? = null) =
        CanvasPagination.plan(bounds, dpi, 0.0, 0.0, limitW, limitH)

    @Test fun anEmptyEndlessCanvasIsOneA4PortraitPage() {
        val pages = plan(emptyList())
        assertEquals(1, pages.size)
        assertEquals(base, pages[0].scale, 1e-12)
        assertEquals(297.0 / 210.0, pages[0].cover.h / pages[0].cover.w, 0.01)
    }

    @Test fun nonFiniteBoundsAreIgnored() {
        val pages = plan(listOf(Rect(Double.NaN, 0.0, 10.0, 10.0)))
        assertEquals(1, pages.size)
        assertEquals(base, pages[0].scale, 1e-12)
    }

    @Test fun aFullyLimitedCanvasIsOnePageWhateverIsOnIt() {
        val pages = plan(listOf(Rect(0.0, 0.0, 5000.0, 90000.0)), limitW = 800.0, limitH = 600.0)
        assertEquals(1, pages.size)
        assertEquals(Rect(0.0, 0.0, 800.0, 600.0), pages[0].cover)
        assertEquals(base, pages[0].scale, 1e-12)
    }

    @Test fun anOversizedLimitedCanvasIsShrunkUnderThePdfCeiling() {
        val page = plan(emptyList(), limitW = 100_000.0, limitH = 100_000.0).single()
        assertTrue(page.scale < base)
        assertEquals(CanvasPdfLayout.MAX_PAGE_POINTS, page.widthPoints, 1e-6)
    }

    @Test fun nearbyBlocksShareAPage() {
        val pages = plan(listOf(Rect(100.0, 100.0, 200.0, 200.0), Rect(100.0, 400.0, 200.0, 200.0)))
        assertEquals(1, pages.size)
        // Padded 0.2 in (30 px at 150 dpi) above the first block.
        assertEquals(70.0, pages[0].cover.top, 1e-9)
    }

    @Test fun aFarAwayBlockStartsANewPageAtItsOwnTop() {
        val pages = plan(listOf(Rect(100.0, 100.0, 200.0, 200.0), Rect(100.0, 5000.0, 200.0, 200.0)))
        assertEquals(2, pages.size)
        assertEquals(70.0, pages[0].cover.top, 1e-9)
        assertEquals(4970.0, pages[1].cover.top, 1e-9)
        assertTrue(pages[0].cover.bottom <= pages[1].cover.top)
    }

    @Test fun aBlockSlightlyTallerThanAPageIsShrunkOntoOne() {
        val pages = plan(listOf(Rect(100.0, 0.0, 200.0, 2000.0)))
        assertEquals(1, pages.size)
        assertTrue(pages[0].scale < base)
        assertEquals(2060.0, pages[0].cover.h, 1e-6) // block + padding both sides, uncut
    }

    @Test fun aMuchTallerBlockIsCutIntoSeveralFullScalePages() {
        val pages = plan(listOf(Rect(100.0, 0.0, 200.0, 10_000.0)))
        assertTrue(pages.size >= 5)
        for (p in pages) assertEquals(base, p.scale, 1e-12)
        // Pages advance down the canvas and between them nothing is skipped.
        for (i in 1 until pages.size) {
            assertTrue(pages[i].cover.top > pages[i - 1].cover.top)
            assertTrue(pages[i].cover.top <= pages[i - 1].cover.bottom)
        }
    }

    @Test fun contentWiderThanA4ShrinksThePageToTakeItIn() {
        val pages = plan(listOf(Rect(0.0, 0.0, 3000.0, 100.0)))
        assertEquals(1, pages.size)
        assertTrue(pages[0].scale < base)
        assertTrue(pages[0].cover.w >= 3000.0)
    }

    @Test fun aLimitedWidthFixesThePageWidthAtOneToOne() {
        val pages = plan(listOf(Rect(0.0, 0.0, 500.0, 5000.0)), limitW = 1000.0)
        assertTrue(pages.isNotEmpty())
        for (p in pages) {
            assertEquals(1000.0, p.cover.w, 1e-9)
            assertEquals(0.0, p.cover.x, 1e-9)
            assertEquals(base, p.scale, 1e-12)
            assertEquals(1000.0 * 297.0 / 210.0, p.cover.h, 1e-6)
        }
    }

    @Test fun aLimitedHeightStacksPagesAcrossInstead() {
        val pages = plan(
            listOf(Rect(100.0, 0.0, 200.0, 100.0), Rect(9000.0, 0.0, 200.0, 100.0)),
            limitH = 1000.0,
        )
        assertEquals(2, pages.size)
        for (p in pages) assertEquals(1000.0, p.cover.h, 1e-9)
        assertTrue(pages[1].cover.left > pages[0].cover.left)
    }

    @Test fun blocksMergeOverlappingAndTouchingSpans() {
        val merged = CanvasPagination.blocks(listOf(5.0 to 20.0, 0.0 to 10.0, 30.0 to 40.0, 40.0 to 45.0))
        assertEquals(listOf(0.0 to 20.0, 30.0 to 45.0), merged)
    }

    @Test fun blocksOfNothingIsNothing() = assertTrue(CanvasPagination.blocks(emptyList()).isEmpty())

    @Test fun cheapestCutFindsTheGapNearestTheEnd() {
        val spans = listOf(0.0 to 50.0, 70.0 to 100.0)
        assertEquals(70.0, CanvasPagination.cheapestCut(spans, 50.0, 70.0), 1e-9)
    }

    @Test fun cheapestCutPrefersTheLeastCrossedPosition() {
        // Two spans cover 60..90; one covers 40..60. The cheapest place is where only one crosses.
        val spans = listOf(0.0 to 60.0, 60.0 to 90.0, 55.0 to 95.0)
        val cut = CanvasPagination.cheapestCut(spans, 40.0, 100.0)
        assertTrue(cut >= 95.0 || cut <= 55.0)
        assertEquals(100.0, cut, 1e-9) // nothing crosses the very end, and ties go nearest it
    }
}
