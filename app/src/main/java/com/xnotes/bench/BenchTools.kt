package com.xnotes.bench

import android.os.Debug
import android.os.SystemClock
import android.view.Choreographer
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume

/** The next display frame's timestamp, in nanoseconds. */
suspend fun awaitFrame(): Long = suspendCancellableCoroutine { c ->
    Choreographer.getInstance().postFrameCallback { c.resume(it) }
}

/** Summary of a list of millisecond samples. */
fun summarize(samples: List<Double>, refreshMs: Double): JSONObject {
    val o = JSONObject()
    o.put("n", samples.size)
    if (samples.isEmpty()) return o
    val s = samples.sorted()
    fun q(p: Double) = s[((s.size - 1) * p).toInt()]
    o.put("mean_ms", round(samples.average()))
    o.put("p50_ms", round(q(0.50)))
    o.put("p95_ms", round(q(0.95)))
    o.put("p99_ms", round(q(0.99)))
    o.put("max_ms", round(s.last()))
    o.put("over_1_5_refresh_pct", round(100.0 * samples.count { it > refreshMs * 1.5 } / samples.size))
    return o
}

fun round(v: Double): Double = Math.round(v * 100.0) / 100.0

/** Memory the process holds right now, in megabytes. */
fun memorySnapshot(): JSONObject {
    val mi = Debug.MemoryInfo()
    Debug.getMemoryInfo(mi)
    val rt = Runtime.getRuntime()
    return JSONObject()
        .put("pss_mb", mi.totalPss / 1024)
        .put("java_mb", (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024))
        .put("native_mb", Debug.getNativeHeapAllocatedSize() / (1024 * 1024))
}

/** Two fingers dispatched straight into [target], so the real gesture code runs. Coordinates are view-local. */
class FingerSynth(private val target: View) {
    private var downTime = 0L
    private var down = false
    private val props = Array(2) {
        MotionEvent.PointerProperties().apply { id = it; toolType = MotionEvent.TOOL_TYPE_FINGER }
    }
    private val coords = Array(2) { MotionEvent.PointerCoords().apply { pressure = 1f; size = 1f } }

    private fun send(action: Int, count: Int) {
        val ev = MotionEvent.obtain(
            downTime, SystemClock.uptimeMillis(), action, count, props, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        target.dispatchTouchEvent(ev)
        ev.recycle()
    }

    private fun set(x0: Float, y0: Float, x1: Float, y1: Float) {
        coords[0].x = x0; coords[0].y = y0
        coords[1].x = x1; coords[1].y = y1
    }

    fun down(x0: Float, y0: Float, x1: Float, y1: Float) {
        downTime = SystemClock.uptimeMillis()
        set(x0, y0, x1, y1)
        send(MotionEvent.ACTION_DOWN, 1)
        send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2)
        down = true
    }

    fun move(x0: Float, y0: Float, x1: Float, y1: Float) {
        if (!down) return
        set(x0, y0, x1, y1)
        send(MotionEvent.ACTION_MOVE, 2)
    }

    fun up() {
        if (!down) return
        send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2)
        send(MotionEvent.ACTION_UP, 1)
        down = false
    }
}

/** One stylus dispatched straight into [target]; [dispatch] times are collected by the caller. */
class PenSynth(private val target: View) {
    private var downTime = 0L
    private val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS })
    private val coords = arrayOf(MotionEvent.PointerCoords().apply { pressure = 0.6f; size = 0.1f })

    private fun send(action: Int, x: Float, y: Float): Long {
        coords[0].x = x
        coords[0].y = y
        val ev = MotionEvent.obtain(
            downTime, SystemClock.uptimeMillis(), action, 1, props, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS or InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        val t0 = System.nanoTime()
        target.dispatchTouchEvent(ev)
        val took = System.nanoTime() - t0
        ev.recycle()
        return took
    }

    fun down(x: Float, y: Float): Long {
        downTime = SystemClock.uptimeMillis()
        return send(MotionEvent.ACTION_DOWN, x, y)
    }

    fun move(x: Float, y: Float): Long = send(MotionEvent.ACTION_MOVE, x, y)
    fun up(x: Float, y: Float): Long = send(MotionEvent.ACTION_UP, x, y)
}

/**
 * The OS's own account of where this process's memory is, for finding what a PSS number is made of:
 * the summary buckets (java heap, native heap, graphics, private other, system), the anon / file /
 * shared split from smaps_rollup, and the biggest mappings by name (the GPU driver, the shared-memory
 * geometry mirrors, the malloc arenas) summed over /proc/self/smaps. Megabytes throughout.
 */
fun memoryBreakdown(): JSONObject {
    val o = JSONObject()
    val mi = Debug.MemoryInfo()
    Debug.getMemoryInfo(mi)
    val summary = JSONObject()
    for ((k, v) in mi.memoryStats) summary.put(k.removePrefix("summary."), (v.toLongOrNull() ?: 0L) / 1024)
    o.put("summary_mb", summary)
    o.put("total_pss_mb", mi.totalPss / 1024)
    val rt = Runtime.getRuntime()
    o.put("java_used_mb", (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024))
    o.put("native_alloc_mb", Debug.getNativeHeapAllocatedSize() / (1024 * 1024))
    try {
        val roll = JSONObject()
        java.io.File("/proc/self/smaps_rollup").forEachLine { l ->
            for (key in listOf("Rss", "Pss", "Pss_Anon", "Pss_File", "Pss_Shmem")) {
                if (l.startsWith("$key:")) roll.put(key.lowercase() + "_mb", l.filter { it.isDigit() }.toLong() / 1024)
            }
        }
        o.put("rollup", roll)
    } catch (_: Throwable) {
    }
    try {
        val header = Regex("^[0-9a-f]+-[0-9a-f]+ [rwxps-]{4} \\S+ \\S+ \\S+\\s*(.*)$")
        val byName = HashMap<String, Long>()
        var name = ""
        java.io.File("/proc/self/smaps").bufferedReader().useLines { lines ->
            for (l in lines) {
                val m = header.matchEntire(l)
                if (m != null) {
                    name = m.groupValues[1].trim().removeSuffix("(deleted)").trim().ifEmpty { "[anon]" }
                } else if (l.startsWith("Pss:")) {
                    byName.merge(name, l.filter { it.isDigit() }.toLong(), Long::plus)
                }
            }
        }
        val top = org.json.JSONArray()
        for ((n, kb) in byName.entries.sortedByDescending { it.value }.take(10)) {
            top.put(JSONObject().put("mapping", n).put("pss_mb", kb / 1024))
        }
        o.put("top_mappings", top)
    } catch (t: Throwable) {
        o.put("smaps_error", t.toString())
    }
    return o
}
