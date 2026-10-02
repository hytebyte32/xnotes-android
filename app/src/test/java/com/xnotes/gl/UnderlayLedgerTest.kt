package com.xnotes.gl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnderlayLedgerTest {

    @Test fun underBudgetNothingIsEvicted() {
        val l = UnderlayLedger<String>(100)
        l.put("a", 40)
        l.put("b", 40)
        assertTrue(l.beginFrame().isEmpty())
        assertEquals(80L, l.residentBytes)
    }

    @Test fun putReplacesSizeAndCounts() {
        val l = UnderlayLedger<String>(100)
        l.put("a", 40)
        l.put("a", 10)
        assertEquals(10L, l.residentBytes)
        assertEquals(1, l.size)
    }

    @Test fun leastRecentlyDrawnGoesFirst() {
        val l = UnderlayLedger<String>(100)
        l.put("a", 60)
        l.put("b", 60)
        l.beginFrame()
        l.beginFrame() // both now older than the protected window
        l.touch("b")   // b drawn this frame
        l.put("c", 60)
        val out = l.beginFrame()
        assertEquals(listOf("a"), out)
        assertTrue("b" in l)
        assertFalse("a" in l)
    }

    @Test fun whatWasDrawnLastFrameIsProtected() {
        val l = UnderlayLedger<String>(50)
        l.put("a", 60)
        l.beginFrame() // a was put in the previous frame: protected
        assertTrue(l.size == 1)
        val out = l.beginFrame()
        assertEquals(listOf("a"), out)
        assertEquals(0L, l.residentBytes)
    }

    @Test fun stopsOnceUnderBudget() {
        val l = UnderlayLedger<String>(100)
        l.put("a", 50)
        l.put("b", 50)
        l.put("c", 50)
        l.beginFrame()
        l.beginFrame()
        l.beginFrame()
        assertEquals(2, l.size)
        assertTrue(l.residentBytes <= 100)
    }

    @Test fun removeAndClear() {
        val l = UnderlayLedger<String>(100)
        l.put("a", 30)
        assertTrue(l.remove("a"))
        assertFalse(l.remove("a"))
        l.put("b", 30)
        l.clear()
        assertEquals(0L, l.residentBytes)
        assertEquals(0, l.size)
    }
}
