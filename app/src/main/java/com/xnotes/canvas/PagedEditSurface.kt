package com.xnotes.canvas

import com.xnotes.core.edit.Cut
import com.xnotes.core.edit.EditSection
import com.xnotes.core.edit.EditSurface
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.history.Command
import com.xnotes.core.history.CompositeCommand
import com.xnotes.core.history.EraseItems
import com.xnotes.core.history.ReplacePageItems
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.Page

/**
 * A paged note as an [EditSurface]: each page is a section whose frame is the page's own space,
 * and every change is reported to [CanvasState] so the GL host repaints what moved.
 */
class PagedEditSurface(private val state: CanvasState) : EditSurface {

    private class PageSection(val index: Int, val page: Page, private val state: CanvasState) : EditSection {
        override fun toLocal(content: Pt): Pt = state.toPageSpace(index, content)

        // Every item is offered; each item rejects itself by its own bounds.
        override fun itemsIn(box: Rect): List<CanvasItem> = page.items.toList()

        override fun replaceItem(item: CanvasItem, replacements: List<CanvasItem>): Int {
            val at = page.items.indexOfFirst { it === item }
            if (at < 0) return -1
            page.items.removeAt(at)
            page.items.addAll(at, replacements)
            return at
        }
    }

    override val zoom: Double get() = state.zoom

    override fun viewportToContent(p: Pt): Pt = state.viewportToContent(p)

    /** Page [index] as a section, or null when there is no such page. */
    fun section(index: Int): EditSection? =
        state.document.pages.getOrNull(index)?.let { PageSection(index, it, state) }

    override fun sectionsNear(box: Rect): List<EditSection> {
        val drawable = state.drawablePageRange()
        val out = ArrayList<EditSection>(2)
        for (pi in state.document.pages.indices) {
            if (pi !in drawable) continue // a hidden paginated neighbour can't be edited
            val pr = state.pageRects.getOrNull(pi) ?: continue
            if (!pr.intersects(box)) continue
            out.add(PageSection(pi, state.document.pages[pi], state))
        }
        return out
    }

    override fun repair(section: EditSection, dirty: Rect) {
        val page = (section as PageSection).page
        // Repaint only the changed area in place; rebuild the page only when nothing is attached to repaint.
        if (!state.repairRegion(page, dirty.outset(REPAIR_PAD))) state.invalidatePage(page)
    }

    override fun eraseCommand(cuts: List<Cut>, area: Boolean): Command? {
        if (cuts.isEmpty()) return null
        if (!area) return EraseItems(cuts.map { (it.section as PageSection).page to it.original })
        // One before/after snapshot per touched page, so the whole drag undoes as one step.
        val cmds = ArrayList<Command>()
        for (page in cuts.map { (it.section as PageSection).page }.distinct()) {
            val mine = cuts.filter { (it.section as PageSection).page === page }
            val after = page.items.toList()
            val before = after.toMutableList()
            for (cut in mine.asReversed()) {
                before.removeAll { item -> cut.fragments.any { it === item } }
                before.add(cut.at.coerceIn(0, before.size), cut.original)
            }
            if (before.size != after.size || before.indices.any { before[it] !== after[it] }) {
                cmds.add(ReplacePageItems(page, before, after))
            }
        }
        return when (cmds.size) {
            0 -> null
            1 -> cmds[0]
            else -> CompositeCommand(cmds)
        }
    }

    companion object {
        const val REPAIR_PAD = 2.0
    }
}
