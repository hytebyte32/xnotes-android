package com.xnotes.bench

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.os.SystemClock
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.NoteDocument
import com.xnotes.core.model.PageSection
import com.xnotes.core.model.RegionSection
import com.xnotes.core.model.Stroke
import com.xnotes.format.CanvasCodec
import com.xnotes.format.DocumentCodec
import com.xnotes.format.XDocCodec
import com.xnotes.platform.AndroidImageCodec
import com.xnotes.platform.AndroidTextMeasurer
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Bench-only check of the unified `.xdoc` format against real notes. For every `.xnote` and
 * `.xcanvas` under a folder it reads the legacy file, carries it into [NoteDocument], writes and reads
 * an `.xdoc`, exports it back to the legacy type, and compares that export with a baseline export of
 * the same legacy read. Nothing is ever written to the folder; all work is in the app's cache.
 *
 * The baseline is the legacy read re-exported rather than the original bytes, because an older writer's
 * file is legitimately rewritten on a re-export (ink compaction), and that is not what is being tested.
 */
class NoteCheck(private val context: Context, private val line: (String) -> Unit) {

    private class Found(val uri: Uri, val path: String, val name: String)

    private val imageCodec = AndroidImageCodec()
    private val docCodec = DocumentCodec(imageCodec, AndroidTextMeasurer())
    private val canvasCodec = CanvasCodec(imageCodec)
    private val xdoc = XDocCodec(imageCodec, AndroidTextMeasurer())

    fun run(tree: Uri): JSONObject {
        val out = JSONObject()
        val files = ArrayList<Found>()
        walk(tree, DocumentsContract.getTreeDocumentId(tree), "", files)
        line("note check: ${files.size} notes found")
        val rows = JSONArray()
        var passed = 0
        for ((i, f) in files.withIndex()) {
            val row = check(f)
            if (row.optBoolean("ok")) passed++
            rows.put(row)
            line("[${i + 1}/${files.size}] ${if (row.optBoolean("ok")) "ok  " else "FAIL"} ${f.path}  ${row.optString("summary")}")
        }
        out.put("files", files.size)
        out.put("passed", passed)
        out.put("failed", files.size - passed)
        out.put("rows", rows)
        line("note check done: $passed of ${files.size} passed")
        return out
    }

    private fun walk(tree: Uri, docId: String, prefix: String, out: MutableList<Found>) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        val entries = ArrayList<Triple<String, String, String>>()
        context.contentResolver.query(children, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) entries.add(Triple(c.getString(0), c.getString(1) ?: "", c.getString(2) ?: ""))
        }
        for ((id, name, mime) in entries) {
            if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                walk(tree, id, "$prefix$name/", out)
            } else if (name.endsWith(".xnote") || name.endsWith(".xcanvas")) {
                out.add(Found(DocumentsContract.buildDocumentUriUsingTree(tree, id), "$prefix$name", name))
            }
        }
    }

    private fun check(f: Found): JSONObject {
        val row = JSONObject().put("file", f.path)
        val work = File(context.cacheDir, "notecheck").apply { deleteRecursively(); mkdirs() }
        try {
            val staged = File(work, "orig")
            context.contentResolver.openInputStream(f.uri)?.use { i -> FileOutputStream(staged).use { i.copyTo(it) } }
                ?: return row.put("ok", false).put("error", "could not open").put("summary", "could not open")
            row.put("orig_bytes", staged.length())
            val canvas = f.name.endsWith(".xcanvas")
            row.put("kind", if (canvas) "xcanvas" else "xnote")
            val imgDir = File(work, "img").apply { mkdirs() }
            val pdfDir = File(work, "pdf").apply { mkdirs() }

            // 1. Legacy read, then a baseline export of what was read.
            var t = SystemClock.elapsedRealtime()
            val note: NoteDocument
            val baseline: ByteArray
            if (canvas) {
                val doc = FileInputStream(staged).use { canvasCodec.read(it, imgDir) }
                baseline = ByteArrayOutputStream().also { canvasCodec.write(doc, it) }.toByteArray()
                note = NoteDocument.fromCanvas(doc)
            } else {
                val doc = FileInputStream(staged).use { docCodec.read(it, pdfDir, imgDir) }
                baseline = ByteArrayOutputStream().also { docCodec.write(doc, it) }.toByteArray()
                note = NoteDocument.fromDocument(doc)
            }
            row.put("legacy_read_ms", SystemClock.elapsedRealtime() - t)
            row.put("baseline_equals_original", baseline.contentEquals(staged.readBytes()))
            val before = counts(note)

            // 2. Through the .xdoc.
            t = SystemClock.elapsedRealtime()
            val xfile = File(work, "note.xdoc")
            FileOutputStream(xfile).use { xdoc.write(note, it) }
            row.put("xdoc_write_ms", SystemClock.elapsedRealtime() - t).put("xdoc_bytes", xfile.length())
            t = SystemClock.elapsedRealtime()
            val back = FileInputStream(xfile).use { xdoc.read(it, pdfDir, imgDir) }
            row.put("xdoc_read_ms", SystemClock.elapsedRealtime() - t)
            val after = counts(back)
            row.put("sections", before.sections).put("items", before.items).put("samples", before.samples)

            // 3. Export back to the legacy type and compare with the baseline.
            t = SystemClock.elapsedRealtime()
            val exported = ByteArrayOutputStream()
            if (canvas) {
                val d = back.toCanvas() ?: return row.put("ok", false).put("error", "did not come back as a canvas").put("summary", "not a canvas")
                canvasCodec.write(d, exported)
            } else {
                val d = back.toDocument() ?: return row.put("ok", false).put("error", "did not come back as a paged note").put("summary", "not paged")
                docCodec.write(d, exported)
            }
            row.put("export_ms", SystemClock.elapsedRealtime() - t)
            val same = sha(exported.toByteArray()) == sha(baseline)
            val countsSame = before == after
            row.put("export_identical", same).put("counts_identical", countsSame)
            val ok = same && countsSame
            row.put("ok", ok)
            if (!ok) {
                row.put("baseline_bytes", baseline.size).put("export_bytes", exported.size())
                row.put("counts_before", before.toString()).put("counts_after", after.toString())
            }
            row.put("summary", "${before.sections} sections, ${before.items} items, ${before.samples} samples, ${xfile.length() / 1024} KB xdoc")
        } catch (t: Throwable) {
            row.put("ok", false).put("error", t.javaClass.simpleName + ": " + (t.message ?: "").take(300))
            row.put("summary", "threw ${t.javaClass.simpleName}")
        } finally {
            work.deleteRecursively()
        }
        return row
    }

    private data class Counts(val sections: Int, val pages: Int, val regions: Int, val items: Int, val samples: Long)

    private fun counts(n: NoteDocument): Counts {
        var items = 0
        var samples = 0L
        fun tally(list: List<CanvasItem>) {
            items += list.size
            for (it in list) if (it is Stroke) samples += it.samples.size
        }
        for (s in n.sections) when (s) {
            is PageSection -> tally(s.page.items)
            is RegionSection -> tally(s.items)
        }
        return Counts(n.sections.size, n.pages.size, n.regions.size, items, samples)
    }

    private fun sha(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}
