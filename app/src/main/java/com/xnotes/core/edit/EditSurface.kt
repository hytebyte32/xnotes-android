package com.xnotes.core.edit

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.history.Command
import com.xnotes.core.model.CanvasItem

/**
 * One place items live: a page, or a region of a canvas. The tools in this package work on sections
 * and know nothing else about the document, so the same tool runs on a paged note and on a canvas.
 *
 * Sections are compared by identity. Everything a tool changes goes through the section's own
 * mutators, so each surface can keep its spatial index, its repaint bookkeeping and its undo
 * conventions to itself.
 */
interface EditSection {
    /** [content] (the surface's content space) as a point in this section's own frame, where its items live. */
    fun toLocal(content: Pt): Pt

    /** The items that may meet [box] (section-local), in z order. A section may return more than meet it. */
    fun itemsIn(box: Rect): List<CanvasItem>

    /**
     * Swap [item] for [replacements] in its own z slot, which is what a cut needs: the pieces that
     * survive sit exactly where the original did. Returns the slot, or -1 when [item] is not here.
     * An empty [replacements] simply removes the item.
     */
    fun replaceItem(item: CanvasItem, replacements: List<CanvasItem>): Int
}

/**
 * What a tool needs from the document and the view it is drawn in: coordinates, which sections are
 * under a point, how to repaint, and how to make a change undoable. A paged note and a canvas each
 * provide one.
 */
interface EditSurface {
    /** Screen pixels per content unit. */
    val zoom: Double

    /** A point in the view's pixels as a content-space point. */
    fun viewportToContent(p: Pt): Pt

    /** The sections whose area meets [box] (content space) and that a tool may edit right now. */
    fun sectionsNear(box: Rect): List<EditSection>

    /** [dirty] (section-local) changed in [section]: repaint just that. */
    fun repair(section: EditSection, dirty: Rect)

    /** The one undoable edit for a whole eraser drag, or null when [cuts] is empty. */
    fun eraseCommand(cuts: List<Cut>, area: Boolean): Command?
}

/**
 * One item an eraser drag cut: its [original] slot [at] when first touched, and the [fragments]
 * standing in its place when the drag ended (none when it was removed whole).
 */
class Cut(val section: EditSection, val at: Int, val original: CanvasItem, val fragments: List<CanvasItem>)

/**
 * The behaviour a surface keeps from before the tools were shared. The paged note and the canvas
 * differ in a few places; until each difference is settled on purpose, a surface says which way it
 * goes and the tool does exactly that.
 */
class EraserPolicy(
    /** Whether the eraser removes text boxes (in both modes). */
    val erasesText: Boolean,
    /** Whether the area eraser removes a measurement label whole. The whole-item mode always does. */
    val areaErasesLabels: Boolean,
) {
    companion object {
        val PAGED = EraserPolicy(erasesText = true, areaErasesLabels = false)
        val CANVAS = EraserPolicy(erasesText = false, areaErasesLabels = true)
    }
}
