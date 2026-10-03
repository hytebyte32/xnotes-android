package com.xnotes.gl

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.infinite.ItemMesher
import com.xnotes.core.infinite.MeshedItem
import com.xnotes.core.infinite.rotatedQuarter
import com.xnotes.core.infinite.transformed
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.Page
import com.xnotes.core.model.TextItem
import java.util.IdentityHashMap

/**
 * Where a page's own space sits in the content plane: turned by [rot] quarter turns (the view's
 * rotation, clockwise), then shifted by ([ox], [oy]).
 */
class PageXform(val rot: Int, val ox: Double, val oy: Double) {
    constructor(origin: Pt) : this(0, origin.x, origin.y)

    fun same(other: PageXform): Boolean =
        rot == other.rot && Math.abs(ox - other.ox) <= 1e-6 && Math.abs(oy - other.oy) <= 1e-6
}

/**
 * Keeps the GL ink in step with a paged note. Each page's items are meshed in the page's own
 * space and filed turned and displaced to where the page sits in the content plane, so the scene is
 * one flat world exactly like the infinite canvas's.
 *
 * The paged model has no per-item change events; editing code calls `invalidatePage` or
 * `repairRegion`. The unit of refresh is the page, narrowed by the dirty region when one is given:
 * [refile] drops items that left the page, re-meshes the new or touched ones, and republishes the
 * global z order (pages top to bottom, each page's items in list order).
 *
 * Vector items (strokes, shapes, labels) are meshed. Images are filed as textured quads from their
 * own space, and text boxes as textures the host renders, both placed on the page.
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

    /** Start over with [pages] placed by [xforms]. */
    fun rebuild(pages: List<Page>, xforms: List<PageXform>) {
        require(pages.size == xforms.size) { "one placement per page" }
        sink.reset()
        filed.clear()
        pageOrder = ArrayList(pages)
        sink.batch {
            for (i in pages.indices) fileAll(pages[i], xforms[i])
        }
        publishOrder()
    }

    @JvmName("rebuildAtOrigins")
    fun rebuild(pages: List<Page>, origins: List<Pt>) = rebuild(pages, origins.map { PageXform(it) })

    /**
     * Re-file one page after it changed, or after it moved. With [dirty] (page-local), only items
     * that are new, or whose paint extent touches it, are re-meshed; the rest stay as filed. An
     * eraser drag reports a small region per move, and re-meshing a whole page for each was the cost.
     */
    fun refile(page: Page, xf: PageXform, dirty: Rect? = null) {
        val old = filed[page].orEmpty()
        val now = page.items
        val oldSet = IdentityHashMap<CanvasItem, Boolean>()
        for (it in old) oldSet[it] = true
        if (old.isNotEmpty()) {
            val keep = IdentityHashMap<CanvasItem, Boolean>()
            for (it in now) keep[it] = true
            for (it in old) if (!keep.containsKey(it)) sink.remove(it)
        }
        sink.batch {
            val items = ArrayList<CanvasItem>(now.size)
            for (item in now) {
                val untouched = dirty != null && oldSet.containsKey(item) && !item.paintBounds().intersects(dirty)
                if (untouched || fileOne(item, xf)) items.add(item)
            }
            filed[page] = items
            if (page !in pageOrder) pageOrder = pageOrder + page
        }
        publishOrder()
    }

    fun refile(page: Page, origin: Pt, dirty: Rect? = null) = refile(page, PageXform(origin), dirty)

    /**
     * One item joined the end of [page] (an ordinary committed stroke): file just it, rather than
     * re-meshing the page. Falls back to [refile] when the page was never filed.
     */
    fun appendItem(page: Page, item: CanvasItem, xf: PageXform) {
        val old = filed[page]
        if (old == null) return refile(page, xf)
        if (!fileOne(item, xf)) return
        if (old.none { it === item }) filed[page] = old + item
        publishOrder()
    }

    fun appendItem(page: Page, item: CanvasItem, origin: Pt) = appendItem(page, item, PageXform(origin))

    /** Items currently filed across all pages, for diagnostics. */
    fun filedCount(): Int = filed.values.sumOf { it.size }

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

    private fun fileAll(page: Page, xf: PageXform) {
        val items = ArrayList<CanvasItem>(page.items.size)
        for (item in page.items) if (fileOne(item, xf)) items.add(item)
        filed[page] = items
        if (page !in pageOrder) pageOrder = pageOrder + page
    }

    /** File one item; false when it draws nothing (or is being left out) and so is not in the order. */
    private fun fileOne(item: CanvasItem, xf: PageXform): Boolean {
        if (skip(item)) {
            sink.remove(item)
            return false
        }
        when (item) {
            is ImageItem -> {
                sink.upsertImage(item, item.paintBounds().rotatedQuarter(xf.rot).translate(xf.ox, xf.oy), xf.ox, xf.oy, xf.rot)
                return true
            }
            is TextItem -> {
                if (item.text.isEmpty()) {
                    sink.remove(item)
                    return false
                }
                sink.upsertText(item, item.bounds().rotatedQuarter(xf.rot).translate(xf.ox, xf.oy), textKey(item, xf.rot))
                return true
            }
            else -> {
                val meshed = mesh(item)
                if (meshed == null || meshed.isEmpty) {
                    sink.remove(item)
                    return false
                }
                val moved = meshed.transformed(xf.rot, xf.ox, xf.oy)
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
        fun textKey(t: TextItem, rot: Int = 0): Long {
            val h = java.util.Objects.hash(t.text, t.font, t.pointSize, t.rgba, t.width, t.height, rot and 3)
            return h.toLong() and 0x7FFFFFFFL
        }
    }
}
