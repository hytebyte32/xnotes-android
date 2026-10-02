package com.xnotes.format

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.PageMargins
import com.xnotes.core.model.PagePattern
import com.xnotes.core.model.PageStyle
import com.xnotes.core.model.PageTemplates
import com.xnotes.core.model.Rgba

// JSON value helpers and the page style/margin (de)serializers shared by the .xdoc codec. These are
// copies of the private helpers in DocumentCodec, which stays frozen as the .xnote importer/exporter.

internal fun doubleOr(p: JsonPull, def: Double): Double = doubleOrNull(p) ?: def

internal fun doubleOrNull(p: JsonPull): Double? = when (p.peek()) {
    JsonPull.Token.NUMBER -> p.nextDouble()
    JsonPull.Token.STRING -> p.nextString().toDoubleOrNull()
    else -> {
        p.skipValue()
        null
    }
}

internal fun intOr(p: JsonPull, def: Int): Int = intOrNull(p) ?: def

internal fun intOrNull(p: JsonPull): Int? = when (p.peek()) {
    JsonPull.Token.NUMBER -> p.nextInt()
    JsonPull.Token.STRING -> p.nextString().let { it.toIntOrNull() ?: it.toDoubleOrNull()?.toInt() }
    else -> {
        p.skipValue()
        null
    }
}

internal fun boolOr(p: JsonPull, def: Boolean): Boolean = boolOrNull(p) ?: def

internal fun boolOrNull(p: JsonPull): Boolean? = when (p.peek()) {
    JsonPull.Token.BOOLEAN -> p.nextBoolean()
    JsonPull.Token.STRING -> when (p.nextString().lowercase()) {
        "true" -> true
        "false" -> false
        else -> null
    }
    else -> {
        p.skipValue()
        null
    }
}

internal fun stringOr(p: JsonPull, def: String): String = stringOrNull(p) ?: def

internal fun stringOrNull(p: JsonPull): String? = when (p.peek()) {
    JsonPull.Token.STRING -> p.nextString()
    else -> {
        p.skipValue()
        null
    }
}

internal fun rgbaOrNull(p: JsonPull): Rgba? {
    if (p.peek() != JsonPull.Token.BEGIN_ARRAY) {
        p.skipValue()
        return null
    }
    val channels = ArrayList<Int>(4)
    p.beginArray()
    while (p.hasNext()) channels.add(intOr(p, 0))
    p.endArray()
    return Rgba.fromList(channels)
}

internal fun ptOrNull(p: JsonPull): Pt? {
    if (p.peek() != JsonPull.Token.BEGIN_ARRAY) {
        p.skipValue()
        return null
    }
    var count = 0
    var x = 0.0
    var y = 0.0
    p.beginArray()
    while (p.hasNext()) {
        when (count) {
            0 -> x = doubleOr(p, 0.0)
            1 -> y = doubleOr(p, 0.0)
            else -> p.skipValue()
        }
        count++
    }
    p.endArray()
    return if (count >= 2) Pt(x, y) else null
}

internal fun rectOrNull(p: JsonPull): Rect? {
    if (p.peek() != JsonPull.Token.BEGIN_ARRAY) {
        p.skipValue()
        return null
    }
    val v = DoubleArray(4)
    var count = 0
    p.beginArray()
    while (p.hasNext()) {
        if (count < 4) v[count] = doubleOr(p, 0.0) else p.skipValue()
        count++
    }
    p.endArray()
    return if (count >= 4) Rect(v[0], v[1], v[2], v[3]) else null
}

internal fun pointsOrNull(p: JsonPull): List<Pt>? {
    if (p.peek() != JsonPull.Token.BEGIN_ARRAY) {
        p.skipValue()
        return null
    }
    val out = ArrayList<Pt>()
    p.beginArray()
    while (p.hasNext()) ptOrNull(p)?.let { out.add(it) }
    p.endArray()
    return if (out.size >= 2) out else null
}


internal fun writeRgba(j: JsonWrite, c: Rgba) {
    j.beginArray().value(c.r).value(c.g).value(c.b).value(c.a).endArray()
}

/** A page/document style, written only when something is overridden (forgiving: fields are optional). */
internal fun writeStyle(j: JsonWrite, s: PageStyle) {
    if (s.isEmpty) return
    j.name("style").beginObject()
    s.pageColor?.let {
        j.name("page_color")
        writeRgba(j, it)
    }
    s.template?.let { t ->
        // Built-in rulings keep the old "pattern" name, so older builds still draw them.
        if (t == PageTemplates.NONE || PageTemplates.isBuiltIn(t)) j.name("pattern").value(t) else j.name("template").value(t)
    }
    s.patternColor?.let {
        j.name("pattern_color")
        writeRgba(j, it)
    }
    s.spacing?.let { j.name("spacing").value(it) }
    s.accentColor?.let {
        j.name("accent_color")
        writeRgba(j, it)
    }
    s.params?.takeIf { it.isNotEmpty() }?.let { params ->
        j.name("params").beginObject()
        for ((k, v) in params.toSortedMap()) j.name(k).value(v)
        j.endObject()
    }
    s.colors?.takeIf { it.isNotEmpty() }?.let { colors ->
        j.name("colors").beginObject()
        for ((k, v) in colors.toSortedMap()) {
            j.name(k)
            writeRgba(j, v)
        }
        j.endObject()
    }
    j.endObject()
}

/** A page/document margin override, written only when an edge is set (fractions 0..1). */
internal fun writeMargins(j: JsonWrite, m: PageMargins) {
    if (m.isEmpty) return
    j.name("margins").beginObject()
    m.left?.let { j.name("left").value(it) }
    m.top?.let { j.name("top").value(it) }
    m.right?.let { j.name("right").value(it) }
    m.bottom?.let { j.name("bottom").value(it) }
    j.endObject()
}


internal fun parseStyle(p: JsonPull): PageStyle {
    if (p.peek() != JsonPull.Token.BEGIN_OBJECT) {
        p.skipValue()
        return PageStyle()
    }
    var pageColor: Rgba? = null
    var pattern: String? = null
    var template: String? = null
    var patternColor: Rgba? = null
    var spacing: Double? = null
    var accent: Rgba? = null
    var params: Map<String, Double>? = null
    var colors: Map<String, Rgba>? = null
    p.beginObject()
    while (p.hasNext()) {
        when (p.nextName()) {
            "page_color" -> pageColor = rgbaOrNull(p)
            "pattern" -> pattern = PagePattern.fromId(stringOrNull(p))?.id
            "template" -> template = stringOrNull(p)?.takeIf { DocumentCodec.TEMPLATE_KEY.matches(it) }
            "pattern_color" -> patternColor = rgbaOrNull(p)
            "spacing" -> spacing = doubleOrNull(p)
            "accent_color" -> accent = rgbaOrNull(p)
            "params" -> params = namedValues(p) { doubleOrNull(it) }
            "colors" -> colors = namedValues(p) { rgbaOrNull(it) }
            else -> p.skipValue()
        }
    }
    p.endObject()
    return PageStyle(
        pageColor = pageColor,
        template = template ?: pattern,
        patternColor = patternColor,
        spacing = spacing,
        accentColor = accent,
        params = params,
        colors = colors,
    )
}

private fun <T> namedValues(p: JsonPull, value: (JsonPull) -> T?): Map<String, T>? {
    if (p.peek() != JsonPull.Token.BEGIN_OBJECT) {
        p.skipValue()
        return null
    }
    val out = LinkedHashMap<String, T>()
    p.beginObject()
    while (p.hasNext()) {
        val k = p.nextName()
        value(p)?.let { out[k] = it }
    }
    p.endObject()
    return out.takeIf { it.isNotEmpty() }
}

internal fun parseMargins(p: JsonPull): PageMargins {
    if (p.peek() != JsonPull.Token.BEGIN_OBJECT) {
        p.skipValue()
        return PageMargins()
    }
    var left: Double? = null
    var top: Double? = null
    var right: Double? = null
    var bottom: Double? = null
    p.beginObject()
    while (p.hasNext()) {
        when (p.nextName()) {
            "left" -> left = doubleOrNull(p)
            "top" -> top = doubleOrNull(p)
            "right" -> right = doubleOrNull(p)
            "bottom" -> bottom = doubleOrNull(p)
            else -> p.skipValue()
        }
    }
    p.endObject()
    // A file from a newer/looser writer can carry anything; clamp to the range the UI offers.
    fun clamp(v: Double?) = v?.coerceIn(0.0, PageMargins.MAX)
    return PageMargins(clamp(left), clamp(top), clamp(right), clamp(bottom))
}
