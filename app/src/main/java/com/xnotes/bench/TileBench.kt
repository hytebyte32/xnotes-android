package com.xnotes.bench

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Collections
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Bench-only stand-in for the hybrid compositor: a GL view that draws a grid of screen-resolution
 * tiles at a panned offset, one textured quad each, and can stream new tile uploads while it pans.
 * It measures what the compositing half of the hybrid would cost; the tiles' pixels (rendered by
 * Skia on the CPU) come from the bench. Nothing here is used by the app.
 */
class TileCompositeView(context: Context) : GLSurfaceView(context) {

    @Volatile var panX = 0f
    @Volatile var panY = 0f

    /** The tile pixels uploaded into every texture. */
    @Volatile var source: Bitmap? = null

    @Volatile var tilePx = 512
    @Volatile var cols = 1
    @Volatile var rows = 1

    /** How many tile textures are held resident; the grid reuses them round-robin. */
    @Volatile var textureCount = 1

    /** Tile uploads performed on the GL thread every frame, to cost streaming new tiles in while panning. */
    @Volatile var uploadsPerFrame = 0

    /** Set to make the GL thread rebuild its textures from [source] on the next frame. */
    @Volatile var rebuild = false

    /** Time for each full-size texture upload, ms (first-time uploads and streamed ones). */
    val uploadMs: MutableList<Double> = Collections.synchronizedList(ArrayList())

    /** Time the GL thread took to issue a frame and have the GPU finish it, ms. */
    val frameMs: MutableList<Double> = Collections.synchronizedList(ArrayList())

    @Volatile var drawnTiles = 0
    @Volatile var rendererName = ""

    private var program = 0
    private var uRect = 0
    private var uView = 0
    private var textures = IntArray(0)
    private var viewW = 1
    private var viewH = 1
    private var streamCursor = 0
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))
        position(0)
    }

    init {
        setEGLContextClientVersion(2)
        setRenderer(object : Renderer {
            override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
                rendererName = GLES20.glGetString(GLES20.GL_RENDERER) ?: ""
                program = link(VERTEX, FRAGMENT)
                uRect = GLES20.glGetUniformLocation(program, "uRect")
                uView = GLES20.glGetUniformLocation(program, "uView")
                textures = IntArray(0)
                rebuild = true
            }

            override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
                viewW = width
                viewH = height
                GLES20.glViewport(0, 0, width, height)
            }

            override fun onDrawFrame(gl: GL10?) {
                val t0 = System.nanoTime()
                if (rebuild) {
                    rebuild = false
                    rebuildTextures()
                }
                val bmp = source
                if (bmp != null && textures.isNotEmpty()) {
                    repeat(uploadsPerFrame) {
                        val i = streamCursor++ % textures.size
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[i])
                        val u0 = System.nanoTime()
                        GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bmp)
                        GLES20.glFinish()
                        uploadMs.add((System.nanoTime() - u0) / 1e6)
                    }
                }
                GLES20.glClearColor(1f, 1f, 1f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                if (textures.isNotEmpty()) draw()
                GLES20.glFinish()
                frameMs.add((System.nanoTime() - t0) / 1e6)
            }
        })
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    private fun rebuildTextures() {
        if (textures.isNotEmpty()) GLES20.glDeleteTextures(textures.size, textures, 0)
        val bmp = source ?: run { textures = IntArray(0); return }
        textures = IntArray(textureCount.coerceAtLeast(1))
        GLES20.glGenTextures(textures.size, textures, 0)
        for (t in textures) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            val u0 = System.nanoTime()
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
            GLES20.glFinish()
            uploadMs.add((System.nanoTime() - u0) / 1e6)
        }
    }

    private fun draw() {
        GLES20.glUseProgram(program)
        GLES20.glUniform2f(uView, viewW.toFloat(), viewH.toFloat())
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 8, quad)
        val tile = tilePx.toFloat()
        val ox = -(((panX % tile) + tile) % tile)
        val oy = -(((panY % tile) + tile) % tile)
        var n = 0
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val x = ox + c * tile
                val y = oy + r * tile
                if (x > viewW || y > viewH || x + tile < 0f || y + tile < 0f) continue
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[(r * cols + c) % textures.size])
                GLES20.glUniform4f(uRect, x, y, tile, tile)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                n++
            }
        }
        drawnTiles = n
    }

    private fun link(vs: String, fs: String): Int {
        fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            return s
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glBindAttribLocation(p, 0, "aPos")
        GLES20.glLinkProgram(p)
        return p
    }

    private companion object {
        const val VERTEX = """
            attribute vec2 aPos;
            uniform vec4 uRect;
            uniform vec2 uView;
            varying vec2 vUv;
            void main() {
                vec2 px = uRect.xy + aPos * uRect.zw;
                vec2 ndc = vec2(px.x / uView.x * 2.0 - 1.0, 1.0 - px.y / uView.y * 2.0);
                gl_Position = vec4(ndc, 0.0, 1.0);
                vUv = aPos;
            }
        """
        const val FRAGMENT = """
            precision mediump float;
            varying vec2 vUv;
            uniform sampler2D uTex;
            void main() { gl_FragColor = texture2D(uTex, vUv); }
        """
    }
}
