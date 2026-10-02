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
