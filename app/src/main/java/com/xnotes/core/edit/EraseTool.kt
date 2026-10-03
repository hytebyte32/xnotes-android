package com.xnotes.core.edit

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.history.Command
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.LabelItem
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.model.Stroke
import com.xnotes.core.model.TextItem
import java.util.IdentityHashMap

/**
 * One eraser drag, from pen down to pen up, on any [EditSurface].
 *
 * Both modes reduce to the same shape: an item is swapped for whatever survives it, which is
 * nothing at all in whole-item mode and the surviving fragments in area mode. A fragment can be cut
 * again later in the same drag, so cuts are coalesced as it runs: each entry holds the item as it
 * was at pen down and the fragments left at pen up, never the states between.
 *
 * Images and anything locked are never erased. Whether text boxes and measurement labels are is the
 * surface's [EraserPolicy].
 *
 * Pure Kotlin, so the whole of the eraser's behaviour is unit-testable without a view.
 */
class EraseTool(private val surface: EditSurface, private val policy: EraserPolicy) {

    private class Entry(val section: EditSection, val at: Int, val original: CanvasItem, val fragments: MutableList<CanvasItem>)

    /** Cut items in the order they were first touched, which is the order undo has to reverse. */
    private val entries = ArrayList<Entry>()
    private val byOriginal = IdentityHashMap<CanvasItem, Entry>()

    /** Which original each live fragment came from, so a re-cut updates the right entry. */
    private val originOf = IdentityHashMap<CanvasItem, CanvasItem>()

    /** Whether the drag has cut anything at all. */
    val isEmpty: Boolean get() = entries.isEmpty()

    /** Forget any earlier drag. */
    fun begin() {
        entries.clear()
        byOriginal.clear()
        originOf.clear()
    }

    /**
     * Pass the eraser over [viewport] (the view's pixels) with [radius] in content units. Returns
     * whether anything was cut.
     */
    fun eraseAt(viewport: Pt, radius: Double, area: Boolean): Boolean {
        val content = surface.viewportToContent(viewport)
        val box = Rect(content.x - radius, content.y - radius, radius * 2, radius * 2)
        var changed = false
        for (section in surface.sectionsNear(box)) {
            if (eraseIn(section, section.toLocal(content), radius, area)) changed = true
        }
        return changed
    }

    /** The same, at a point already in [section]'s own frame. Returns whether anything was cut. */
    fun eraseIn(section: EditSection, local: Pt, radius: Double, area: Boolean): Boolean {
        val box = Rect(local.x - radius, local.y - radius, radius * 2, radius * 2)
        var dirty: Rect? = null
        for (item in section.itemsIn(box)) {
            val fragments = cut(item, local.x, local.y, radius, area) ?: continue
            val touched = item.paintBounds()
            val at = section.replaceItem(item, fragments)
            if (at < 0) continue
            record(section, item, at, fragments)
            dirty = dirty?.union(touched) ?: touched
        }
        if (dirty == null) return false
        surface.repair(section, dirty)
        return true
    }

    /** The single undoable edit for the whole drag, or null when it cut nothing. */
    fun buildCommand(area: Boolean): Command? =
        if (entries.isEmpty()) null
        else surface.eraseCommand(entries.map { Cut(it.section, it.at, it.original, it.fragments.toList()) }, area)

    /** What is left of [item], or null when the eraser leaves it alone. */
    private fun cut(item: CanvasItem, cx: Double, cy: Double, radius: Double, area: Boolean): List<CanvasItem>? {
        if (item.locked || item is ImageItem) return null
        if (item is TextItem && !policy.erasesText) return null
        if (!area) return if (item.intersectsCircle(cx, cy, radius)) emptyList() else null
        return when (item) {
            is Stroke -> item.erasedBy(cx, cy, radius)
            is ShapeItem -> item.erasedBy(cx, cy, radius)
            is TextItem -> if (item.intersectsCircle(cx, cy, radius)) emptyList() else null
            is LabelItem -> if (policy.areaErasesLabels && item.intersectsCircle(cx, cy, radius)) emptyList() else null
            else -> null
        }
    }

    /** Fold this cut into the entry for whichever item it ultimately came from. */
    private fun record(section: EditSection, item: CanvasItem, at: Int, fragments: List<CanvasItem>) {
        val original = originOf[item]
        val entry = original?.let { byOriginal[it] }
        if (entry == null) {
            val fresh = Entry(section, at, item, fragments.toMutableList())
            entries.add(fresh)
            byOriginal[item] = fresh
            for (fragment in fragments) originOf[fragment] = item
            return
        }
        // A fragment cut again: swap it for its own fragments inside the entry, in place, so the
        // entry always describes the original's net result rather than a chain of intermediates.
        val slot = entry.fragments.indexOfFirst { it === item }
        if (slot >= 0) {
            entry.fragments.removeAt(slot)
            entry.fragments.addAll(slot, fragments)
        }
        originOf.remove(item)
        for (fragment in fragments) originOf[fragment] = entry.original
    }
}
