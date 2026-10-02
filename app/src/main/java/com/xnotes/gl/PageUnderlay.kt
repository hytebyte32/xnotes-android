package com.xnotes.gl

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLUtils
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * GL textures for the static layer under a page's ink: paper, PDF raster, ruling and flow text,
 * rendered by Skia into bitmaps and drawn here as quads.
 *
 * Bitmaps arrive from any thread through [submit]; they are uploaded on the render thread at the
 * start of the next frame ([uploadPending]) and recycled straight after, so no bitmap outlives its
 * upload. A texture is keyed by whatever the caller likes (a page, or a page plus a tile) and carries
 * a version, so the caller can tell whether what is resident is current without asking Skia again.
 * [UnderlayLedger] decides what to evict.
 */
class PageUnderlay<K : Any>(budgetBytes: Long = DEFAULT_BUDGET_BYTES) {

    private class Entry(val texture: Int, val width: Int, val height: Int, val version: Long)

    private class Pending<K>(val key: K, val bitmap: Bitmap, val version: Long)

    private val ledger = UnderlayLedger<K>(budgetBytes)
    private val entries = HashMap<K, Entry>()
    private val ready = ConcurrentLinkedQueue<Pending<K>>()

    private var contextGen = -1

    val residentBytes: Long get() = ledger.residentBytes
    val textureCount: Int get() = entries.size

    /** A new context: every texture name is gone, so forget them rather than delete them. */
    fun onContextCreated(gen: Int) {
        contextGen = gen
        entries.clear()
        ledger.clear()
        while (true) (ready.poll() ?: break).bitmap.recycle()
    }

    /** Hand over a rendered bitmap for [key]. Ownership passes to the underlay; call from any thread. */
    fun submit(key: K, bitmap: Bitmap, version: Long) {
        ready.add(Pending(key, bitmap, version))
    }

    /** The version resident for [key], or null when nothing is. Render thread. */
    fun versionOf(key: K): Long? = entries[key]?.version

    /** Take in whatever arrived since the last frame. Call with the context current. */
    fun uploadPending() {
        while (true) {
            val next = ready.poll() ?: return
            if (next.bitmap.isRecycled) continue
            val old = entries.remove(next.key)
            if (old != null) GLES30.glDeleteTextures(1, intArrayOf(old.texture), 0)
            val name = IntArray(1)
            GLES30.glGenTextures(1, name, 0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, name[0])
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, next.bitmap, 0)
            val w = next.bitmap.width
            val h = next.bitmap.height
            entries[next.key] = Entry(name[0], w, h, next.version)
            ledger.put(next.key, w.toLong() * h * 4)
            next.bitmap.recycle()
        }
    }

    /** Start a frame and delete what the budget no longer covers. Render thread. */
    fun beginFrame() {
        for (key in ledger.beginFrame()) {
            val e = entries.remove(key) ?: continue
            GLES30.glDeleteTextures(1, intArrayOf(e.texture), 0)
        }
    }

    /**
     * Draw [key]'s texture over the clip-space quad [corners] (top-left, top-right, bottom-left,
     * bottom-right). False when nothing is resident yet, so the caller can fall back to plain paper.
     */
    fun draw(shader: ImageShader, key: K, corners: FloatArray, premultiplied: Boolean = false): Boolean {
        val e = entries[key] ?: return false
        ledger.touch(key)
        shader.draw(corners, e.texture, 0, premultiplied = premultiplied)
        return true
    }

    /** Delete one key's texture, e.g. when its page is gone. Render thread. */
    fun drop(key: K) {
        val e = entries.remove(key) ?: return
        ledger.remove(key)
        GLES30.glDeleteTextures(1, intArrayOf(e.texture), 0)
    }

    /** Drop everything, deleting the textures. Call with the context current. */
    fun release() {
        for (e in entries.values) GLES30.glDeleteTextures(1, intArrayOf(e.texture), 0)
        entries.clear()
        ledger.clear()
    }

    companion object {
        /** Enough for roughly a dozen tablet-resolution pages. */
        const val DEFAULT_BUDGET_BYTES = 128L * 1024 * 1024
    }
}
