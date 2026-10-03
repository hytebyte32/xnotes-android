package com.xnotes.core.edit

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.history.Command
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.Stroke
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EraseToolTest {

    private class FakeSection(val items: MutableList<CanvasItem>, val dx: Double = 0.0) : EditSection {
        override fun toLocal(content: Pt) = Pt(content.x - dx, content.y)
        override fun itemsIn(box: Rect) = items.toList()
        override fun replaceItem(item: CanvasItem, replacements: List<CanvasItem>): Int {
            val at = items.indexOfFirst { it === item }
            if (at < 0) return -1
            items.removeAt(at)
            items.addAll(at, replacements)
            return at
        }
    }

    private class FakeSurface(val sections: List<FakeSection>) : EditSurface {
        override val zoom = 1.0
        val repaired = ArrayList<Pair<EditSection, Rect>>()
        var lastCuts: List<Cut> = emptyList()
        override fun viewportToContent(p: Pt) = p
        override fun sectionsNear(box: Rect): List<EditSection> = sections
        override fun repair(section: EditSection, dirty: Rect) { repaired.add(section to dirty) }
        override fun eraseCommand(cuts: List<Cut>, area: Boolean): Command? {
            lastCuts = cuts
            return null
        }
    }

    private fun line(length: Double, y: Double = 0.0): Stroke = Stroke(
        Tool.PEN,
        ToolDefaults.configFor(Tool.PEN),
        (0..(length / 5).toInt()).map { Sample(it * 5.0, y, 1.0) }.toMutableList(),
    )

    private fun image(): ImageItem = ImageItem(ImageData(File("none"), 10, 10), Rect(0.0, 0.0, 10.0, 10.0))

    @Test fun strokeModeRemovesTouchedAndRepairs() {
        val hit = line(100.0)
        val miss = line(100.0, y = 500.0)
        val sec = FakeSection(mutableListOf(hit, miss))
        val surface = FakeSurface(listOf(sec))
        val tool = EraseTool(surface, EraserPolicy.PAGED)
        tool.begin()
        assertTrue(tool.eraseAt(Pt(50.0, 0.0), 8.0, area = false))
        assertEquals(listOf<CanvasItem>(miss), sec.items)
        assertEquals(1, surface.repaired.size)
        tool.buildCommand(false)
        assertEquals(1, surface.lastCuts.size)
        assertSame(hit, surface.lastCuts[0].original)
        assertEquals(0, surface.lastCuts[0].at)
        assertTrue(surface.lastCuts[0].fragments.isEmpty())
    }

    @Test fun missesChangeNothing() {
        val sec = FakeSection(mutableListOf(line(100.0)))
        val tool = EraseTool(FakeSurface(listOf(sec)), EraserPolicy.PAGED)
        assertFalse(tool.eraseAt(Pt(50.0, 400.0), 8.0, area = false))
        assertTrue(tool.isEmpty)
        assertNull(tool.buildCommand(false))
    }

    @Test fun imagesAndLockedSurviveBothModes() {
        val img = image()
        val locked = line(100.0).also { it.locked = true }
        val sec = FakeSection(mutableListOf(img, locked))
        val tool = EraseTool(FakeSurface(listOf(sec)), EraserPolicy.PAGED)
        assertFalse(tool.eraseAt(Pt(5.0, 0.0), 20.0, area = false))
        assertFalse(tool.eraseAt(Pt(5.0, 0.0), 20.0, area = true))
        assertEquals(2, sec.items.size)
    }

    @Test fun areaModeCutsAndFragmentsKeepTheSlot() {
        val before = line(10.0, y = 100.0)
        val s = line(100.0)
        val sec = FakeSection(mutableListOf(before, s))
        val tool = EraseTool(FakeSurface(listOf(sec)), EraserPolicy.PAGED)
        assertTrue(tool.eraseAt(Pt(50.0, 0.0), 8.0, area = true))
        assertEquals(3, sec.items.size)
        assertSame(before, sec.items[0])
    }

    @Test fun recutFragmentsCoalesceIntoOneEntry() {
        val s = line(100.0)
        val sec = FakeSection(mutableListOf(s))
        val surface = FakeSurface(listOf(sec))
        val tool = EraseTool(surface, EraserPolicy.PAGED)
        tool.eraseAt(Pt(30.0, 0.0), 6.0, area = true)
        tool.eraseAt(Pt(70.0, 0.0), 6.0, area = true)
        tool.buildCommand(true)
        assertEquals(1, surface.lastCuts.size)
        assertSame(s, surface.lastCuts[0].original)
        assertEquals(3, surface.lastCuts[0].fragments.size)
        assertEquals(sec.items, surface.lastCuts[0].fragments)
    }

    @Test fun beginForgetsTheLastDrag() {
        val sec = FakeSection(mutableListOf(line(100.0)))
        val tool = EraseTool(FakeSurface(listOf(sec)), EraserPolicy.PAGED)
        tool.eraseAt(Pt(50.0, 0.0), 8.0, area = false)
        assertFalse(tool.isEmpty)
        tool.begin()
        assertTrue(tool.isEmpty)
    }

    @Test fun sectionsUseTheirOwnFrame() {
        val a = line(100.0)
        val sec = FakeSection(mutableListOf(a), dx = 1000.0)
        val tool = EraseTool(FakeSurface(listOf(sec)), EraserPolicy.PAGED)
        assertFalse(tool.eraseAt(Pt(50.0, 0.0), 8.0, area = false))
        assertTrue(tool.eraseAt(Pt(1050.0, 0.0), 8.0, area = false))
    }
}
