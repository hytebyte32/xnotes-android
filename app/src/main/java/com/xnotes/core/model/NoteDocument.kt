package com.xnotes.core.model

import com.xnotes.core.infinite.CanvasBackground
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.infinite.Waypoint
import com.xnotes.core.text.TextFlow
import com.xnotes.core.util.Paths

/** One stretch of a [NoteDocument], top to bottom: a fixed-size page or an infinite (or bounded) region. */
sealed interface Section

/** A classic page. The [Page] is the same mutable, identity-compared object the paged editor uses. */
class PageSection(val page: Page) : Section

/**
 * A region: one flat, z-ordered list of items in its own content space, with the axis limits a canvas
 * has. A null limit is an endless axis; a limited axis spans 0..limit. [originX]/[originY] is the
 * region's top-left corner, which nothing scrolls past.
 */
class RegionSection(
    var limitW: Double? = null,
    var limitH: Double? = null,
    var originX: Double = 0.0,
    var originY: Double = 0.0,
    val items: MutableList<CanvasItem> = mutableListOf(),
) : Section


/**
 * The unified document: an ordered list of [sections] (pages and regions interleaved) plus every
 * note-wide setting the two older document types kept separately. A classic note is one with only
 * pages; a canvas is one with a single region; anything else is a mix.
 *
 * Note-wide, because the design makes them so: the PDF source, bookmarks, document-wide style and
 * margins, the flowing text, templates, the endless-background ruling, waypoints and the last view.
 *
 * This is the model only. The editors still run on [Document] and [InfiniteDocument] until they are
 * moved over; [fromDocument] and [fromCanvas] bring either into this shape, and [toDocument] and
 * [toCanvas] take a note back out when it is expressible as that legacy type.
 */
class NoteDocument(
    val sections: MutableList<Section> = mutableListOf(),
    var dpi: Int = PageSize.DEFAULT_DPI,
    var path: String? = null,
    /** Real file name from the storage provider, when known (overrides [path]-derived title). */
    var displayName: String? = null,
    var dirty: Boolean = false,
    /** When the note was created (epoch ms), or null for files written before this was recorded. */
    var created: Long? = null,
    /** Embedded source PDF, held as a file on disk, as on [Document.pdfFile]. */
    var pdfFile: java.io.File? = null,
    val bookmarks: MutableList<Bookmark> = mutableListOf(),
    var style: PageStyle = PageStyle(),
    var margins: PageMargins = PageMargins(),
    val flow: TextFlow = TextFlow(),
    var templates: Map<String, String> = emptyMap(),
    /** The ruling regions draw on; pages carry their own style. */
    var background: CanvasBackground = CanvasBackground(),
    val waypoints: MutableList<Waypoint> = mutableListOf(),
    /** The view the note was last left at, restored on open. */
    var lastView: Waypoint? = null,
) {

    val title: String
        get() = displayName?.let { Paths.stem(it) }
            ?: path?.let { Paths.stem(it) }
            ?: "Untitled"

    val hasPdf: Boolean get() = pdfFile != null

    /** Every page section's page, in order. */
    val pages: List<Page> get() = sections.filterIsInstance<PageSection>().map { it.page }

    /** Every region, in order. */
    val regions: List<RegionSection> get() = sections.filterIsInstance<RegionSection>()

    /** True for a classic note: at least one section, every one a page. */
    val isPaged: Boolean get() = sections.isNotEmpty() && sections.all { it is PageSection }

    /** True for a canvas: exactly one section, and it is a region. */
    val isCanvas: Boolean get() = sections.size == 1 && sections[0] is RegionSection

    /** This note as a legacy [Document], or null when it holds a region or no pages. Shares the pages. */
    fun toDocument(): Document? {
        if (!isPaged) return null
        val doc = Document(
            pages = pages.toMutableList(),
            dpi = dpi,
            path = path,
            displayName = displayName,
            dirty = dirty,
            pdfFile = pdfFile,
            bookmarks = bookmarks.toMutableList(),
            style = style,
            margins = margins,
            flow = flow,
            created = created,
            templates = templates,
        )
        return doc
    }

    /** This note as a legacy [InfiniteDocument], or null unless it is a single region. Shares the items. */
    fun toCanvas(): InfiniteDocument? {
        if (!isCanvas) return null
        val region = sections[0] as RegionSection
        val doc = InfiniteDocument(
            dpi = dpi,
            path = path,
            displayName = displayName,
            dirty = dirty,
            background = background,
            waypoints = waypoints.toMutableList(),
            lastView = lastView,
            created = created,
        )
        doc.limitW = region.limitW
        doc.limitH = region.limitH
        doc.originX = region.originX
        doc.originY = region.originY
        doc.addAll(region.items)
        return doc
    }

    companion object {

        /** A classic note brought into the unified model. The pages are shared, not copied. */
        fun fromDocument(doc: Document): NoteDocument = NoteDocument(
            sections = doc.pages.mapTo(ArrayList<Section>()) { PageSection(it) },
            dpi = doc.dpi,
            path = doc.path,
            displayName = doc.displayName,
            dirty = doc.dirty,
            created = doc.created,
            pdfFile = doc.pdfFile,
            bookmarks = doc.bookmarks.toMutableList(),
            style = doc.style,
            margins = doc.margins,
            flow = doc.flow,
            templates = doc.templates,
        )

        /** A canvas brought into the unified model, as a note with one region. The items are shared. */
        fun fromCanvas(doc: InfiniteDocument): NoteDocument {
            val region = RegionSection(doc.limitW, doc.limitH, doc.originX, doc.originY, doc.items.toMutableList())
            return NoteDocument(
                sections = mutableListOf(region),
                dpi = doc.dpi,
                path = doc.path,
                displayName = doc.displayName,
                dirty = doc.dirty,
                created = doc.created,
                background = doc.background,
                waypoints = doc.waypoints.toMutableList(),
                lastView = doc.lastView,
            )
        }
    }
}
