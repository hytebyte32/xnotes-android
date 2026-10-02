package com.xnotes.gl

import com.xnotes.core.geometry.Pt
import com.xnotes.core.infinite.ItemMesher
import com.xnotes.core.infinite.MeshedItem
import com.xnotes.core.infinite.translated
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.Page
import com.xnotes.core.model.TextItem
import java.util.IdentityHashMap

/**
 * Keeps the GL ink in step with a paged note. Each page's items are meshed in the page's own
 * space and filed displaced to where the page sits in the content plane, so the scene is one flat
 * world exactly like the infinite canvas's.
 *
 * The paged model has no per-item change events; editing code calls `invalidatePage`. So the unit of
 * refresh here is the page: [refile] drops items that left it, re-meshes everything still on it and
 * republishes the global z order (pages top to bottom, each page's items in list order). A page
 * with a great many strokes pays for all of them on each edit; a per-item version stamp would cut
 * that, and is the obvious next step once this is measured.
 *
 * Vector items (strokes, shapes, labels) are meshed. Images are filed as textured quads from their
 * own space, and text boxes as textures the host renders, both displaced to the page.
 *
 * Main thread only.
 */
class PagedInkSync(
    private val sink: InkSink,
    /** Items to leave out for now, e.g. lifted by a drag or still owned by the front-buffer pad. */
    private val skip: (CanvasItem) -> Boolean = { false },
    private val mesh: (CanvasItem) -> MeshedItem? = { ItemMesher.mesh(it) },
) {

    private val filed = IdentityHashMap<Page, List<CanvasItem>>()
    private var pageOrder: List<Page> = emptyList()

    /** Start over with [pages] at [origins] (each page's page-space origin in content space). */
    fun rebuild(pages: List<Page>, origins: List<Pt>) {
        require(pages.size == origins.size) { "one origin per page" }
        sink.reset()
        filed.clear()
        pageOrder = ArrayList(pages)
        sink.batch {
            for (i in pages.indices) fileAll(pages[i], origins[i])
        }
        publishOrder()
    }

    /** Re-file one page after it changed, or after it moved to a new [origin]. */
    fun refile(page: Page, origin: Pt) {
        val old = filed[page].orEmpty()
        val now = page.items
        if (old.isNotEmpty()) {
            val keep = IdentityHashMap<CanvasItem, Boolean>()
            for (it in now) keep[it] = true
            for (it in old) if (!keep.containsKey(it)) sink.remove(it)
        }
        sink.batch { fileAll(page, origin) }
        publishOrder()
    }

    /**
     * One item joined the end of [page] (an ordinary committed stroke): file just it, rather than
     * re-meshing the page. Falls back to [refile] when the page was never filed.
     */
    fun appendItem(page: Page, item: CanvasItem, origin: Pt) {
        val old = filed[page]
        if (old == null) return refile(page, origin)
        if (!fileOne(item, origin)) return
        if (old.none { it === item }) filed[page] = old + item
        publishOrder()
    }

    /** A page was removed from the note: take all its ink out. */
    fun drop(page: Page) {
        val old = filed.remove(page) ?: return
        for (item in old) sink.remove(item)
        pageOrder = pageOrder.filter { it !== page }
        publishOrder()
    }

    /** The pages' order changed without their content: only the z order needs republishing. */
    fun reorder(pages: List<Page>) {
        pageOrder = ArrayList(pages)
        publishOrder()
    }

    private fun fileAll(page: Page, origin: Pt) {
        val items = ArrayList<CanvasItem>(page.items.size)
        for (item in page.items) if (fileOne(item, origin)) items.add(item)
        filed[page] = items
        if (page !in pageOrder) pageOrder = pageOrder + page
    }

    /** File one item; false when it draws nothing (or is being left out) and so is not in the order. */
    private fun fileOne(item: CanvasItem, origin: Pt): Boolean {
        if (skip(item)) {
            sink.remove(item)
            return false
        }
        when (item) {
            is ImageItem -> {
                sink.upsertImage(item, item.paintBounds().translate(origin.x, origin.y), origin.x, origin.y)
                return true
            }
            is TextItem -> {
                if (item.text.isEmpty()) {
                    sink.remove(item)
                    return false
                }
                sink.upsertText(item, item.bounds().translate(origin.x, origin.y), textKey(item))
                return true
            }
            else -> {
                val meshed = mesh(item)
                if (meshed == null || meshed.isEmpty) {
                    sink.remove(item)
                    return false
                }
                val moved = meshed.translated(origin.x, origin.y)
                sink.upsert(item, moved.parts, moved.bounds)
                return true
            }
        }
    }

    private fun publishOrder() {
        val all = ArrayList<CanvasItem>()
        for (p in pageOrder) filed[p]?.let { all.addAll(it) }
        sink.setOrder(all)
    }

    companion object {
        /** Stamp of everything that changes how a text box looks; a texture is current when it matches. */
        fun textKey(t: TextItem): Long {
            val h = java.util.Objects.hash(t.text, t.font, t.pointSize, t.rgba, t.width, t.height)
            return h.toLong() and 0x7FFFFFFFL
        }
    }
}
