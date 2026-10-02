package com.xnotes.gl

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.infinite.InkPass
import com.xnotes.core.infinite.MeshData
import com.xnotes.core.infinite.MeshPart
import com.xnotes.core.infinite.MeshedItem
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.Page
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.LabelItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PagedInkSyncTest {

    private class Recorder : InkSink {
        val upserts = ArrayList<Pair<CanvasItem, Rect>>()
        val firstX = HashMap<CanvasItem, Double>()
        val removed = ArrayList<CanvasItem>()
        var order: List<CanvasItem> = emptyList()
        var resets = 0
        override fun upsert(item: CanvasItem, parts: List<MeshPart>, bounds: Rect) {
            upserts.add(item to bounds)
            firstX[item] = parts[0].mesh.positions[0]
        }
        override fun remove(item: CanvasItem) { removed.add(item) }
        override fun setOrder(items: List<CanvasItem>) { order = items }
        override fun reset() { resets++ }
        override fun batch(block: () -> Unit) = block()
    }

    private fun item(): CanvasItem = LabelItem(Pt(0.0, 0.0), "1", 10.0)

    private val unit = MeshedItem(
        listOf(MeshPart(MeshData(doubleArrayOf(0.0, 0.0, 1.0, 0.0, 0.0, 1.0), DoubleArray(6), intArrayOf(0, 1, 2)), Rgba(0, 0, 0, 255), InkPass.OPAQUE)),
        Rect(0.0, 0.0, 10.0, 10.0),
    )

    @Test fun rebuildFilesInkDisplacedToEachPage() {
        val r = Recorder()
        val a = Page(100.0, 100.0).also { it.items.add(item()) }
        val b = Page(100.0, 100.0).also { it.items.add(item()) }
        PagedInkSync(r) { unit }.rebuild(listOf(a, b), listOf(Pt(0.0, 0.0), Pt(500.0, 700.0)))
        assertEquals(1, r.resets)
        assertEquals(0.0, r.firstX[a.items[0]]!!, 0.0)
        assertEquals(500.0, r.firstX[b.items[0]]!!, 0.0)
        assertEquals(Rect(500.0, 700.0, 10.0, 10.0), r.upserts.first { it.first === b.items[0] }.second)
    }

    @Test fun zOrderIsPagesTopToBottomThenItemOrder() {
        val r = Recorder()
        val a = Page(1.0, 1.0).also { it.items.addAll(listOf(item(), item())) }
        val b = Page(1.0, 1.0).also { it.items.add(item()) }
        PagedInkSync(r) { unit }.rebuild(listOf(a, b), listOf(Pt(0.0, 0.0), Pt(0.0, 10.0)))
        assertEquals(listOf(a.items[0], a.items[1], b.items[0]), r.order)
    }

    @Test fun refileRemovesItemsThatLeftThePage() {
        val r = Recorder()
        val a = Page(1.0, 1.0).also { it.items.addAll(listOf(item(), item())) }
        val sync = PagedInkSync(r) { unit }
        sync.rebuild(listOf(a), listOf(Pt(0.0, 0.0)))
        val gone = a.items.removeAt(0)
        sync.refile(a, Pt(0.0, 0.0))
        assertTrue(r.removed.any { it === gone })
        assertEquals(1, r.order.size)
        assertSame(a.items[0], r.order[0])
    }

    @Test fun itemsThatMeshToNothingAreNotInTheOrder() {
        val r = Recorder()
        val a = Page(1.0, 1.0).also { it.items.add(item()) }
        PagedInkSync(r) { null }.rebuild(listOf(a), listOf(Pt(0.0, 0.0)))
        assertTrue(r.order.isEmpty())
    }

    @Test fun dropTakesAPagesInkOut() {
        val r = Recorder()
        val a = Page(1.0, 1.0).also { it.items.add(item()) }
        val b = Page(1.0, 1.0).also { it.items.add(item()) }
        val sync = PagedInkSync(r) { unit }
        sync.rebuild(listOf(a, b), listOf(Pt(0.0, 0.0), Pt(0.0, 10.0)))
        sync.drop(a)
        assertEquals(listOf(b.items[0]), r.order)
        assertTrue(r.removed.any { it === a.items[0] })
    }
}

class PagedInkSyncAppendTest {
    private class Rec : InkSink {
        var upserts = 0
        var order: List<CanvasItem> = emptyList()
        override fun upsert(item: CanvasItem, parts: List<MeshPart>, bounds: Rect) { upserts++ }
        override fun remove(item: CanvasItem) {}
        override fun setOrder(items: List<CanvasItem>) { order = items }
        override fun reset() {}
        override fun batch(block: () -> Unit) = block()
    }

    private val unit = MeshedItem(
        listOf(MeshPart(MeshData(doubleArrayOf(0.0, 0.0, 1.0, 0.0, 0.0, 1.0), DoubleArray(6), intArrayOf(0, 1, 2)), Rgba(0, 0, 0, 255), InkPass.OPAQUE)),
        Rect(0.0, 0.0, 10.0, 10.0),
    )

    @Test fun appendFilesOnlyTheNewItem() {
        val r = Rec()
        val a = Page(1.0, 1.0).also { it.items.add(LabelItem(Pt(0.0, 0.0), "1", 10.0)) }
        val sync = PagedInkSync(r) { unit }
        sync.rebuild(listOf(a), listOf(Pt(0.0, 0.0)))
        val before = r.upserts
        val added = LabelItem(Pt(0.0, 0.0), "2", 10.0)
        a.items.add(added)
        sync.appendItem(a, added, Pt(0.0, 0.0))
        assertEquals(before + 1, r.upserts)
        assertEquals(listOf(a.items[0], added), r.order)
    }

    @Test fun appendToAnUnfiledPageRefilesIt() {
        val r = Rec()
        val a = Page(1.0, 1.0).also { it.items.add(LabelItem(Pt(0.0, 0.0), "1", 10.0)) }
        PagedInkSync(r) { unit }.appendItem(a, a.items[0], Pt(0.0, 0.0))
        assertEquals(1, r.order.size)
    }
}
