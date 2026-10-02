package com.xnotes.gl

/**
 * The bookkeeping behind [PageUnderlay]: which keys hold how many bytes, when each was last drawn,
 * and which to drop to get back under budget. Pure, so the eviction rules are testable on the JVM
 * without a GL context.
 *
 * Anything drawn this frame or the one before is protected: a texture that was on screen a moment
 * ago is the one most likely to be needed again, and dropping it would make it flash.
 */
class UnderlayLedger<K : Any>(private val budgetBytes: Long) {

    private class Entry(var bytes: Long, var lastUsed: Long)

    private val entries = LinkedHashMap<K, Entry>()
    private var frame = 0L

    var residentBytes: Long = 0
        private set

    val size: Int get() = entries.size

    operator fun contains(key: K): Boolean = key in entries

    /** Mark [key] as drawn this frame. False when it is not held. */
    fun touch(key: K): Boolean {
        val e = entries[key] ?: return false
        e.lastUsed = frame
        return true
    }

    /** Record [key] as now holding [bytes], replacing any earlier size. It counts as used this frame. */
    fun put(key: K, bytes: Long) {
        val old = entries[key]
        if (old != null) {
            residentBytes -= old.bytes
            old.bytes = bytes
            old.lastUsed = frame
        } else {
            entries[key] = Entry(bytes, frame)
        }
        residentBytes += bytes
    }

    fun remove(key: K): Boolean {
        val e = entries.remove(key) ?: return false
        residentBytes -= e.bytes
        return true
    }

    fun clear() {
        entries.clear()
        residentBytes = 0
    }

    /** Start a new frame; returns the keys to delete, least recently drawn first, to meet the budget. */
    fun beginFrame(): List<K> {
        frame++
        if (residentBytes <= budgetBytes) return emptyList()
        val out = ArrayList<K>()
        val ordered = entries.entries.sortedBy { it.value.lastUsed }
        for ((key, e) in ordered) {
            if (residentBytes <= budgetBytes) break
            if (e.lastUsed >= frame - 1) continue
            out.add(key)
            residentBytes -= e.bytes
        }
        for (k in out) entries.remove(k)
        return out
    }
}
