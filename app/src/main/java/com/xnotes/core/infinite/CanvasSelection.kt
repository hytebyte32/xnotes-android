package com.xnotes.core.infinite

import com.xnotes.canvas.HandleId
import com.xnotes.canvas.ResizeHandle
import com.xnotes.canvas.ResizeMath
import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Obb
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.history.Command
import com.xnotes.core.history.MoveItems
import com.xnotes.core.history.TransformItems
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.GeometrySnapshot
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.ShapeItem

/**
 * What is selected on the canvas, and the arithmetic of moving, scaling and rotating it.
 *
 * A live drag restores each item's gesture-start geometry and re-applies the whole transform every
 * frame, rather than applying an incremental one. That is what keeps a drag from compounding
 * rounding error, and it is what lets a rotation be undone as a single step.
 *
 * The box is oriented rather than axis-aligned, so a selection that has been turned keeps its own
 * frame and its handles scale along its own axes. Both come from [ResizeMath], shared with the
 * paged canvas, so a resize behaves the same on either surface.
 *
 * Pure Kotlin: no view, no renderer, so all of it unit-tests.
 */
class CanvasSelection(private val doc: InfiniteDocument) {

    var items: List<CanvasItem> = emptyList()
        private set

    /** The oriented box around the selection, or null when nothing is selected. */
    var box: Obb? = null
        private set

    val isEmpty: Boolean get() = items.isEmpty()

    /** Gesture-start geometry, captured by [beginTransform] and restored every frame of a drag. */
    private var startSnapshots: List<GeometrySnapshot> = emptyList()
    private var startBox: Obb? = null

    /** Where the finger came down, as an angle about the box's centre. Null when it was not given. */
    private var startGrabAngle: Double? = null

    fun select(next: List<CanvasItem>) {
        cropImage = null
        cropBefore = null
        items = next
        box = boundsOf(next)?.let { Obb.fromAabb(it) }
    }

    fun clear() {
        cropImage = null
        cropBefore = null
        items = emptyList()
        box = null
        startSnapshots = emptyList()
        startBox = null
        startGrabAngle = null
    }

    /**
     * Re-derive the box from the items, after something outside a drag changed them.
     *
     * The box comes back upright, because item bounds are axis aligned and cannot say what angle
     * the content is at. Keeping the old angle and tilting the fresh bounds was worse than useless:
     * every rotation grew the box by its own turn, so a selection ballooned and skewed the moment
     * the finger came off the grip.
     */
    fun refreshBox() {
        box = boundsOf(items)?.let { Obb.fromAabb(it) }
    }

    /**
     * The one shape selected, when it is edited by its own points instead of a box: line ends,
     * polygon corners, the four corners of a rectangle or ellipse. Null for everything else.
     */
    val pointShape: ShapeItem?
        get() = (items.singleOrNull() as? ShapeItem)?.takeIf { it.usesPointHandles }

    /** The point handles of [pointShape], in content space; empty when the box handles apply. */
    fun pointHandles(): List<Pt> = pointShape?.editPoints() ?: emptyList()

    /** Index of the point handle [p] lands on, within [tolerance] content pixels, or null. */
    fun hitPoint(p: Pt, tolerance: Double): Int? {
        var best: Int? = null
        var bestDist = tolerance
        pointHandles().forEachIndexed { i, pt ->
            val d = pt.distanceTo(p)
            if (d <= bestDist) {
                bestDist = d
                best = i
            }
        }
        return best
    }

    private var dragPointIndex = -1

    /** Start dragging point [index]; captures the gesture-start geometry. */
    fun beginPointDrag(index: Int) {
        beginTransform()
        dragPointIndex = index
    }

    /** Put the dragged point at [pointer], measured from the gesture start so it cannot compound. */
    fun dragPointLive(pointer: Pt) {
        val shape = pointShape ?: items.firstOrNull() as? ShapeItem ?: return
        if (dragPointIndex < 0 || startSnapshots.isEmpty()) return
        shape.restoreGeometry(startSnapshots[0])
        shape.movePoint(dragPointIndex, pointer)
        doc.itemsChanged(items)
        refreshBox()
    }

    /** Put the shape back as it was when the drag began, for a cancelled gesture. */
    fun cancelPointDrag() {
        if (startSnapshots.isNotEmpty() && items.isNotEmpty()) {
            items[0].restoreGeometry(startSnapshots[0])
            doc.itemsChanged(items)
        }
        dragPointIndex = -1
        refreshBox()
    }

    /** Finish a point drag; the undo edit comes from [buildCommand]. */
    fun endPointDrag() {
        dragPointIndex = -1
    }

    /** True when [p] is inside the selection, so a press there grabs it rather than starting a band. */
    fun contains(p: Pt): Boolean {
        pointShape?.let { return it.contains(p) }
        return box?.contains(p) == true
    }

    /** The eight resize handles, in content space; none for a shape edited by its points. */
    fun handles(): List<ResizeHandle> {
        if (pointShape != null) return emptyList()
        return box?.let { ResizeMath.obbHandles(it) } ?: emptyList()
    }

    /** The rotate grip's centre, [arm] content pixels past the box's top edge. */
    fun rotateGrip(arm: Double): Pt? =
        if (pointShape != null || cropping) null else box?.let { ResizeMath.obbRotateGrip(it, arm) }



    // --- crop ---

    /** The image being cropped, or null. While set, the box is the image's own and the grip is gone. */
    var cropImage: ImageItem? = null
        private set
    private var cropBefore: GeometrySnapshot? = null

    val cropping: Boolean get() = cropImage != null

    /** One unlocked, unturned image selected: the only thing that can be cropped. */
    fun canCrop(): Boolean {
        val img = items.singleOrNull() as? ImageItem ?: return false
        return !img.locked && img.angle == 0.0
    }

    fun beginCrop(): Boolean {
        if (!canCrop()) return false
        val img = items.single() as ImageItem
        cropImage = img
        cropBefore = img.snapshotGeometry()
        refreshBox()
        return true
    }

    /** Drag the crop side(s) [handle] stands for to [pointer]; the picture itself stays put. */
    fun cropLive(handle: HandleId, pointer: Pt) {
        val img = cropImage ?: return
        img.cropEdge(
            left = handle == HandleId.L || handle == HandleId.TL || handle == HandleId.BL,
            right = handle == HandleId.R || handle == HandleId.TR || handle == HandleId.BR,
            top = handle == HandleId.T || handle == HandleId.TL || handle == HandleId.TR,
            bottom = handle == HandleId.B || handle == HandleId.BL || handle == HandleId.BR,
            p = pointer,
            minSize = ResizeMath.MIN_SIZE,
        )
        doc.itemsChanged(items)
        refreshBox()
    }

    /** Leave crop mode, returning the undoable edit when the crop actually changed. */
    fun endCrop(): Command? {
        val img = cropImage ?: return null
        val before = cropBefore
        cropImage = null
        cropBefore = null
        refreshBox()
        if (before == null) return null
        val after = img.snapshotGeometry()
        if (after == before) return null
        return OnCanvas(doc, TransformItems(listOf(img), listOf(before), listOf(after)), listOf(img))
    }

    /** Which handle [p] lands on, within [tolerance] content pixels, or null. */
    fun hitHandle(p: Pt, tolerance: Double): HandleId? =
        ResizeMath.hitHandle(handles(), p, tolerance)

    // --- transforms ---

    /**
     * Capture the state a drag will be measured against. Call once, at pointer down. [grabAt] is
     * where the finger actually landed, which a rotation needs: it turns by the angle swept from
     * there, so a press a few pixels off the grip's centre no longer snaps the selection.
     */
    fun beginTransform(grabAt: Pt? = null) {
        startSnapshots = items.map { it.snapshotGeometry() }
        startBox = box
        val centre = box?.center
        startGrabAngle = if (grabAt == null || centre == null) null
        else kotlin.math.atan2(grabAt.y - centre.y, grabAt.x - centre.x)
    }

    /**
     * Put every item back where the drag began and bake [transform] over it. Called per frame, so
     * the drag is always one transform of the original rather than a chain of small ones.
     */
    fun applyLive(transform: Affine) {
        for (i in items.indices) items[i].restoreGeometry(startSnapshots[i])
        for (item in items) item.applyTransform(transform)
        doc.itemsChanged(items)
    }

    /**
     * Move the box alone, for a drag the renderer is offsetting rather than the model.
     *
     * A drag used to move the items themselves on every touch sample, which meant re-tessellating
     * and re-uploading every selected item several times a frame. The model now stays put until the
     * finger lifts, and [moveLive] applies the whole move once.
     */
    fun previewMove(dx: Double, dy: Double) {
        startBox?.let { box = it.translate(dx, dy) }
    }

    /** Move by a delta from the gesture start, restoring first so the drag cannot compound. */
    fun moveLive(dx: Double, dy: Double) {
        for (i in items.indices) items[i].restoreGeometry(startSnapshots[i])
        for (item in items) item.translate(dx, dy)
        startBox?.let { box = it.translate(dx, dy) }
        doc.itemsChanged(items)
    }

    /** Scale a handle drag in the box's own frame, anchored at the opposite handle. */
    fun resizeLive(handle: HandleId, pointer: Pt) {
        val from = startBox ?: return
        val result = ResizeMath.obbResize(from, handle, pointer)
        box = result.obb
        applyLive(result.transform)
    }

    /**
     * Scale the box alone and report the map, for a drag the renderer is scaling rather than the
     * model. The counterpart of [previewMove] and [previewRotate].
     */
    fun previewResize(handle: HandleId, pointer: Pt): Affine? {
        val from = startBox ?: return null
        val result = ResizeMath.obbResize(from, handle, pointer)
        box = result.obb
        return result.transform
    }

    /**
     * Turn the selection about its own centre by the angle the pointer has swept since the grab.
     *
     * Swept, not absolute: pointing the box's local up straight at the pointer means a press that
     * lands anywhere but the grip's exact centre jerks the selection round before the finger has
     * moved, and the grip is a 22 device pixel target 34 pixels out from the box.
     */
    fun rotateLive(pointer: Pt) {
        val from = startBox ?: return
        val swept = sweptAngle(from, pointer)
        box = from.copy(angle = from.angle + swept)
        applyLive(Affine.rotateAbout(from.center, swept))
    }

    /**
     * Turn the box alone and report the angle, for a drag the renderer is turning rather than the
     * model. The counterpart of [previewMove]: the model stays put until the finger lifts, and
     * [rotateLive] then applies the whole turn once.
     */
    fun previewRotate(pointer: Pt): Double {
        val from = startBox ?: return 0.0
        val swept = sweptAngle(from, pointer)
        box = from.copy(angle = from.angle + swept)
        return swept
    }

    /** Put the box back where the drag started, for a gesture that was cancelled rather than ended. */
    fun previewBack() {
        startBox?.let { box = it }
    }

    /** The point a transform turns and scales about: the box as it was when the gesture began. */
    val transformPivot: Pt? get() = startBox?.center

    private fun sweptAngle(from: Obb, pointer: Pt): Double {
        val centre = from.center
        // Without a recorded grab, fall back to the grip's own direction, which is the box's local
        // up: that reduces to pointing up at the pointer.
        val grab = startGrabAngle ?: (from.angle - Math.PI / 2.0)
        // A weak pull onto 30, 45 and 90 degree turns.
        return com.xnotes.core.geometry.AngleSnap.snapAngle(kotlin.math.atan2(pointer.y - centre.y, pointer.x - centre.x) - grab)
    }

    /**
     * The undoable edit for the drag just finished, or null when nothing actually moved. A move is
     * recorded as a move so it stays cheap; anything that changed shape is recorded as before and
     * after geometry snapshots, because a rotation can change a shape's very kind.
     */
    fun buildCommand(movedOnly: Boolean, dx: Double = 0.0, dy: Double = 0.0): Command? {
        if (items.isEmpty()) return null
        if (movedOnly) {
            if (dx == 0.0 && dy == 0.0) return null
            return OnCanvas(doc, MoveItems(items, dx, dy), items)
        }
        val after = items.map { it.snapshotGeometry() }
        return OnCanvas(doc, TransformItems(items, startSnapshots, after), items)
    }

    private fun boundsOf(of: List<CanvasItem>): Rect? {
        var acc: Rect? = null
        for (item in of) {
            val b = item.bounds()
            acc = acc?.union(b) ?: b
        }
        return acc
    }
}
