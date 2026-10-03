package com.xnotes.ui

import com.xnotes.core.edit.Cut
import com.xnotes.core.edit.EditSection
import com.xnotes.core.edit.EditSurface
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.history.Command
import com.xnotes.core.infinite.CanvasViewport
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.infinite.SplitCanvasItems
import com.xnotes.core.model.CanvasItem

/**
 * The canvas as an [EditSurface]: one section that is the whole document, content space is its own
 * frame, and the spatial index narrows each query to the items under the tool. Repaint needs no
 * repair here, because the GL scene hears about edits from the document itself.
 */
class CanvasEditSurface(
    private val doc: InfiniteDocument,
    private val viewport: CanvasViewport,
) : EditSurface {

    private val whole = object : EditSection {
        override fun toLocal(content: Pt): Pt = content
        override fun itemsIn(box: Rect): List<CanvasItem> = doc.itemsIn(box)
        override fun replaceItem(item: CanvasItem, replacements: List<CanvasItem>): Int =
            doc.replaceItem(item, replacements)
    }

    override val zoom: Double get() = viewport.zoom

    override fun viewportToContent(p: Pt): Pt = viewport.viewportToContent(p)

    override fun sectionsNear(box: Rect): List<EditSection> = listOf(whole)

    override fun repair(section: EditSection, dirty: Rect) = Unit

    override fun eraseCommand(cuts: List<Cut>, area: Boolean): Command? =
        if (cuts.isEmpty()) null
        else SplitCanvasItems(doc, cuts.map { SplitCanvasItems.Split(it.at, it.original, it.fragments) })
}
