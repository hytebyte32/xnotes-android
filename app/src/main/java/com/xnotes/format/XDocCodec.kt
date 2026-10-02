package com.xnotes.format

import com.xnotes.core.geometry.Rect
import com.xnotes.core.infinite.CanvasBackground
import com.xnotes.core.infinite.Waypoint
import com.xnotes.core.model.Bookmark
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.NoteDocument
import com.xnotes.core.model.Orientation
import com.xnotes.core.model.Page
import com.xnotes.core.model.PageMargins
import com.xnotes.core.model.PagePattern
import com.xnotes.core.model.PageSection
import com.xnotes.core.model.PageSize
import com.xnotes.core.model.PageStyle
import com.xnotes.core.model.RegionSection
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.Section
import com.xnotes.core.pal.ImageCodec
import com.xnotes.core.pal.TextMeasurer
import com.xnotes.core.util.Svg
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Thrown when a file is not a valid `.xdoc` bundle. */
class XDocFormatException(message: String) : Exception(message)

/**
 * Reads and writes the unified `.xdoc` bundle: a ZIP with a deflated `manifest.json` plus stored
 * binary assets (`assets/image-NNN.ext`, `assets/source.pdf`), `flow.xml` and `templates/`, laid out
 * like `.xnote` (assets first and the manifest last, so a later save can replace the manifest in
 * place). The manifest holds the note-wide settings and an ordered `sections` array whose entries are
 * either `"kind":"page"` (a page: size, optional PDF page, items, style, margins) or
 * `"kind":"region"` (axis limits, origin, items).
 *
 * Items use the encoding `.xnote` and `.xcanvas` already share, via [XDocItems]. Loading is
 * forgiving in the same way: unknown section or item kinds are skipped and missing fields take model
 * defaults, and optional fields are written only when set.
 *
 * The older formats are not touched: [DocumentCodec] and [CanvasCodec] stay as the importers and
 * exporters, and [NoteDocument.fromDocument] / [NoteDocument.fromCanvas] carry what they read into
 * this model.
 */
class XDocCodec(imageCodec: ImageCodec, textMeasurer: TextMeasurer? = null) {

    private val items = XDocItems(imageCodec, textMeasurer)

    /** Thrown out of [write] when [isCancelled] turns true mid-copy, so the caller can discard the partial file. */
    class WriteCancelled : Exception()

    fun write(note: NoteDocument, out: OutputStream, isCancelled: () -> Boolean = { false }) {
        val assets = imageAssets(note)
        ZipOutputStream(out).use { zos ->
            // ALWAYS LEVEL 1, for the reasons given at the same line in [DocumentCodec.write].
            zos.setLevel(java.util.zip.Deflater.BEST_SPEED)
            for ((name, file) in assets) zos.putStored(name, file, isCancelled)
            note.pdfFile?.let { zos.putStored("assets/source.pdf", it, isCancelled) }
            if (!note.flow.isEmpty || !com.xnotes.core.text.FlowDefaults.of(note.flow).isEmpty) {
                zos.putDeflated(FlowXml.ENTRY_NAME, FlowXml.write(note.flow))
            }
            for ((key, text) in usedTemplates(note)) {
                zos.putDeflated("$TEMPLATE_DIR$key$TEMPLATE_EXT", text.toByteArray(Charsets.UTF_8))
            }
            zos.putNextEntry(ZipEntry("manifest.json").apply { method = ZipEntry.DEFLATED })
            val w = java.io.BufferedWriter(java.io.OutputStreamWriter(zos, Charsets.UTF_8), 32 * 1024)
            writeManifest(JsonWrite(w), note, assets)
            w.flush()
            zos.closeEntry()
        }
    }

    /** The embedded templates [note]'s styles name, in key order; built-in rulings need none. */
    internal fun usedTemplates(note: NoteDocument): List<Pair<String, String>> {
        val keys = sortedSetOf<String>()
        note.style.template?.let { keys.add(it) }
        for (page in note.pages) page.style.template?.let { keys.add(it) }
        return keys.mapNotNull { k -> note.templates[k]?.let { k to it } }
    }

    /** The image entries a note needs, named in the order the manifest mentions them. */
    internal fun imageAssets(note: NoteDocument): List<Pair<String, File>> {
        val out = ArrayList<Pair<String, File>>()
        for (section in note.sections) {
            val list = when (section) {
                is PageSection -> section.page.items
                is RegionSection -> section.items
            }
            for (item in list) {
                if (item !is ImageItem) continue
                val ext = if (Svg.isSvgFile(item.image.file)) "svg" else "png"
                out.add("assets/image-%03d.%s".format(out.size, ext) to item.image.file)
            }
        }
        return out
    }

    // --- model -> streaming json ---

    private fun writeManifest(j: JsonWrite, note: NoteDocument, assets: List<Pair<String, File>>) {
        j.beginObject()
        j.name("format").value(FORMAT)
        j.name("version").value(VERSION)
        j.name("writer").value(DocumentCodec.WRITER)
        note.created?.let { j.name("created").value(java.time.Instant.ofEpochMilli(it).toString()) }
        j.name("dpi").value(note.dpi)
        j.name("has_pdf").value(note.pdfFile != null)
        j.name("bookmarks").beginArray()
        for (b in note.bookmarks) {
            j.beginObject()
            j.name("page").value(b.page)
            j.name("label").value(b.label)
            j.endObject()
        }
        j.endArray()
        writeBackground(j, note.background)
        note.lastView?.let {
            j.name("view")
            writeView(j, it)
        }
        if (note.waypoints.isNotEmpty()) {
            j.name("waypoints").beginArray()
            for (wp in note.waypoints) writeWaypoint(j, wp)
            j.endArray()
        }
        j.name("sections").beginArray()
        val nextAsset = intArrayOf(0)
        for (section in note.sections) {
            when (section) {
                is PageSection -> writePage(j, section.page, assets, nextAsset)
                is RegionSection -> writeRegion(j, section, assets, nextAsset)
            }
        }
        j.endArray()
        writeStyle(j, note.style)
        writeMargins(j, note.margins)
        j.endObject()
    }

    private fun writePage(j: JsonWrite, page: Page, assets: List<Pair<String, File>>, nextAsset: IntArray) {
        j.beginObject()
        j.name("kind").value(KIND_PAGE)
        j.name("width").value(page.width)
        j.name("height").value(page.height)
        j.name("pdf_page")
        page.pdfPage?.let { j.value(it) } ?: j.nullValue()
        j.name("items").beginArray()
        for (item in page.items) items.writeItem(j, item, assets, nextAsset)
        j.endArray()
        writeStyle(j, page.style)
        writeMargins(j, page.margins)
        j.endObject()
    }

    private fun writeRegion(j: JsonWrite, region: RegionSection, assets: List<Pair<String, File>>, nextAsset: IntArray) {
        j.beginObject()
        j.name("kind").value(KIND_REGION)
        // Written only for a limited axis, so an endless region's bytes carry no limit.
        region.limitW?.let { j.name("limit_w").value(it) }
        region.limitH?.let { j.name("limit_h").value(it) }
        j.name("origin_x").value(region.originX)
        j.name("origin_y").value(region.originY)
        j.name("items").beginArray()
        for (item in region.items) items.writeItem(j, item, assets, nextAsset)
        j.endArray()
        j.endObject()
    }

    private fun writeBackground(j: JsonWrite, bg: CanvasBackground) {
        j.name("background").beginObject()
        j.name("pattern").value(bg.pattern.id)
        j.name("pattern_color")
        writeRgba(j, bg.patternColor)
        j.name("spacing").value(bg.spacing)
        // Additive: written only when the canvas overrides the theme paper.
        bg.paperColor?.let {
            j.name("paper_color")
            writeRgba(j, it)
        }
        j.endObject()
    }

    private fun writeView(j: JsonWrite, v: Waypoint) {
        j.beginObject()
        j.name("cx").value(v.cx)
        j.name("cy").value(v.cy)
        j.name("zoom").value(v.zoom)
        j.endObject()
    }

    private fun writeWaypoint(j: JsonWrite, w: Waypoint) {
        j.beginObject()
        j.name("name").value(w.name)
        j.name("cx").value(w.cx)
        j.name("cy").value(w.cy)
        j.name("zoom").value(w.zoom)
        j.endObject()
    }

    // --- bundle -> model ---

    /**
     * Read a `.xdoc` from [input]. When [pdfDir] is non-null an embedded source PDF, and when
     * [imageDir] is non-null the inserted images, are streamed out to fresh temp files in those dirs
     * (so neither is ever held in RAM) and the caller owns those files' lifetime. A null dir skips
     * that asset, which is what validation-only reads want.
     */
    fun read(input: InputStream, pdfDir: File? = null, imageDir: File? = null): NoteDocument {
        var manifest: ParsedManifest? = null
        var flowBytes: ByteArray? = null
        val templates = HashMap<String, String>()
        val imageFiles = HashMap<String, File>()
        var pdfFile: File? = null
        ZipInputStream(input).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val name = entry.name
                    if (name == "manifest.json") {
                        if (manifest == null) {
                            manifest = try {
                                parseManifest(JsonPull(InputStreamReader(zis, Charsets.UTF_8)))
                            } catch (_: JsonPullException) {
                                throw XDocFormatException(NOT_XDOC)
                            }
                        }
                    } else if (name == "assets/source.pdf") {
                        if (pdfDir != null) {
                            val f = File.createTempFile("src", ".pdf", pdfDir)
                            FileOutputStream(f).use { zis.copyTo(it) }
                            pdfFile = f
                        }
                    } else if (name.startsWith("assets/image-")) {
                        if (imageDir != null) {
                            val f = File.createTempFile("img", null, imageDir)
                            FileOutputStream(f).use { zis.copyTo(it) }
                            imageFiles[name] = f
                        }
                    } else if (name == FlowXml.ENTRY_NAME) {
                        flowBytes = zis.readBytes()
                    } else if (name.startsWith(TEMPLATE_DIR) && name.endsWith(TEMPLATE_EXT)) {
                        val key = name.substring(TEMPLATE_DIR.length, name.length - TEMPLATE_EXT.length)
                        val bytes = readAtMost(zis, TemplateReader.MAX_BYTES)
                        if (DocumentCodec.TEMPLATE_KEY.matches(key) && bytes != null) templates[key] = String(bytes, Charsets.UTF_8)
                    }
                    // Anything else is an asset from a newer version: skipped, never buffered.
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        val m = manifest ?: throw XDocFormatException(NOT_XDOC)
        if (!m.formatOk) throw XDocFormatException(NOT_XDOC)

        val note = NoteDocument(dpi = m.dpi)
        note.created = m.created
        note.templates = templates
        note.style = m.style
        note.margins = m.margins
        note.background = m.background
        note.lastView = m.view
        note.waypoints.addAll(m.waypoints)
        note.bookmarks.addAll(m.bookmarks)
        if (m.hasPdf) note.pdfFile = pdfFile else pdfFile?.delete() // a stray PDF with no manifest flag: don't leak it
        flowBytes?.let { FlowXml.readInto(note.flow, it) }

        if (m.sections.isEmpty()) {
            note.sections.add(PageSection(Page.blank(PageSize.A4, Orientation.PORTRAIT, m.dpi)))
            return note
        }
        note.sections.addAll(m.sections)

        // Image entries stream out of the zip after the manifest, so image items materialize only now
        // that their files exist; the recorded index restores each one's z-order slot.
        for ((list, specs) in m.sectionImages) {
            var dropped = 0
            for (spec in specs) {
                val item = items.materializeImage(spec, imageFiles)
                if (item == null) dropped++ else list.add(spec.index - dropped, item)
            }
        }
        return note
    }

    private class ParsedManifest {
        var formatOk = false
        var created: Long? = null
        var dpi = PageSize.DEFAULT_DPI
        var hasPdf = false
        var style = PageStyle()
        var margins = PageMargins()
        var background = CanvasBackground()
        var view: Waypoint? = null
        val waypoints = ArrayList<Waypoint>()
        val bookmarks = ArrayList<Bookmark>()
        val sections = ArrayList<Section>()
        val sectionImages = ArrayList<Pair<MutableList<CanvasItem>, List<XDocItems.PendingImage>>>()
    }

    private fun parseManifest(p: JsonPull): ParsedManifest {
        val m = ParsedManifest()
        p.beginObject()
        while (p.hasNext()) {
            when (p.nextName()) {
                "format" -> {
                    if (stringOr(p, "") != FORMAT) throw XDocFormatException(NOT_XDOC)
                    m.formatOk = true
                }
                "created" -> m.created = stringOrNull(p)?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
                "dpi" -> m.dpi = intOr(p, PageSize.DEFAULT_DPI)
                "has_pdf" -> m.hasPdf = boolOr(p, false)
                "bookmarks" -> parseBookmarks(p, m.bookmarks)
                "background" -> m.background = parseBackground(p)
                "view" -> m.view = parseWaypoint(p, named = false)
                "waypoints" -> parseWaypoints(p, m.waypoints)
                "sections" -> parseSections(p, m)
                "style" -> m.style = parseStyle(p)
                "margins" -> m.margins = parseMargins(p)
                else -> p.skipValue()
            }
        }
        p.endObject()
        return m
    }

    private fun parseBookmarks(p: JsonPull, out: MutableList<Bookmark>) {
        if (p.peek() != JsonPull.Token.BEGIN_ARRAY) return p.skipValue()
        p.beginArray()
        while (p.hasNext()) {
            if (p.peek() != JsonPull.Token.BEGIN_OBJECT) {
                p.skipValue()
                continue
            }
            var page = 0
            var label = ""
            p.beginObject()
            while (p.hasNext()) {
                when (p.nextName()) {
                    "page" -> page = intOr(p, 0)
                    "label" -> label = stringOr(p, "")
                    else -> p.skipValue()
                }
            }
            p.endObject()
            out.add(Bookmark(page, label))
        }
        p.endArray()
    }

    private fun parseBackground(p: JsonPull): CanvasBackground {
        if (p.peek() != JsonPull.Token.BEGIN_OBJECT) {
            p.skipValue()
            return CanvasBackground()
        }
        val def = CanvasBackground()
        var pattern = def.pattern
        var patternColor = def.patternColor
        var spacing = def.spacing
        var paperColor: Rgba? = null
        p.beginObject()
        while (p.hasNext()) {
            when (p.nextName()) {
                "pattern" -> pattern = PagePattern.fromId(stringOrNull(p)) ?: def.pattern
                "pattern_color" -> patternColor = rgbaOrNull(p) ?: def.patternColor
                "spacing" -> spacing = doubleOr(p, def.spacing)
                "paper_color" -> paperColor = rgbaOrNull(p)
                else -> p.skipValue()
            }
        }
        p.endObject()
        return CanvasBackground(pattern, patternColor, spacing, paperColor)
    }

    private fun parseWaypoints(p: JsonPull, out: MutableList<Waypoint>) {
        if (p.peek() != JsonPull.Token.BEGIN_ARRAY) return p.skipValue()
        p.beginArray()
        while (p.hasNext()) parseWaypoint(p, named = true)?.let { out.add(it) }
        p.endArray()
    }

    /** One saved view. A malformed or non-object entry is skipped rather than failing the load. */
    private fun parseWaypoint(p: JsonPull, named: Boolean): Waypoint? {
        if (p.peek() != JsonPull.Token.BEGIN_OBJECT) {
            p.skipValue()
            return null
        }
        var name = ""
        var cx = 0.0
        var cy = 0.0
        var zoom = 1.0
        p.beginObject()
        while (p.hasNext()) {
            when (p.nextName()) {
                "name" -> name = stringOr(p, "")
                "cx" -> cx = doubleOr(p, 0.0)
                "cy" -> cy = doubleOr(p, 0.0)
                "zoom" -> zoom = doubleOr(p, 1.0)
                else -> p.skipValue()
            }
        }
        p.endObject()
        if (!cx.isFinite() || !cy.isFinite() || !zoom.isFinite() || zoom <= 0.0) return null
        return Waypoint(if (named) Waypoint.sanitizeName(name) else "", cx, cy, zoom)
    }


    private fun parseSections(p: JsonPull, m: ParsedManifest) {
        if (p.peek() != JsonPull.Token.BEGIN_ARRAY) return p.skipValue()
        val (fallbackW, fallbackH) = PageSize.A4.pixels(Orientation.PORTRAIT, m.dpi)
        p.beginArray()
        while (p.hasNext()) {
            if (p.peek() != JsonPull.Token.BEGIN_OBJECT) {
                p.skipValue()
                continue
            }
            var kind = ""
            var width = fallbackW
            var height = fallbackH
            var pdfPage: Int? = null
            var style = PageStyle()
            var margins = PageMargins()
            var limitW: Double? = null
            var limitH: Double? = null
            var originX = 0.0
            var originY = 0.0
            val list = mutableListOf<CanvasItem>()
            val pending = ArrayList<XDocItems.PendingImage>()
            p.beginObject()
            while (p.hasNext()) {
                when (p.nextName()) {
                    "kind" -> kind = stringOr(p, "")
                    "width" -> width = doubleOr(p, fallbackW)
                    "height" -> height = doubleOr(p, fallbackH)
                    "pdf_page" -> pdfPage = intOrNull(p)
                    "style" -> style = parseStyle(p)
                    "margins" -> margins = parseMargins(p)
                    "limit_w" -> limitW = doubleOrNull(p)?.takeIf { it.isFinite() && it > 0.0 }
                    "limit_h" -> limitH = doubleOrNull(p)?.takeIf { it.isFinite() && it > 0.0 }
                    "origin_x" -> originX = doubleOr(p, 0.0)
                    "origin_y" -> originY = doubleOr(p, 0.0)
                    "items" -> items.parseItems(p, list, pending)
                    else -> p.skipValue()
                }
            }
            p.endObject()
            when (kind) {
                KIND_PAGE -> {
                    if (!(width > 0.0 && width.isFinite() && height > 0.0 && height.isFinite())) {
                        // a page with no area takes its neighbour's size
                        val prev = m.sections.lastOrNull { it is PageSection } as PageSection?
                        width = prev?.page?.width ?: fallbackW
                        height = prev?.page?.height ?: fallbackH
                        pdfPage = null
                    }
                    m.sections.add(PageSection(Page(width = width, height = height, items = list, pdfPage = pdfPage, style = style, margins = margins)))
                    if (pending.isNotEmpty()) m.sectionImages.add(list to pending)
                }
                KIND_REGION -> {
                    m.sections.add(RegionSection(limitW, limitH, originX, originY, list))
                    if (pending.isNotEmpty()) m.sectionImages.add(list to pending)
                }
                else -> {} // a section kind from a newer version: skipped (forgiving)
            }
        }
        p.endArray()
    }

    // --- zip helpers ---

    private fun readAtMost(input: InputStream, limit: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) return out.toByteArray()
            if (out.size() + n > limit) return null
            out.write(buf, 0, n)
        }
    }

    private fun ZipOutputStream.putDeflated(name: String, data: ByteArray) {
        val entry = ZipEntry(name).apply { method = ZipEntry.DEFLATED }
        putNextEntry(entry)
        write(data)
        closeEntry()
    }

    /** Stream [file] into a STORED zip entry without holding it in memory; the CRC comes from [AssetCrc]. */
    private fun ZipOutputStream.putStored(name: String, file: File, isCancelled: () -> Boolean) {
        val buf = ByteArray(64 * 1024)
        val size = file.length()
        val entry = ZipEntry(name).apply {
            method = ZipEntry.STORED
            this.size = size
            compressedSize = size
            this.crc = AssetCrc.of(file)
        }
        putNextEntry(entry)
        FileInputStream(file).use { input ->
            while (true) {
                if (isCancelled()) throw WriteCancelled()
                val n = input.read(buf)
                if (n < 0) break
                write(buf, 0, n)
            }
        }
        closeEntry()
    }

    companion object {
        const val FORMAT = "xdoc"
        const val VERSION = 1

        private const val KIND_PAGE = "page"
        private const val KIND_REGION = "region"

        private const val NOT_XDOC = "Not an xnotes document"
        private const val TEMPLATE_DIR = "templates/"
        private const val TEMPLATE_EXT = ".xtemplate"
    }
}
