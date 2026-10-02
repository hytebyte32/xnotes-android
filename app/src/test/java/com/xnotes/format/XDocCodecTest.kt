package com.xnotes.format

import com.xnotes.core.FakeImageCodec
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.infinite.CanvasBackground
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.infinite.Waypoint
import com.xnotes.core.model.Bookmark
import com.xnotes.core.model.Document
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.LabelItem
import com.xnotes.core.model.NoteDocument
import com.xnotes.core.model.Page
import com.xnotes.core.model.PageMargins
import com.xnotes.core.model.PagePattern
import com.xnotes.core.model.PageSection
import com.xnotes.core.model.PageStyle
import com.xnotes.core.model.RegionSection
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.model.Stroke
import com.xnotes.core.model.TextItem
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.ShapeKind
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class XDocCodecTest {

    private val codec = XDocCodec(FakeImageCodec(), FakeTextMeasurer())
    private val docCodec = DocumentCodec(FakeImageCodec(), FakeTextMeasurer())
    private val canvasCodec = CanvasCodec(FakeImageCodec())

    private fun imageFile(bytes: ByteArray = byteArrayOf(1, 2, 3, 4)): File =
        File.createTempFile("img", null).apply { writeBytes(bytes); deleteOnExit() }

    private fun dir(): File = Files.createTempDirectory("xnotes-xdoc").toFile()

    private fun bytesOf(note: NoteDocument): ByteArray =
        ByteArrayOutputStream().also { codec.write(note, it) }.toByteArray()

    private fun read(bytes: ByteArray, pdfDir: File? = null): NoteDocument =
        codec.read(ByteArrayInputStream(bytes), pdfDir = pdfDir, imageDir = dir())

    private fun roundTrip(note: NoteDocument): NoteDocument = read(bytesOf(note))

    private fun stroke(vararg xy: Double): Stroke =
        Stroke(Tool.PEN, ToolConfig(), (xy.indices step 2).map { Sample(xy[it], xy[it + 1], 0.5) }.toMutableList())

    private fun bundleOf(manifest: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            zos.putNextEntry(ZipEntry("manifest.json").apply { method = ZipEntry.DEFLATED })
            zos.write(manifest.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        return out.toByteArray()
    }

    private fun entryNames(bytes: ByteArray): List<String> {
        val names = ArrayList<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var e = zis.nextEntry
            while (e != null) { names.add(e.name); zis.closeEntry(); e = zis.nextEntry }
        }
        return names
    }

    private fun mixed(): NoteDocument {
        val page1 = Page(800.0, 1200.0).apply { items.add(stroke(1.0, 2.0, 3.0, 4.0)) }
        val region = RegionSection(limitW = 800.0, limitH = null, originX = -10.0, originY = 5.0).apply {
            items.add(stroke(-5.0, -6.0, 7.0, 8.0))
            items.add(LabelItem(Pt(10.0, 20.0), "12.5 cm", 30.0))
        }
        val page2 = Page(600.0, 900.0, pdfPage = null)
        return NoteDocument(mutableListOf(PageSection(page1), region, PageSection(page2)), dpi = 150)
    }

    // --- structure ---

    @Test fun sectionsKeepTheirOrderKindAndGeometry() {
        val back = roundTrip(mixed())
        assertEquals(3, back.sections.size)
        val p1 = (back.sections[0] as PageSection).page
        assertEquals(800.0, p1.width, 0.0)
        assertEquals(1200.0, p1.height, 0.0)
        val r = back.sections[1] as RegionSection
        assertEquals(800.0, r.limitW!!, 0.0)
        assertNull(r.limitH)
        assertEquals(-10.0, r.originX, 0.0)
        assertEquals(5.0, r.originY, 0.0)
        assertEquals(600.0, (back.sections[2] as PageSection).page.width, 0.0)
        assertEquals(150, back.dpi)
    }

    @Test fun itemsLandInTheSectionTheyWereIn() {
        val back = roundTrip(mixed())
        assertEquals(1, (back.sections[0] as PageSection).page.items.size)
        val r = back.sections[1] as RegionSection
        assertEquals(2, r.items.size)
        assertTrue(r.items[0] is Stroke)
        val label = r.items[1] as LabelItem
        assertEquals("12.5 cm", label.text)
        assertEquals(30.0, label.height, 0.0)
        assertEquals(Pt(10.0, 20.0), label.pos)
        assertTrue((back.sections[2] as PageSection).page.items.isEmpty())
    }

    @Test fun strokeSamplesSurviveExactly() {
        val s = Stroke(
            Tool.CALLIGRAPHY,
            ToolConfig(6.0, true, 0.4, 0.6, Rgba(0, 230, 118, 255)),
            mutableListOf(Sample(10.0, 20.0, 0.5), Sample(-30.0, 40.0, 0.9)),
        )
        val note = NoteDocument(mutableListOf(RegionSection(items = mutableListOf(s))))
        val back = (roundTrip(note).sections[0] as RegionSection).items[0] as Stroke
        assertEquals(Tool.CALLIGRAPHY, back.tool)
        assertEquals(0.6, back.config.directionStrength, 1e-9)
        assertEquals(Sample(10.0, 20.0, 0.5), back.samples[0])
        assertEquals(-30.0, back.samples[1].x, 1e-9)
    }

    @Test fun shapesTextBoxesAndLockedFlagsRoundTrip() {
        val page = Page(500.0, 500.0)
        page.items.add(ShapeItem(ShapeKind.RECTANGLE, Pt(0.0, 0.0), Pt(50.0, 30.0), Rgba(255, 92, 92, 255), 3.0, Rgba(255, 92, 92, 64)).apply { locked = true })
        page.items.add(TextItem(Pt(100.0, 110.0), width = 250.0, text = "hello\nworld", rgba = Rgba(1, 2, 3, 255), pointSize = 13.0, measurer = FakeTextMeasurer()))
        val back = (roundTrip(NoteDocument(mutableListOf(PageSection(page)))).sections[0] as PageSection).page.items
        val shape = back[0] as ShapeItem
        assertEquals(ShapeKind.RECTANGLE, shape.shape)
        assertNotNull(shape.fillRgba)
        assertTrue(shape.locked)
        val text = back[1] as TextItem
        assertEquals("hello\nworld", text.text)
        assertEquals(250.0, text.width, 0.0)
    }

    @Test fun textBoxesAreSkippedWhenThereIsNoMeasurer() {
        val page = Page(500.0, 500.0)
        page.items.add(TextItem(Pt(1.0, 1.0), text = "hi", measurer = FakeTextMeasurer()))
        page.items.add(stroke(1.0, 1.0))
        val bytes = bytesOf(NoteDocument(mutableListOf(PageSection(page))))
        val back = XDocCodec(FakeImageCodec()).read(ByteArrayInputStream(bytes), imageDir = dir())
        assertEquals(1, (back.sections[0] as PageSection).page.items.size)
    }

    // --- images and assets ---

    @Test fun imagesAcrossSectionsGetTheirOwnBytesAndKeepTheirZSlot() {
        val page = Page(500.0, 500.0)
        page.items.add(ImageItem(ImageData(imageFile(byteArrayOf(9, 9)), 10, 10), Rect(0.0, 0.0, 10.0, 10.0)))
        page.items.add(stroke(1.0, 1.0))
        val region = RegionSection()
        region.items.add(stroke(2.0, 2.0))
        region.items.add(ImageItem(ImageData(imageFile(byteArrayOf(7, 7, 7)), 20, 20), Rect(5.0, 5.0, 20.0, 20.0), angle = 0.5))
        val note = NoteDocument(mutableListOf(PageSection(page), region))

        val bytes = bytesOf(note)
        assertEquals(listOf("assets/image-000.png", "assets/image-001.png", "manifest.json"), entryNames(bytes))
        val back = read(bytes)
        val pi = (back.sections[0] as PageSection).page.items
        assertTrue(pi[0] is ImageItem && pi[1] is Stroke)
        assertArrayEquals(byteArrayOf(9, 9), (pi[0] as ImageItem).image.file.readBytes())
        val ri = (back.sections[1] as RegionSection).items
        assertTrue(ri[0] is Stroke && ri[1] is ImageItem)
        assertArrayEquals(byteArrayOf(7, 7, 7), (ri[1] as ImageItem).image.file.readBytes())
        assertEquals(0.5, (ri[1] as ImageItem).angle, 0.0)
    }

    @Test fun anEmbeddedPdfStreamsOutAndIsFlaggedInTheManifest() {
        val pdf = File.createTempFile("src", ".pdf").apply { writeBytes(ByteArray(5000) { it.toByte() }); deleteOnExit() }
        val note = NoteDocument(mutableListOf(PageSection(Page(595.0, 842.0, pdfPage = 0))), pdfFile = pdf)
        val bytes = bytesOf(note)
        assertTrue("assets/source.pdf" in entryNames(bytes))
        val back = read(bytes, pdfDir = dir())
        assertArrayEquals(pdf.readBytes(), back.pdfFile!!.readBytes())
        assertEquals(0, (back.sections[0] as PageSection).page.pdfPage)
        // A validation-only read, with no directory to stream into, drops the PDF rather than failing.
        assertNull(read(bytes).pdfFile)
    }

    // --- note-wide settings ---

    @Test fun noteWideSettingsRoundTrip() {
        val note = mixed().apply {
            created = 1_789_720_071_123L
            bookmarks.add(Bookmark(1, "Intro"))
            style = PageStyle(pageColor = Rgba(250, 240, 230, 255), template = PagePattern.GRID.id)
            margins = PageMargins(left = 0.1, top = 0.2)
            background = CanvasBackground(PagePattern.DOTS, Rgba(10, 20, 30, 200), 42.0, Rgba(1, 2, 3, 255))
            lastView = Waypoint("", 1234.5, -678.25, 3.5)
            waypoints.add(Waypoint("origin", 0.0, 0.0, 1.0))
            waypoints.add(Waypoint("far", 90000.0, -80000.0, 0.05))
        }
        val back = roundTrip(note)
        assertEquals(1_789_720_071_123L, back.created)
        assertEquals(listOf(Bookmark(1, "Intro")), back.bookmarks)
        assertEquals(Rgba(250, 240, 230, 255), back.style.pageColor)
        assertEquals(PagePattern.GRID.id, back.style.template)
        assertEquals(0.1, back.margins.left!!, 1e-12)
        assertEquals(0.2, back.margins.top!!, 1e-12)
        assertEquals(PagePattern.DOTS, back.background.pattern)
        assertEquals(Rgba(1, 2, 3, 255), back.background.paperColor)
        assertEquals(42.0, back.background.spacing, 0.0)
        assertEquals(3.5, back.lastView!!.zoom, 0.0)
        assertEquals(listOf("origin", "far"), back.waypoints.map { it.name })
    }

    @Test fun anUnknownCreatedTimeStaysUnknown() {
        assertNull(roundTrip(mixed()).created)
    }

    @Test fun anEmbeddedTemplateTravelsWithAPageThatNamesIt() {
        val page = Page(500.0, 500.0, style = PageStyle(template = "custom-1"))
        val note = NoteDocument(mutableListOf(PageSection(page)), templates = mapOf("custom-1" to "template text", "unused" to "x"))
        val bytes = bytesOf(note)
        assertTrue("templates/custom-1.xtemplate" in entryNames(bytes))
        assertFalse("templates/unused.xtemplate" in entryNames(bytes))
        assertEquals(mapOf("custom-1" to "template text"), read(bytes).templates)
    }

    @Test fun anEmptyFlowWritesNoFlowEntry() {
        assertFalse(FlowXml.ENTRY_NAME in entryNames(bytesOf(mixed())))
    }

    // --- stability ---

    @Test fun resavingAnUntouchedNoteIsByteStable() {
        val first = bytesOf(mixed())
        assertArrayEquals(first, bytesOf(read(first)))
    }

    // --- forgiving loads ---

    @Test fun anUnknownSectionKindIsSkipped() {
        val back = read(bundleOf("""{"format":"xdoc","version":1,"sections":[{"kind":"hologram"},{"kind":"page","width":100,"height":200,"items":[]},{"width":1}]}"""))
        assertEquals(1, back.sections.size)
        assertEquals(200.0, (back.sections[0] as PageSection).page.height, 0.0)
    }

    @Test fun aNoteWithNoSectionsGetsABlankPage() {
        val back = read(bundleOf("""{"format":"xdoc","version":1,"sections":[]}"""))
        assertEquals(1, back.sections.size)
        assertTrue(back.sections[0] is PageSection)
    }

    @Test fun aPageWithNoAreaTakesItsNeighboursSize() {
        val back = read(bundleOf("""{"format":"xdoc","sections":[{"kind":"page","width":100,"height":200},{"kind":"page","width":0,"height":0}]}"""))
        val p = (back.sections[1] as PageSection).page
        assertEquals(100.0, p.width, 0.0)
        assertEquals(200.0, p.height, 0.0)
    }

    @Test fun aNonPositiveRegionLimitMeansEndless() {
        val back = read(bundleOf("""{"format":"xdoc","sections":[{"kind":"region","limit_w":-5,"limit_h":0}]}"""))
        val r = back.sections[0] as RegionSection
        assertNull(r.limitW)
        assertNull(r.limitH)
    }

    @Test fun otherFormatsAndGarbageAreRejected() {
        assertThrows(XDocFormatException::class.java) { read(bundleOf("""{"format":"xnote","pages":[]}""")) }
        assertThrows(XDocFormatException::class.java) { read(bundleOf("""{"sections":[]}""")) }
        assertThrows(XDocFormatException::class.java) { read(byteArrayOf(1, 2, 3, 4, 5)) }
        assertThrows(XDocFormatException::class.java) { read(bundleOf("not json")) }
    }

    // --- legacy import and export ---

    private fun legacyDocument(): Document {
        val doc = Document(dpi = 150)
        val page = Page(1240.0, 1754.0)
        page.items.add(stroke(1.0, 2.0, 3.0, 5.0, 8.0, 13.0))
        page.items.add(ImageItem(ImageData(imageFile(), 64, 48), Rect(5.0, 6.0, 64.0, 48.0)))
        page.items.add(TextItem(Pt(100.0, 110.0), width = 250.0, text = "hello", pointSize = 13.0, measurer = FakeTextMeasurer()))
        page.items.add(ShapeItem(ShapeKind.RECTANGLE, Pt(0.0, 0.0), Pt(50.0, 30.0), Rgba(255, 92, 92, 255), 3.0, null))
        doc.pages.add(page)
        doc.pages.add(Page(1240.0, 1754.0, style = PageStyle(pageColor = Rgba(1, 2, 3, 255))))
        doc.bookmarks.add(Bookmark(1, "Second"))
        doc.created = 1_789_720_071_123L
        doc.margins = PageMargins(left = 0.05)
        return doc
    }

    private fun docBytes(doc: Document): ByteArray = ByteArrayOutputStream().also { docCodec.write(doc, it) }.toByteArray()

    @Test fun anXnoteSurvivesAnXdocTripWithItsExportByteIdentical() {
        val legacy = docBytes(legacyDocument())
        val imported = NoteDocument.fromDocument(docCodec.read(ByteArrayInputStream(legacy), imageDir = dir()))
        assertTrue(imported.isPaged)
        val through = read(bytesOf(imported))
        assertTrue(through.isPaged)
        assertFalse(through.isCanvas)
        assertArrayEquals(legacy, docBytes(through.toDocument()!!))
    }

    private fun legacyCanvas(): InfiniteDocument {
        val doc = InfiniteDocument(dpi = 150)
        doc.limitW = 900.0
        doc.originX = -40.0
        doc.originY = -20.0
        doc.add(stroke(-5.0, -6.0, 7.0, 8.0))
        doc.add(ImageItem(ImageData(imageFile(), 64, 48), Rect(5.0, 6.0, 64.0, 48.0)))
        doc.add(LabelItem(Pt(3.0, 4.0), "90.0°", 24.0))
        doc.background = CanvasBackground(PagePattern.DOTS, Rgba(10, 20, 30, 200), 42.0, null)
        doc.lastView = Waypoint("", 10.0, -20.0, 2.0)
        doc.waypoints.add(Waypoint("home", 0.0, 0.0, 1.0))
        doc.created = 1_789_720_071_123L
        return doc
    }

    private fun canvasBytes(doc: InfiniteDocument): ByteArray = ByteArrayOutputStream().also { canvasCodec.write(doc, it) }.toByteArray()

    @Test fun anXcanvasSurvivesAnXdocTripWithItsExportByteIdentical() {
        val legacy = canvasBytes(legacyCanvas())
        val imported = NoteDocument.fromCanvas(canvasCodec.read(ByteArrayInputStream(legacy), imageDir = dir()))
        assertTrue(imported.isCanvas)
        val through = read(bytesOf(imported))
        assertTrue(through.isCanvas)
        assertArrayEquals(legacy, canvasBytes(through.toCanvas()!!))
    }

    @Test fun aCanvasBecomesOneRegionCarryingItsLimitsAndOrigin() {
        val note = NoteDocument.fromCanvas(legacyCanvas())
        val region = note.sections.single() as RegionSection
        assertEquals(900.0, region.limitW!!, 0.0)
        assertNull(region.limitH)
        assertEquals(-40.0, region.originX, 0.0)
        assertEquals(3, region.items.size)
        assertEquals(listOf("home"), note.waypoints.map { it.name })
    }

    @Test fun aNoteIsOnlyExpressibleAsTheLegacyTypeItActuallyIs() {
        val m = mixed()
        assertNull(m.toDocument())
        assertNull(m.toCanvas())
        assertFalse(m.isPaged)
        assertFalse(m.isCanvas)

        val paged = NoteDocument.fromDocument(legacyDocument())
        assertNotNull(paged.toDocument())
        assertNull(paged.toCanvas())

        val canvas = NoteDocument.fromCanvas(legacyCanvas())
        assertNotNull(canvas.toCanvas())
        assertNull(canvas.toDocument())

        assertNull(NoteDocument().toDocument())
        assertNull(NoteDocument().toCanvas())
    }

    @Test fun twoRegionsOrARegionBesideAPageAreNotACanvas() {
        val two = NoteDocument(mutableListOf(RegionSection(), RegionSection()))
        assertFalse(two.isCanvas)
        assertNull(two.toCanvas())
    }

    @Test fun aMixedNoteIsReportedByItsPagesAndRegions() {
        val m = mixed()
        assertEquals(2, m.pages.size)
        assertEquals(1, m.regions.size)
    }
}
