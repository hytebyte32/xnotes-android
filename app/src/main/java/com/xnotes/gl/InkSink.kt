package com.xnotes.gl

import com.xnotes.core.geometry.Rect
import com.xnotes.core.infinite.MeshPart
import com.xnotes.core.model.CanvasItem

/** Where meshed ink is filed. [CanvasScene] is the real one; tests record calls with a fake. */
interface InkSink {
    fun upsert(item: CanvasItem, parts: List<MeshPart>, bounds: Rect)
    fun remove(item: CanvasItem)
    fun setOrder(items: List<CanvasItem>)
    fun reset()
    fun batch(block: () -> Unit)
}

/** Adapts a [CanvasScene] to [InkSink]. */
class CanvasSceneSink(private val scene: CanvasScene) : InkSink {
    override fun upsert(item: CanvasItem, parts: List<MeshPart>, bounds: Rect) = scene.upsert(item, parts, bounds)
    override fun remove(item: CanvasItem) = scene.remove(item)
    override fun setOrder(items: List<CanvasItem>) = scene.setOrder(items)
    override fun reset() = scene.reset()
    override fun batch(block: () -> Unit) = scene.batch(block)
}
