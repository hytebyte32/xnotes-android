package com.xnotes.platform

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DocMetaStoreTest {

    private fun file(): File = File(Files.createTempDirectory("xnotes-docmeta").toFile(), "doc_meta.json")

    /** Writes are batched by 1.5 s; wait for the file to satisfy [ok] rather than sleeping a fixed time. */
    private fun waitFor(f: File, ok: (JSONObject) -> Boolean): JSONObject {
        val end = System.currentTimeMillis() + 8000
        var o = JsonStore(f).read()
        while (!ok(o) && System.currentTimeMillis() < end) {
            Thread.sleep(50)
            o = JsonStore(f).read()
        }
        return o
    }

    @Test fun getReturnsAMetaOnlyForTheModifiedTimeItWasReadAt() {
        val s = DocMetaStore(JsonStore(file()))
        s.put("a", DocMetaStore.Meta(pages = 3, pdf = true, modified = 1000L))
        assertEquals(3, s.get("a", 1000L)!!.pages)
        assertNull(s.get("a", 1001L))
        assertNull(s.get("missing", 1000L))
    }

    @Test fun latestIgnoresStaleness() {
        val s = DocMetaStore(JsonStore(file()))
        s.put("a", DocMetaStore.Meta(2, false, 1L))
        assertNotNull(s.latest("a"))
        assertEquals(2, s.latest("a")!!.pages)
        assertNull(s.latest("b"))
    }

    @Test fun aNewerReadReplacesTheOld() {
        val s = DocMetaStore(JsonStore(file()))
        s.put("a", DocMetaStore.Meta(2, false, 1L))
        s.put("a", DocMetaStore.Meta(5, true, 2L))
        assertEquals(5, s.latest("a")!!.pages)
        assertTrue(s.latest("a")!!.pdf)
        assertNull(s.get("a", 1L))
    }

    @Test fun loadsEntriesAlreadyOnDiskWithForgivingDefaults() {
        val f = file()
        JsonStore(f).write(JSONObject("""{"a":{"pages":4,"pdf":true,"modified":7},"b":{},"c":"junk"}"""))
        val s = DocMetaStore(JsonStore(f))
        assertEquals(4, s.get("a", 7L)!!.pages)
        assertEquals(0, s.latest("b")!!.pages)
        assertEquals(false, s.latest("b")!!.pdf)
        assertNull(s.latest("c"))
    }

    @Test fun changesAreWrittenBackInABatch() {
        val f = file()
        val s = DocMetaStore(JsonStore(f))
        s.put("a", DocMetaStore.Meta(1, false, 10L))
        s.put("b", DocMetaStore.Meta(2, true, 20L))
        val o = waitFor(f) { it.has("a") && it.has("b") }
        assertEquals(1, o.getJSONObject("a").getInt("pages"))
        assertEquals(true, o.getJSONObject("b").getBoolean("pdf"))
        assertEquals(20L, o.getJSONObject("b").getLong("modified"))
        // And a fresh store reads it back.
        assertEquals(2, DocMetaStore(JsonStore(f)).latest("b")!!.pages)
    }

    @Test fun aMoveCarriesTheFolderAndItsDescendantsOnly() {
        val s = DocMetaStore(JsonStore(file()))
        s.put("root/a", DocMetaStore.Meta(1, false, 1L))
        s.put("root/a/b", DocMetaStore.Meta(2, false, 1L))
        s.put("root/ab", DocMetaStore.Meta(3, false, 1L))
        s.rekeyTree("root/a", "root/c")
        assertNull(s.latest("root/a"))
        assertNull(s.latest("root/a/b"))
        assertEquals(1, s.latest("root/c")!!.pages)
        assertEquals(2, s.latest("root/c/b")!!.pages)
        assertEquals(3, s.latest("root/ab")!!.pages)
    }

    @Test fun removeMatchingDropsOnlyThoseKeys() {
        val s = DocMetaStore(JsonStore(file()))
        s.put("x", DocMetaStore.Meta(1, false, 1L))
        s.put("y", DocMetaStore.Meta(2, false, 1L))
        s.removeMatching { it == "x" }
        assertNull(s.latest("x"))
        assertNotNull(s.latest("y"))
    }

    @Test fun clearEmptiesMemoryAndEventuallyDisk() {
        val f = file()
        val s = DocMetaStore(JsonStore(f))
        s.put("x", DocMetaStore.Meta(1, false, 1L))
        waitFor(f) { it.has("x") }
        s.clear()
        assertNull(s.latest("x"))
        waitFor(f) { !it.has("x") }
        assertEquals(0, JsonStore(f).read().length())
    }
}
