package com.xnotes.gl

import com.xnotes.core.geometry.Rect
import com.xnotes.core.infinite.MeshPart
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.TextItem

/** Where meshed ink is filed. [CanvasScene] is the real one; tests record calls with a fake. */
interface InkSink {
    fun upsert(item: CanvasItem, parts: List<MeshPart>, bounds: Rect)
    fun remove(item: CanvasItem)
    fun setOrder(items: List<CanvasItem>)
    fun reset()
    fun batch(block: () -> Unit)

    /** A placed image, drawn from its own space displaced by ([dx], [dy]); [bounds] is in the plane. */
    fun upsertImage(item: ImageItem, bounds: Rect, dx: Double, dy: Double, rot: Int) {}

    /** A text box whose texture the host renders; [bounds] is in the plane and [key] stamps its content. */
    fun upsertText(item: TextItem, bounds: Rect, key: Long) {}
}

/** Adapts a [CanvasScene] to [InkSink]. */
class CanvasSceneSink(private val scene: CanvasScene) : InkSink {
    override fun upsert(item: CanvasItem, parts: List<MeshPart>, bounds: Rect) = scene.upsert(item, parts, bounds)
    override fun remove(item: CanvasItem) = scene.remove(item)
    override fun setOrder(items: List<CanvasItem>) = scene.setOrder(items)
    override fun reset() = scene.reset()
    override fun batch(block: () -> Unit) = scene.batch(block)
    override fun upsertImage(item: ImageItem, bounds: Rect, dx: Double, dy: Double, rot: Int) = scene.upsertImage(item, bounds, dx, dy, rot)
    override fun upsertText(item: TextItem, bounds: Rect, key: Long) = scene.upsertText(item, bounds, key)
}
