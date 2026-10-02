package com.xnotes.format

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.LabelItem
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.model.Stroke
import com.xnotes.core.model.TextItem
import com.xnotes.core.pal.FontFace
import com.xnotes.core.pal.ImageCodec
import com.xnotes.core.pal.TextMeasurer
import com.xnotes.core.tools.ShapeKind
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig
import com.xnotes.core.tools.ToolDefaults
import java.io.File

/**
 * Item (de)serialization for the unified `.xdoc` format: every kind a page or a region can hold,
 * strokes, images, shapes, text boxes and measurement labels, in the one encoding both legacy
 * formats already use. It is a copy of the private item code in [DocumentCodec] and [CanvasCodec],
 * which stay frozen as the `.xnote` / `.xcanvas` importers and exporters until those formats are
 * retired, so a legacy file's bytes can never change under a refactor.
 *
 * [textMeasurer] is needed only to rebuild text boxes; without one they are skipped on read.
 */
internal class XDocItems(
    private val imageCodec: ImageCodec,
    private val textMeasurer: TextMeasurer?,
) {

    /** An image item parsed before its asset entry has streamed out of the zip. */
    class PendingImage(
        val index: Int,
        val asset: String,
        val rect: Rect?,
        val srcW: Int,
        val srcH: Int,
        val orientation: Int,
        val angle: Double,
        val locked: Boolean,
        val crop: Rect? = null,
        val flipH: Boolean = false,
        val flipV: Boolean = false,
    )


    /** One item as the `.xdoc` writes it; an image takes its name from [assets] positionally. */
    fun writeItem(j: JsonWrite, item: CanvasItem, assets: List<Pair<String, File>>, nextAsset: IntArray) {
        when (item) {
            is Stroke -> writeStroke(j, item)
            is ImageItem -> {
                // The name was decided by the same walk of these items that named the entries.
                val name = assets.getOrNull(nextAsset[0]++)?.first ?: return
                writeImage(j, item, name)
            }
            is TextItem -> writeText(j, item)
            is ShapeItem -> writeShape(j, item)
            is LabelItem -> writeLabel(j, item)
            else -> {} // unrecognized kind: not written
        }
    }

    /** A measurement label: its text, top-left, glyph height and colour. */
    private fun writeLabel(j: JsonWrite, l: LabelItem) {
        j.beginObject()
        j.name("kind").value(LabelItem.KIND)
        j.name("text").value(l.text)
        j.name("start").beginArray().value(l.pos.x).value(l.pos.y).endArray()
        j.name("label_h").value(l.height)
        j.name("stroke_rgba")
        writeRgba(j, l.rgba)
        if (l.locked) j.name("locked").value(true)
        j.endObject()
    }

    private fun writeStroke(j: JsonWrite, s: Stroke) {
        // Per-sample time is only meaningful to the speed pen, so it's written as an
        // optional 4th element only then — every other stroke serializes unchanged.
        val withTime = s.config.speedStrength > 0.0
        j.beginObject()
        j.name("kind").value(Stroke.KIND)
        j.name("tool").value(s.tool.id)
        j.name("config").beginObject()
        j.name("base_width").value(s.config.baseWidth)
        j.name("pressure_enabled").value(s.config.pressureEnabled)
        j.name("pressure_min_factor").value(s.config.pressureMinFactor)
        j.name("direction_strength").value(s.config.directionStrength)
        j.name("rgba")
        writeRgba(j, s.config.rgba)
        // New style fields are written only when set, so a plain pen/calligraphy
        // stroke's config is byte-for-byte what older versions wrote.
        if (s.config.speedStrength != 0.0) j.name("speed_strength").value(s.config.speedStrength)
        if (s.config.taperEnabled) {
            j.name("taper_enabled").value(true)
            j.name("taper_min_factor").value(s.config.taperMinFactor)
        }
        if (s.config.neon) {
            j.name("neon").value(true)
            j.name("neon_strength").value(s.config.neonStrength)
        }
        // Dash runs matter only to the dashed pen, so a plain stroke's config is unchanged.
        if (s.tool == Tool.DASHED) {
            j.name("dash_length").value(s.config.dashLength)
            j.name("dash_gap").value(s.config.dashGap)
        }
        // Strength matters only to the highlighter; baked per-stroke so a note reopens unchanged.
        if (s.tool == Tool.HIGHLIGHTER) {
            j.name("highlighter_alpha").value(s.config.highlighterAlpha)
            if (s.config.highlighterInverse) j.name("highlighter_inverse").value(true)
        }
        j.endObject()
        // Samples are ~97% of a dense manifest's bytes, so they go out through [JsonWrite.samplePoint], which rounds them: 0.01
        // content px (2dp) and 0.001 pressure (3dp) are far below anything visible, and a
        // 500 Hz note shrinks ~2.3x. Rounding is idempotent, so re-saves stay byte-stable.
        j.name("samples").beginArray()
        for (sm in s.samples) j.samplePoint(sm.x, sm.y, sm.pressure, if (withTime) sm.t else null)
        j.endArray()
        // The speed pen's gesture-speed scale (zoom ÷ density at pen-down) reconstructs its
        // width on reload; written alongside the per-sample times, only for that tool.
        if (withTime) j.name("speed_scale").value(s.speedScale)
        // The zoom the stroke was drawn at, as the scale on the ink low-pass lengths, so it
        // re-smooths on load exactly as it did under the pen. Written only when it is not the
        // 100%-zoom default, which is most ink.
        if (s.smoothScale != 1.0) j.name("smooth_scale").value(s.smoothScale)
        // Straight-line strokes must reload un-smoothed, else the EMA pulls their far end inward.
        if (s.straight) j.name("straight").value(true)
        if (s.locked) j.name("locked").value(true)
        j.endObject()
    }

    private fun writeImage(j: JsonWrite, item: ImageItem, assetName: String) {
        j.beginObject()
        j.name("kind").value(ImageItem.KIND)
        j.name("asset").value(assetName)
        j.name("rect").beginArray().value(item.rect.x).value(item.rect.y).value(item.rect.w).value(item.rect.h).endArray()
        j.name("src_w").value(item.image.width)
        j.name("src_h").value(item.image.height)
        // Additive fields: written only when turned, so older readers stay compatible.
        if (item.orientation != 0) j.name("orientation").value(item.orientation)
        if (item.angle != 0.0) j.name("angle").value(item.angle)
        if (item.isCropped) {
            j.name("crop").beginArray().value(item.crop.x).value(item.crop.y).value(item.crop.w).value(item.crop.h).endArray()
        }
        if (item.flipH) j.name("flip_h").value(true)
        if (item.flipV) j.name("flip_v").value(true)
        if (item.locked) j.name("locked").value(true)
        j.endObject()
    }

    private fun writeText(j: JsonWrite, t: TextItem) {
        j.beginObject()
        j.name("kind").value(TextItem.KIND)
        j.name("pos").beginArray().value(t.pos.x).value(t.pos.y).endArray()
        j.name("width").value(t.width)
        j.name("text").value(t.text)
        j.name("rgba")
        writeRgba(j, t.rgba)
        j.name("point_size").value(t.pointSize)
        // Additive fields: written only when set, so older readers stay compatible
        // and notes that use neither serialize exactly as before.
        if (t.height > 0.0) j.name("height").value(t.height)
        if (t.face != TextItem.DEFAULT_FACE) j.name("font_face").value(t.face.id)
        if (t.locked) j.name("locked").value(true)
        j.endObject()
    }

    private fun writeShape(j: JsonWrite, s: ShapeItem) {
        j.beginObject()
        j.name("kind").value(ShapeItem.KIND)
        j.name("shape").value(s.shape.id)
        j.name("start").beginArray().value(s.start.x).value(s.start.y).endArray()
        j.name("end").beginArray().value(s.end.x).value(s.end.y).endArray()
        j.name("stroke_rgba")
        writeRgba(j, s.strokeRgba)
        j.name("stroke_width").value(s.strokeWidth)
        j.name("fill_rgba")
        s.fillRgba?.let { writeRgba(j, it) } ?: j.nullValue()
        // Polygon/polyline carry their vertices (absolute content px); other kinds omit them.
        s.vertices()?.let { verts ->
            j.name("points").beginArray()
            for (p in verts) j.beginArray().value(p.x).value(p.y).endArray()
            j.endArray()
        }
        // Glow is additive: a plain shape serializes exactly as before.
        if (s.neon) {
            j.name("neon").value(true)
            j.name("neon_strength").value(s.neonStrength)
        }
        // Dash is additive too: written only on dashed shapes.
        if (s.dashed) {
            j.name("dashed").value(true)
            j.name("dash_length").value(s.dashLength)
            j.name("dash_gap").value(s.dashGap)
        }
        if (s.isHighlighter) {
            j.name("hl_alpha").value(s.highlighterAlpha)
            if (s.highlighterInverse) j.name("hl_inverse").value(true)
        }
        if (s.locked) j.name("locked").value(true)
        j.endObject()
    }

    // --- reading ---

    fun parseItems(p: JsonPull, items: MutableList<CanvasItem>, pending: MutableList<PendingImage>) {
        if (p.peek() != JsonPull.Token.BEGIN_ARRAY) return p.skipValue()
        p.beginArray()
        while (p.hasNext()) parseItem(p, items, pending)
        p.endArray()
    }


    /** Union of every kind's fields, so an item parses in one pass whatever its key order. */
    private class ItemScratch {
        var locked = false
        var kind: String? = null
        var tool: String? = null
        var config: ConfigScratch? = null
        var samples: RawSamples? = null
        var speedScale = 1.0
        var smoothScale = 1.0
        var straight = false
        var asset: String? = null
        var rect: Rect? = null
        var srcW = 0
        var srcH = 0
        var orientation = 0
        var angle = 0.0
        var crop: Rect? = null
        var flipH = false
        var flipV = false
        var pos: Pt? = null
        var width = TextItem.DEFAULT_WIDTH
        var height = 0.0
        var text = ""
        var rgba: Rgba? = null
        var pointSize = TextItem.DEFAULT_POINT_SIZE
        var fontFace = ""
        var shape: String? = null
        var start: Pt? = null
        var end: Pt? = null
        var strokeRgba: Rgba? = null
        var strokeWidth = 3.0
        var fillRgba: Rgba? = null
        var points: List<Pt>? = null
        var neon = false
        var neonStrength = 0.6
        var dashed = false
        var dashLength = 10.0
        var dashGap = 8.0
        var hlAlpha = 0.0
        var hlInverse = false
        var labelH = 0.0
    }

    /** Stroke config fields as written; null = absent, so defaults resolve exactly as before. */
    private class ConfigScratch {
        var baseWidth: Double? = null
        var pressureEnabled: Boolean? = null
        var pressureMinFactor: Double? = null
        var directionStrength: Double? = null
        var rgba: Rgba? = null
        var speedStrength: Double? = null
        var taperEnabled: Boolean? = null
        var taperLength: Double? = null
        var taperMinFactor: Double? = null
        var neon: Boolean? = null
        var neonStrength: Double? = null
        var dashLength: Double? = null
        var dashGap: Double? = null
        var highlighterAlpha: Double? = null
        var highlighterInverse: Boolean? = null
    }
    private fun parseItem(p: JsonPull, items: MutableList<CanvasItem>, pending: MutableList<PendingImage>) {
        if (p.peek() != JsonPull.Token.BEGIN_OBJECT) return p.skipValue()
        val s = ItemScratch()
        p.beginObject()
        while (p.hasNext()) {
            when (p.nextName()) {
                "kind" -> s.kind = stringOr(p, "")
                "tool" -> s.tool = stringOr(p, "")
                "config" -> s.config = parseConfig(p)
                "samples" -> s.samples = parseSamples(p)
                "speed_scale" -> s.speedScale = doubleOr(p, 1.0)
                "smooth_scale" -> s.smoothScale = doubleOr(p, 1.0)
                "straight" -> s.straight = boolOr(p, false)
                "asset" -> s.asset = stringOr(p, "")
                "rect" -> s.rect = rectOrNull(p)
                "src_w" -> s.srcW = intOr(p, 0)
                "src_h" -> s.srcH = intOr(p, 0)
                "orientation" -> s.orientation = intOr(p, 0)
                "angle" -> s.angle = doubleOr(p, 0.0)
                "crop" -> s.crop = rectOrNull(p)
                "flip_h" -> s.flipH = boolOr(p, false)
                "flip_v" -> s.flipV = boolOr(p, false)
                "pos" -> s.pos = ptOrNull(p)
                "width" -> s.width = doubleOr(p, TextItem.DEFAULT_WIDTH)
                "height" -> s.height = doubleOr(p, 0.0)
                "text" -> s.text = stringOr(p, "")
                "rgba" -> s.rgba = rgbaOrNull(p)
                "point_size" -> s.pointSize = doubleOr(p, TextItem.DEFAULT_POINT_SIZE)
                "font_face" -> s.fontFace = stringOr(p, "")
                "shape" -> s.shape = stringOr(p, "")
                "start" -> s.start = ptOrNull(p)
                "end" -> s.end = ptOrNull(p)
                "stroke_rgba" -> s.strokeRgba = rgbaOrNull(p)
                "stroke_width" -> s.strokeWidth = doubleOr(p, 3.0)
                "fill_rgba" -> s.fillRgba = rgbaOrNull(p)
                "points" -> s.points = pointsOrNull(p)
                "neon" -> s.neon = boolOr(p, false)
                "neon_strength" -> s.neonStrength = doubleOr(p, 0.6)
                "dashed" -> s.dashed = boolOr(p, false)
                "dash_length" -> s.dashLength = doubleOr(p, 10.0)
                "dash_gap" -> s.dashGap = doubleOr(p, 8.0)
                "hl_alpha" -> s.hlAlpha = doubleOr(p, 0.0)
                "hl_inverse" -> s.hlInverse = boolOr(p, false)
                "label_h" -> s.labelH = doubleOr(p, 0.0)
                "locked" -> s.locked = boolOr(p, false)
                else -> p.skipValue()
            }
        }
        p.endObject()
        val before = items.size
        when (s.kind) {
            Stroke.KIND -> items.add(buildStroke(s))
            ImageItem.KIND -> {
                val asset = s.asset
                if (!asset.isNullOrEmpty()) {
                    pending.add(
                        PendingImage(
                            items.size + pending.size, asset, s.rect, s.srcW, s.srcH,
                            s.orientation, s.angle, s.locked, s.crop, s.flipH, s.flipV,
                        ),
                    )
                }
            }
            TextItem.KIND -> textMeasurer?.let { m ->
                items.add(
                    TextItem(
                        pos = s.pos ?: Pt.ZERO,
                        width = s.width,
                        height = s.height,
                        text = s.text,
                        rgba = s.rgba ?: TextItem.DEFAULT_COLOR,
                        pointSize = s.pointSize,
                        face = FontFace.fromId(s.fontFace),
                        measurer = m,
                    ),
                )
            }
            ShapeItem.KIND -> items.add(buildShape(s))
            LabelItem.KIND -> if (s.text.isNotEmpty() && s.labelH > 0.0) {
                items.add(LabelItem(s.start ?: Pt.ZERO, s.text, s.labelH, s.strokeRgba ?: LabelItem.DEFAULT_COLOR))
            }
            else -> {} // unrecognized kind: skipped (forgiving)
        }
        // Absent on every note written before locking existed, which reads back as unlocked.
        if (s.locked && items.size > before) items[before].locked = true
    }

    private fun buildStroke(s: ItemScratch): Stroke {
        val tool = Tool.fromId(s.tool) ?: Tool.PEN
        val c = s.config
        val def = ToolConfig()
        val config = ToolConfig(
            baseWidth = c?.baseWidth ?: def.baseWidth,
            pressureEnabled = c?.pressureEnabled ?: def.pressureEnabled,
            pressureMinFactor = c?.pressureMinFactor ?: def.pressureMinFactor,
            directionStrength = c?.directionStrength ?: def.directionStrength,
            rgba = c?.rgba ?: def.rgba,
            speedStrength = c?.speedStrength ?: def.speedStrength,
            taperEnabled = c?.let { it.taperEnabled ?: ((it.taperLength ?: 0.0) > 0.0) } ?: def.taperEnabled,
            // Absent on legacy taper strokes -> the legacy tip, so old tapers reload tapered.
            taperMinFactor = c?.taperMinFactor ?: ToolDefaults.LEGACY_TAPER_TIP,
            neon = c?.neon ?: def.neon,
            neonStrength = c?.neonStrength ?: def.neonStrength,
            dashLength = c?.dashLength ?: def.dashLength,
            dashGap = c?.dashGap ?: def.dashGap,
            // Absent on legacy highlighter strokes -> the historical 0.35, so they reload unchanged.
            highlighterAlpha = c?.highlighterAlpha ?: def.highlighterAlpha,
            highlighterInverse = c?.highlighterInverse ?: def.highlighterInverse,
        )
        val stroke = Stroke(tool, config, emptyList(), s.speedScale, s.straight, s.smoothScale)
        s.samples?.let { stroke.setSamples(it.xs, it.ys, it.ps, it.ts, it.n) }
        return stroke
    }

    private fun buildShape(s: ItemScratch): ShapeItem {
        val kind = ShapeKind.fromId(s.shape)
        val strokeRgba = s.strokeRgba ?: Rgba(0, 230, 118, 255)
        s.points?.let { verts ->
            return ShapeItem.poly(
                kind, verts, strokeRgba, s.strokeWidth, s.fillRgba, s.neon, s.neonStrength,
                s.dashed, s.dashLength, s.dashGap, s.hlAlpha, s.hlInverse,
            )
        }
        return ShapeItem(
            shape = kind,
            start = s.start ?: Pt.ZERO,
            end = s.end ?: Pt.ZERO,
            strokeRgba = strokeRgba,
            strokeWidth = s.strokeWidth,
            fillRgba = s.fillRgba,
            neon = s.neon,
            neonStrength = s.neonStrength,
            dashed = s.dashed,
            dashLength = s.dashLength,
            dashGap = s.dashGap,
            highlighterAlpha = s.hlAlpha,
            highlighterInverse = s.hlInverse,
        )
    }

    private fun parseConfig(p: JsonPull): ConfigScratch? {
        if (p.peek() != JsonPull.Token.BEGIN_OBJECT) {
            p.skipValue()
            return null
        }
        val c = ConfigScratch()
        p.beginObject()
        while (p.hasNext()) {
            when (p.nextName()) {
                "base_width" -> c.baseWidth = doubleOrNull(p)
                "pressure_enabled" -> c.pressureEnabled = boolOrNull(p)
                "pressure_min_factor" -> c.pressureMinFactor = doubleOrNull(p)
                "direction_strength" -> c.directionStrength = doubleOrNull(p)
                "rgba" -> c.rgba = rgbaOrNull(p)
                "speed_strength" -> c.speedStrength = doubleOrNull(p)
                "taper_enabled" -> c.taperEnabled = boolOrNull(p)
                "taper_length" -> c.taperLength = doubleOrNull(p)
                "taper_min_factor" -> c.taperMinFactor = doubleOrNull(p)
                "neon" -> c.neon = boolOrNull(p)
                "neon_strength" -> c.neonStrength = doubleOrNull(p)
                "dash_length" -> c.dashLength = doubleOrNull(p)
                "dash_gap" -> c.dashGap = doubleOrNull(p)
                "highlighter_alpha" -> c.highlighterAlpha = doubleOrNull(p)
                "highlighter_inverse" -> c.highlighterInverse = boolOrNull(p)
                else -> p.skipValue()
            }
        }
        p.endObject()
        return c
    }

    private fun parseSamples(p: JsonPull): RawSamples? {
        if (p.peek() != JsonPull.Token.BEGIN_ARRAY) {
            p.skipValue()
            return null
        }
        val out = RawSamples()
        val tuple = DoubleArray(4)
        p.beginArray()
        while (p.hasNext()) {
            if (p.peek() != JsonPull.Token.BEGIN_ARRAY) {
                p.skipValue()
                continue
            }
            // NaN back from [JsonPull.nextSample] means the slot held nothing readable, which is
            // not the same as a zero: pressure then defaults to full, and the 4th element
            // (relative ms, only speed-pen strokes carry it) to none.
            p.nextSample(tuple)
            out.add(
                if (tuple[0].isNaN()) 0.0 else tuple[0],
                if (tuple[1].isNaN()) 0.0 else tuple[1],
                if (tuple[2].isNaN()) 1.0 else tuple[2],
                if (tuple[3].isNaN()) 0.0 else tuple[3],
            )
        }
        p.endArray()
        return out
    }

    fun materializeImage(spec: PendingImage, imageFiles: Map<String, File>): ImageItem? {
        val file = imageFiles[spec.asset] ?: return null
        var w = spec.srcW
        var h = spec.srcH
        if (w <= 0 || h <= 0) {
            // Legacy notes (and any without stored dims): read the native size without decoding pixels.
            val probed = imageCodec.probeFile(file.path) ?: return null
            w = probed.width
            h = probed.height
        }
        val rect = spec.rect ?: Rect(0.0, 0.0, w.toDouble(), h.toDouble())
        return ImageItem(ImageData(file, w, h), rect, spec.orientation, spec.angle, spec.crop?.takeIf { it.w > 0.0 && it.h > 0.0 } ?: ImageItem.FULL_CROP, spec.flipH, spec.flipV)
            .also { it.locked = spec.locked }
    }

    // --- streaming value helpers (mirroring org.json's forgiving opt* coercions) ---
}
