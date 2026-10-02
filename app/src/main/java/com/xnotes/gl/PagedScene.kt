package com.xnotes.gl

import android.opengl.GLES30

/**
 * The scene for a paged note: each page's static underlay (paper, PDF, ruling, flow text) as a
 * texture, then every item's ink from [ink] on top, all in one framebuffer so highlighters can
 * multiply against the page.
 *
 * Pages live at their place in one content plane, so the camera is the plain zoom and scroll of a
 * [FrameState] and nothing here knows about page layout beyond the rects it is handed. The host
 * replaces [pages] when the layout or any page's look changes, and answers [onNeedTextures] by
 * rendering bitmaps with Skia and calling `underlay.submit`.
 */
class PagedScene(val ink: CanvasScene) : GlScene {

    val underlay = PageUnderlay<Any>()

    @Volatile
    var pages: List<PageQuad> = emptyList()

    /**
     * Called on the GL thread with the pages (visible, or within a screen of it) whose resident
     * texture is missing or out of date. Cheap to call every frame: the host dedupes in-flight work.
     */
    @Volatile
    var onNeedTextures: ((List<PageQuad>) -> Unit)? = null

    private var shader: ImageShader? = null
    private var contextGen = -1

    override fun onContextCreated(contextGen: Int) {
        this.contextGen = contextGen
        shader = null
        underlay.onContextCreated(contextGen)
        try {
            shader = ImageShader(contextGen)
        } catch (e: GlShaderException) {
            android.util.Log.e("PagedScene", "page shader unavailable", e)
        }
        ink.onContextCreated(contextGen)
    }

    override fun drawContent(frame: FrameState) {
        underlay.beginFrame()
        underlay.uploadPending()
        val all = pages
        val near = PageQuads.inView(all, frame.zoom, frame.scrollX, frame.scrollY, frame.widthPx, frame.heightPx, marginPx = frame.heightPx.toDouble())
        val stale = near.filter { underlay.versionOf(it.key) != it.version }
        if (stale.isNotEmpty()) onNeedTextures?.invoke(stale)

        val visible = PageQuads.inView(near, frame.zoom, frame.scrollX, frame.scrollY, frame.widthPx, frame.heightPx)
        val s = shader
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        for (page in visible) {
            val c = PageQuads.corners(page.rect, frame.zoom, frame.scrollX, frame.scrollY, frame.widthPx, frame.heightPx)
            val drawn = s != null && s.contextGen == contextGen && underlay.draw(s, page.key, c)
            if (!drawn) fillPaper(page, frame)
        }
        ink.drawContent(frame)
    }

    /** Plain paper under a page whose texture has not arrived, via a scissored clear. */
    private fun fillPaper(page: PageQuad, frame: FrameState) {
        val l = Math.floor((page.rect.left - frame.scrollX) * frame.zoom).toInt().coerceAtLeast(0)
        val r = Math.ceil((page.rect.right - frame.scrollX) * frame.zoom).toInt().coerceAtMost(frame.widthPx)
        val t = Math.floor((page.rect.top - frame.scrollY) * frame.zoom).toInt().coerceAtLeast(0)
        val b = Math.ceil((page.rect.bottom - frame.scrollY) * frame.zoom).toInt().coerceAtMost(frame.heightPx)
        if (r <= l || b <= t) return
        GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
        GLES30.glScissor(l, frame.heightPx - b, r - l, b - t)
        GLES30.glClearColor(
            ((page.paperRgb shr 16) and 0xFF) / 255f,
            ((page.paperRgb shr 8) and 0xFF) / 255f,
            (page.paperRgb and 0xFF) / 255f,
            1f,
        )
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
    }

    override fun describe(into: GlStats): GlStats = ink.describe(into)
}
