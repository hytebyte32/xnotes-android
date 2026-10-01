package com.xnotes.core.model

import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.pal.Renderer
import kotlin.math.cos
import kotlin.math.sin

/**
 * A pasted or inserted bitmap (spec 02 §5.2). Holds the encoded [image] source (decoded on demand
 * by the renderer) and a quarter-turn [orientation]; resize is aspect-locked.
 *
 * A turn is stored, never baked: ink and shapes rotate by moving their own points, but pixels
 * cannot be moved without resampling them, so the rotate handle only advances [angle] and the
 * renderer places the bitmap turned. [rect] therefore stays the image's own upright box, and
 * [bounds] is the box that box sweeps out once turned.
 */
class ImageItem(
    var image: ImageData,
    var rect: Rect,
    var orientation: Int = 0,
    /** Free rotation about [rect]'s centre, radians clockwise. */
    var angle: Double = 0.0,
    /**
     * The part of the picture that shows, as fractions (left, top, width, height) of the whole upright
     * image. The rest is kept, only hidden, so a crop can be widened again. [rect] is the visible
     * box; the whole image is [rect] scaled by 1 / these fractions.
     */
    var crop: Rect = FULL_CROP,
    /** Mirrored left to right and/or top to bottom, after the quarter turn and before the free turn. */
    var flipH: Boolean = false,
    var flipV: Boolean = false,
) : CanvasItem, Resizable {

    override val kind = KIND
    override val resizable = true
    override var locked = false

    override fun paint(r: Renderer) = r.drawImage(image, rect, orientation, angle, if (isCropped) crop else null, flipH, flipV)

    val isCropped: Boolean get() = crop != FULL_CROP

    /** A square about [fullCentre] that holds the whole image however it is turned, for a layer to cover. */
    fun fullBounds(): Rect {
        val c = fullCentre()
        val d = kotlin.math.hypot(fullW(), fullH()) / 2.0 + 2.0
        return Rect(c.x - d, c.y - d, 2.0 * d, 2.0 * d)
    }

    /** Size of the whole image (cropped parts included) at its current scale. */
    private fun fullW() = rect.w / crop.w
    private fun fullH() = rect.h / crop.h

    /** Where the whole image sits, centred on the point a crop never moves (the turn is about it too). */
    fun fullCentre(): Pt {
        val dx = (crop.x + crop.w / 2.0 - 0.5) * fullW()
        val dy = (crop.y + crop.h / 2.0 - 0.5) * fullH()
        val cs = cos(angle)
        val sn = sin(angle)
        val c = rect.center
        return Pt(c.x - (dx * cs - dy * sn), c.y - (dx * sn + dy * cs))
    }

    /** The whole image as an upright box about [fullCentre], to draw dimmed while cropping. */
    fun fullRect(): Rect {
        val c = fullCentre()
        return Rect(c.x - fullW() / 2.0, c.y - fullH() / 2.0, fullW(), fullH())
    }

    /**
     * Drag the crop sides flagged ([left], [right], [top], [bottom]; a corner flags two) to the point [p]: they follow it,
     * clamped to the image, while the picture itself stays where it is. Free per side, not
     * aspect-locked.
     */
    fun cropEdge(left: Boolean, right: Boolean, top: Boolean, bottom: Boolean, p: Pt, minSize: Double) {
        val fw = fullW()
        val fh = fullH()
        val c = fullCentre()
        val cs = cos(angle)
        val sn = sin(angle)
        val dx = p.x - c.x
        val dy = p.y - c.y
        val qx = dx * cs + dy * sn
        val qy = -dx * sn + dy * cs
        var l = (crop.x - 0.5) * fw
        var r = (crop.x + crop.w - 0.5) * fw
        var t = (crop.y - 0.5) * fh
        var b = (crop.y + crop.h - 0.5) * fh
        val m = minOf(minSize, fw, fh)
        if (left) l = qx.coerceIn(-fw / 2.0, r - m)
        if (right) r = qx.coerceIn(l + m, fw / 2.0)
        if (top) t = qy.coerceIn(-fh / 2.0, b - m)
        if (bottom) b = qy.coerceIn(t + m, fh / 2.0)
        crop = Rect(l / fw + 0.5, t / fh + 0.5, (r - l) / fw, (b - t) / fh)
        val mx = (l + r) / 2.0
        val my = (t + b) / 2.0
        val wx = c.x + (mx * cs - my * sn)
        val wy = c.y + (mx * sn + my * cs)
        rect = Rect(wx - (r - l) / 2.0, wy - (b - t) / 2.0, r - l, b - t)
    }

    override fun bounds(): Rect = if (angle == 0.0) rect else Rect.bounding(corners())

    /**
     * Mirror the picture about the visible box, which stays where it is. The crop is kept in the
     * mirrored picture's own fractions, so the same part of the picture stays on show, mirrored.
     */
    fun flip(horizontal: Boolean) {
        if (horizontal) {
            flipH = !flipH
            crop = Rect(1.0 - crop.x - crop.w, crop.y, crop.w, crop.h)
        } else {
            flipV = !flipV
            crop = Rect(crop.x, 1.0 - crop.y - crop.h, crop.w, crop.h)
        }
    }

    /** The turned rect's four corners, in the item's own space. */
    fun corners(): List<Pt> {
        val cs = cos(angle)
        val sn = sin(angle)
        val c = rect.center
        return listOf(
            Pt(rect.left, rect.top), Pt(rect.right, rect.top),
            Pt(rect.right, rect.bottom), Pt(rect.left, rect.bottom),
        ).map {
            val dx = it.x - c.x
            val dy = it.y - c.y
            Pt(c.x + dx * cs - dy * sn, c.y + dx * sn + dy * cs)
        }
    }

    /** [p] brought back into the upright rect's frame, so hit tests stay plain rectangle maths. */
    private fun unturn(p: Pt): Pt {
        if (angle == 0.0) return p
        val cs = cos(angle)
        val sn = sin(angle)
        val c = rect.center
        val dx = p.x - c.x
        val dy = p.y - c.y
        return Pt(c.x + dx * cs + dy * sn, c.y - dx * sn + dy * cs)
    }

    override fun translate(dx: Double, dy: Double) {
        rect = rect.translate(dx, dy)
    }

    override fun contains(p: Pt): Boolean = rect.contains(unturn(p))

    override fun centroid(): Pt = rect.center

    override fun intersectsCircle(cx: Double, cy: Double, radius: Double): Boolean =
        rect.distanceTo(unturn(Pt(cx, cy))) <= radius

    override fun geometry(): GeoHandle = RectHandle(rect)

    override fun setGeometry(handle: GeoHandle) {
        if (handle is RectHandle) rect = handle.rect
    }

    override fun snapshotGeometry(): GeometrySnapshot = ImageSnapshot(rect, angle, crop, flipH, flipV)

    override fun restoreGeometry(snap: GeometrySnapshot) {
        if (snap is ImageSnapshot) {
            rect = snap.rect
            angle = snap.angle
            crop = snap.crop
            flipH = snap.flipH
            flipV = snap.flipV
        }
    }

    /**
     * Scale the upright box about its mapped centre and add the transform's own turn to [angle].
     * The scale factors are the world axes' — an image already turned is stretched along its own
     * axes rather than sheared, since a bitmap has no shear to be drawn with.
     */
    override fun applyTransform(t: Affine) {
        val c = t.apply(rect.center)
        val w = rect.w * t.scaleX
        val h = rect.h * t.scaleY
        rect = Rect(c.x - w / 2.0, c.y - h / 2.0, w, h)
        angle += t.rotationAngle
    }

    companion object {
        const val KIND = "image"
        val FULL_CROP = Rect(0.0, 0.0, 1.0, 1.0)
    }
}

/** Snapshot of an image's transformable geometry. */
private data class ImageSnapshot(val rect: Rect, val angle: Double, val crop: Rect, val flipH: Boolean, val flipV: Boolean) : GeometrySnapshot
