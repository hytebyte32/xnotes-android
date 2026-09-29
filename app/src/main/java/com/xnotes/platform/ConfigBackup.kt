package com.xnotes.platform

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File

/**
 * Mirrors the app's config and internal notes into `Documents/xnotes-backup` so a reinstall (or an
 * uninstall/install for a new signing key) can bring everything back. Needs "All files access";
 * without it every call is a quiet no-op. Best effort: nothing here may crash the app.
 *
 * Layout: `data/<dir>/...` for private dirs under filesDir, `notes/...` for the internal notes dir.
 * Backup is an incremental mirror (copy new/changed files, drop files that no longer exist), except
 * that it never runs against an empty source when the backup holds data, so a fresh install cannot
 * wipe the backup before it has been restored.
 */
object ConfigBackup {

    /** Private dirs mirrored under filesDir. Temp/session/thumbnail dirs are excluded: they are regenerable. */
    private val DATA_DIRS = listOf("config", "stamps", "fonts", "templates", "theme")

    fun hasAccess(): Boolean = Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()

    private fun root(): File = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "xnotes-backup")

    /** True when a backup with saved settings exists. */
    fun exists(): Boolean = hasAccess() && File(root(), "data/config/settings.json").isFile

    /** True when this install has no settings yet, i.e. a fresh install. */
    fun isFreshInstall(context: Context): Boolean = !File(context.filesDir, "config/settings.json").exists()

    /** Copy the backup into the app. Returns true if anything was restored. */
    fun restore(context: Context): Boolean = runCatching {
        if (!exists()) return false
        val base = root()
        for (d in DATA_DIRS) mirror(File(base, "data/$d"), File(context.filesDir, d), delete = false)
        mirror(File(base, "notes"), AppStorageDocumentsProvider.rootDir(context), delete = false)
        true
    }.getOrDefault(false)

    /** Mirror the app into the backup. Skipped without access or on a fresh install still awaiting restore. */
    fun backup(context: Context) {
        runCatching {
            if (!hasAccess()) return
            if (isFreshInstall(context) && File(root(), "data").exists()) return
            val base = root()
            for (d in DATA_DIRS) mirror(File(context.filesDir, d), File(base, "data/$d"), delete = true)
            mirror(AppStorageDocumentsProvider.rootDir(context), File(base, "notes"), delete = true)
        }
    }

    private fun mirror(src: File, dst: File, delete: Boolean) {
        if (!src.exists()) return
        if (src.isFile) {
            if (!dst.exists() || dst.length() != src.length() || dst.lastModified() < src.lastModified()) {
                dst.parentFile?.mkdirs()
                src.copyTo(dst, overwrite = true)
                dst.setLastModified(src.lastModified())
            }
            return
        }
        dst.mkdirs()
        val names = src.list().orEmpty().toSet()
        for (n in names) mirror(File(src, n), File(dst, n), delete)
        if (delete) dst.list()?.filter { it !in names && !it.endsWith(".tmp") }?.forEach { File(dst, it).deleteRecursively() }
    }
}
