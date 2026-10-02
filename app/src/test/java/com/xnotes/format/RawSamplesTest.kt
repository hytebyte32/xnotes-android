package com.xnotes.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class RawSamplesTest {

    @Test fun storesSamplesInOrder() {
        val r = RawSamples()
        r.add(1.0, 2.0, 0.5, 0.0)
        r.add(3.0, 4.0, 0.6, 0.0)
        assertEquals(2, r.n)
        assertEquals(3.0, r.xs[1], 0.0)
        assertEquals(4.0, r.ys[1], 0.0)
        assertEquals(0.5, r.ps[0], 0.0)
    }

    @Test fun timesAreNotAllocatedWhileAllZero() {
        val r = RawSamples()
        repeat(10) { r.add(it.toDouble(), 0.0, 1.0, 0.0) }
        assertNull(r.ts)
    }

    @Test fun firstNonZeroTimeAllocatesAndEarlierSamplesStayZero() {
        val r = RawSamples()
        repeat(3) { r.add(it.toDouble(), 0.0, 1.0, 0.0) }
        r.add(3.0, 0.0, 1.0, 7.5)
        val ts = r.ts
        assertNotNull(ts)
        assertEquals(0.0, ts!![0], 0.0)
        assertEquals(0.0, ts[2], 0.0)
        assertEquals(7.5, ts[3], 0.0)
    }

    @Test fun growsPastItsInitialCapacityKeepingEverything() {
        val r = RawSamples()
        val count = 1000
        for (i in 0 until count) r.add(i.toDouble(), i * 2.0, i * 0.001, if (i >= 500) i.toDouble() else 0.0)
        assertEquals(count, r.n)
        for (i in 0 until count) {
            assertEquals(i.toDouble(), r.xs[i], 0.0)
            assertEquals(i * 2.0, r.ys[i], 0.0)
            assertEquals(i * 0.001, r.ps[i], 0.0)
            assertEquals(if (i >= 500) i.toDouble() else 0.0, r.ts!![i], 0.0)
        }
    }
}
