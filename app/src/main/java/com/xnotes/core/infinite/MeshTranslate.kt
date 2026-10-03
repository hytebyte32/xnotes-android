package com.xnotes.core.infinite

/**
 * Moving meshed ink to another place in the plane. The paged view meshes each item in its page's own
 * space and files it displaced to where the page sits, so ink stays exactly where the page is whatever
 * the layout does.
 *
 * Offsets are displacements from the line, not positions, so they are untouched; indices are relative
 * to the mesh and also untouched.
 */
fun MeshData.translated(dx: Double, dy: Double): MeshData {
    if (dx == 0.0 && dy == 0.0) return this
    val p = DoubleArray(positions.size)
    var i = 0
    while (i < p.size) {
        p[i] = positions[i] + dx
        p[i + 1] = positions[i + 1] + dy
        i += 2
    }
    return MeshData(p, offsets, indices, colors)
}

fun MeshPart.translated(dx: Double, dy: Double): MeshPart =
    if (dx == 0.0 && dy == 0.0) this else MeshPart(mesh.translated(dx, dy), color, pass, glow)

fun MeshedItem.translated(dx: Double, dy: Double): MeshedItem =
    if (dx == 0.0 && dy == 0.0) this
    else MeshedItem(parts.map { it.translated(dx, dy) }, bounds.translate(dx, dy), minHalfWidth)

/**
 * A view rotated in quarter turns carries each page's space onto the plane turned and shifted. These
 * apply that to meshed ink: the turn (clockwise on screen, as the paged view does it) first, then the
 * shift. [rot] is 0..3 quarter turns; offsets are directions, so they turn but do not shift.
 */
fun rotX(rot: Int, x: Double, y: Double): Double = when (rot and 3) {
    1 -> -y
    2 -> -x
    3 -> y
    else -> x
}

fun rotY(rot: Int, x: Double, y: Double): Double = when (rot and 3) {
    1 -> x
    2 -> -y
    3 -> -x
    else -> y
}

fun com.xnotes.core.geometry.Rect.rotatedQuarter(rot: Int): com.xnotes.core.geometry.Rect {
    if (rot and 3 == 0) return this
    val xs = doubleArrayOf(left, right, left, right)
    val ys = doubleArrayOf(top, top, bottom, bottom)
    var l = Double.MAX_VALUE
    var t = Double.MAX_VALUE
    var r = -Double.MAX_VALUE
    var b = -Double.MAX_VALUE
    for (i in 0 until 4) {
        val x = rotX(rot, xs[i], ys[i])
        val y = rotY(rot, xs[i], ys[i])
        if (x < l) l = x
        if (x > r) r = x
        if (y < t) t = y
        if (y > b) b = y
    }
    return com.xnotes.core.geometry.Rect(l, t, r - l, b - t)
}

fun MeshData.transformed(rot: Int, dx: Double, dy: Double): MeshData {
    if (rot and 3 == 0) return translated(dx, dy)
    val p = DoubleArray(positions.size)
    val o = DoubleArray(offsets.size)
    var i = 0
    while (i < p.size) {
        val x = positions[i]
        val y = positions[i + 1]
        p[i] = rotX(rot, x, y) + dx
        p[i + 1] = rotY(rot, x, y) + dy
        i += 2
    }
    i = 0
    while (i < o.size) {
        val x = offsets[i]
        val y = offsets[i + 1]
        o[i] = rotX(rot, x, y)
        o[i + 1] = rotY(rot, x, y)
        i += 2
    }
    return MeshData(p, o, indices, colors)
}

fun MeshedItem.transformed(rot: Int, dx: Double, dy: Double): MeshedItem =
    if (rot and 3 == 0) translated(dx, dy)
    else MeshedItem(parts.map { MeshPart(it.mesh.transformed(rot, dx, dy), it.color, it.pass, it.glow) }, bounds.rotatedQuarter(rot).translate(dx, dy), minHalfWidth)
