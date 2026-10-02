package com.xnotes.gl

import com.xnotes.core.geometry.Pt
import com.xnotes.core.infinite.ItemMesher
import com.xnotes.core.infinite.MeshedItem
import com.xnotes.core.infinite.translated
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.Page
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
 * Only vector items go through here: strokes, shapes and labels. Images and text boxes are wired in
 * later steps, so [mesh] returns null for them and they are simply not filed.
 *
 * Main thread only.
 */
class PagedInkSync(
    private val sink: InkSink,
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
        val meshed = mesh(item)
        if (meshed == null || meshed.isEmpty) return
        val moved = meshed.translated(origin.x, origin.y)
        sink.upsert(item, moved.parts, moved.bounds)
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
        for (item in page.items) {
            val meshed = mesh(item)
            if (meshed == null || meshed.isEmpty) {
                sink.remove(item)
                continue
            }
            val moved = meshed.translated(origin.x, origin.y)
            sink.upsert(item, moved.parts, moved.bounds)
            items.add(item)
        }
        filed[page] = items
        if (page !in pageOrder) pageOrder = pageOrder + page
    }

    private fun publishOrder() {
        val all = ArrayList<CanvasItem>()
        for (p in pageOrder) filed[p]?.let { all.addAll(it) }
        sink.setOrder(all)
    }
}
