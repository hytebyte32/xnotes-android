package com.xnotes.platform

import com.xnotes.core.FakeImageCodec
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.format.CanvasCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CanvasSessionStoreTest {

    private fun dir(): File = Files.createTempDirectory("xnotes-canvas-session").toFile()
    private fun images(): File = Files.createTempDirectory("xnotes-canvas-session-img").toFile()
    private fun store(dir: File, images: File = images()) = CanvasSessionStore(dir, CanvasCodec(FakeImageCodec()), images)

    @Test fun nothingStoredMeansNothingToRestore() {
        val s = store(dir())
        assertFalse(s.exists())
        assertNull(s.load())
    }

    @Test fun roundTripsTheDocumentAndItsIdentity() {
        val d = dir()
        val doc = InfiniteDocument(dpi = 200, path = "content://x/y.xcanvas", displayName = "y.xcanvas", dirty = true)
        store(d).save(doc, writeDocument = true)

        val s = store(d)
        assertTrue(s.exists())
        val back = s.load()!!
        assertEquals(200, back.dpi)
        assertEquals("content://x/y.xcanvas", back.path)
        assertEquals("y.xcanvas", back.displayName)
        assertTrue(back.dirty)
    }

    @Test fun anUnsavedCanvasHasNoPathOrName() {
        val d = dir()
        store(d).save(InfiniteDocument(), writeDocument = true)
        val back = store(d).load()!!
        assertNull(back.path)
        assertNull(back.displayName)
        assertFalse(back.dirty)
    }

    @Test fun aMetaOnlySaveUpdatesTheFlagsWithoutRewritingTheDocument() {
        val d = dir()
        val doc = InfiniteDocument(dpi = 150, dirty = true)
        val s = store(d)
        s.save(doc, writeDocument = true)
        val stamp = File(d, "canvas.xcanvas").lastModified()
        val size = File(d, "canvas.xcanvas").length()

        doc.dirty = false
        doc.path = "content://later"
        s.save(doc, writeDocument = false)

        assertEquals(size, File(d, "canvas.xcanvas").length())
        assertEquals(stamp, File(d, "canvas.xcanvas").lastModified())
        val back = s.load()!!
        assertFalse(back.dirty)
        assertEquals("content://later", back.path)
    }

    @Test fun aMetaOnlySaveWithNoDocumentFileYetStillWritesOne() {
        val d = dir()
        val s = store(d)
        s.save(InfiniteDocument(), writeDocument = false)
        assertTrue(s.exists())
        assertNotNull(s.load())
    }

    @Test fun theTempFileDoesNotLinger() {
        val d = dir()
        store(d).save(InfiniteDocument(), writeDocument = true)
        assertFalse(File(d, "canvas.xcanvas.tmp").exists())
    }

    @Test fun aCorruptSessionRestoresNothingInsteadOfFailing() {
        val d = dir()
        File(d, "canvas.xcanvas").writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        val s = store(d)
        assertTrue(s.exists())
        assertNull(s.load())
    }

    @Test fun clearForgetsTheSession() {
        val d = dir()
        val s = store(d)
        s.save(InfiniteDocument(path = "p", dirty = true), writeDocument = true)
        s.clear()
        assertFalse(s.exists())
        assertNull(s.load())
    }

    @Test fun savingIntoAMissingDirectoryCreatesIt() {
        val d = File(dir(), "nested/deeper")
        val s = store(d)
        s.save(InfiniteDocument(), writeDocument = true)
        assertTrue(s.exists())
    }
}
