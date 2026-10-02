package com.xnotes.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CreationTimeStoreTest {

    private fun file(): File = File(Files.createTempDirectory("xnotes-ctime").toFile(), "created.json")
    private fun onDisk(f: File): Map<String, Long> = JsonStore(f).read().let { o -> o.keys().asSequence().associateWith { o.getLong(it) } }

    @Test fun startsEmptyAndPersistsAPut() {
        val f = file()
        val s = CreationTimeStore(JsonStore(f))
        assertNull(s.get("a"))
        s.put("a", 100L)
        assertEquals(100L, s.get("a"))
        assertEquals(mapOf("a" to 100L), onDisk(f))
    }

    @Test fun reloadsWhatWasWritten() {
        val f = file()
        CreationTimeStore(JsonStore(f)).put("a", 5L)
        assertEquals(5L, CreationTimeStore(JsonStore(f)).get("a"))
    }

    @Test fun stampMissingNeverOverwritesAKnownTime() {
        val s = CreationTimeStore(JsonStore(file()))
        s.put("a", 1L)
        s.stampMissing(mapOf("a" to 99L, "b" to 2L))
        assertEquals(1L, s.get("a"))
        assertEquals(2L, s.get("b"))
    }

    @Test fun aFileOwnTimeOutranksTheFirstSeenStamp() {
        val s = CreationTimeStore(JsonStore(file()))
        s.stampMissing(mapOf("a" to 50L))
        s.put("a", 10L)
        assertEquals(10L, s.get("a"))
    }

    @Test fun stampMissingWithNothingNewDoesNotRewrite() {
        val f = file()
        val s = CreationTimeStore(JsonStore(f))
        s.put("a", 1L)
        f.delete()
        s.stampMissing(mapOf("a" to 2L))
        assertEquals(false, f.exists())
    }

    @Test fun aMoveCarriesTheFolderAndEverythingUnderItButNotSiblingsWithTheSamePrefix() {
        val f = file()
        val s = CreationTimeStore(JsonStore(f))
        s.stampMissing(mapOf("root/a" to 1L, "root/a/b" to 2L, "root/ab" to 3L))
        s.rekeyTree("root/a", "root/c")
        assertNull(s.get("root/a"))
        assertNull(s.get("root/a/b"))
        assertEquals(1L, s.get("root/c"))
        assertEquals(2L, s.get("root/c/b"))
        assertEquals(3L, s.get("root/ab"))
        assertEquals(mapOf("root/c" to 1L, "root/c/b" to 2L, "root/ab" to 3L), onDisk(f))
    }

    @Test fun removeMatchingForgetsAFoldersSubtree() {
        val f = file()
        val s = CreationTimeStore(JsonStore(f))
        s.stampMissing(mapOf("x" to 1L, "x/y" to 2L, "z" to 3L))
        s.removeMatching { it == "x" || it.startsWith("x/") }
        assertEquals(mapOf("z" to 3L), onDisk(f))
    }

    @Test fun clearForgetsEverythingOnDisk() {
        val f = file()
        val s = CreationTimeStore(JsonStore(f))
        s.put("a", 1L)
        s.clear()
        assertNull(s.get("a"))
        assertEquals(emptyMap<String, Long>(), onDisk(f))
    }
}
