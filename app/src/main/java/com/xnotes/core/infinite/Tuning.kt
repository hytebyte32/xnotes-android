package com.xnotes.core.infinite

/**
 * Switches for the renderer optimizations under test on the unify branch. Every default is today's
 * behaviour, so nothing changes unless a switch is turned on; the bench flips them to build its
 * grid. Fields are volatile because tessellation runs off the main thread.
 */
object Tuning {
    /** Rail vertices shared between neighbouring ribbon quads: two per sample instead of four per segment. */
    @Volatile var sharedRails = false

    /** Content-pixel chord error for round caps and joins; 0 keeps [StrokeTessellator.DEFAULT_TOLERANCE]. */
    @Volatile var capTolerance = 0.0

    /** Centreline samples within this many content pixels of the line between their neighbours are dropped; 0 is off. */
    @Volatile var simplifyTolerance = 0.0

    /** Re-tessellate at a coarser [Lod] level when zoom allows it. */
    @Volatile var lodEnabled = false

    /** The [Lod] level the scene is currently meshed at. */
    @Volatile var lodLevel = 0

    /** Fill gaps up to this many indices between opaque runs so culled neighbours do not split a draw call; 0 is off. */
    @Volatile var mergeGap = 0

    /** Upload committed geometry as GL_STATIC_DRAW rather than GL_DYNAMIC_DRAW. */
    @Volatile var staticBuffers = false

    /** The simplification tolerance in force: the explicit setting or the LOD level, whichever is coarser. */
    fun effectiveSimplify(): Double {
        val lod = if (lodEnabled) Lod.toleranceFor(lodLevel) else 0.0
        return maxOf(simplifyTolerance, lod)
    }

    /** Switch the optimizations on or off from preferences, with the tuned constants behind each. */
    fun apply(
        sharedRails: Boolean, simplify: Boolean, coarseCaps: Boolean,
        lod: Boolean, mergeDraws: Boolean, staticBuffers: Boolean,
    ) {
        this.sharedRails = sharedRails
        simplifyTolerance = if (simplify) SIMPLIFY_PX else 0.0
        capTolerance = if (coarseCaps) COARSE_CAP_TOLERANCE else 0.0
        lodEnabled = lod
        if (!lod) lodLevel = 0
        mergeGap = if (mergeDraws) MERGE_GAP_INDICES else 0
        this.staticBuffers = staticBuffers
    }

    const val SIMPLIFY_PX = 0.25
    const val COARSE_CAP_TOLERANCE = 0.5 / 8.0
    const val MERGE_GAP_INDICES = 30_000

    fun reset() {
        sharedRails = false
        capTolerance = 0.0
        simplifyTolerance = 0.0
        lodEnabled = false
        lodLevel = 0
        mergeGap = 0
        staticBuffers = false
    }

    /** One-line description, for the bench report. */
    fun describe(): String = buildString {
        append("rails=").append(if (sharedRails) "shared" else "split")
        append(" cap=").append(capTolerance)
        append(" simp=").append(simplifyTolerance)
        append(" lod=").append(if (lodEnabled) "on" else "off")
        append(" merge=").append(mergeGap)
        append(" static=").append(staticBuffers)
    }
}
