package dev.mibox.neon.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Every setting (favorites, order, hidden apps, theme, city, light presets…) in one JSON file,
 * Download/Serwy/neon-yedek.json, which outlives uninstalling the launcher. Writing to Download
 * needs no permission. After a reinstall the file belongs to nobody and the app may no longer
 * read it, so a restore then goes through the box's own adb ([LocalAdb]).
 */
class Backup(private val ctx: Context, private val prefs: Prefs, private val adb: LocalAdb) {

    sealed interface Restore {
        data class Done(val savedAt: Long) : Restore
        data object NoBackup : Restore
        data object NotAllowed : Restore
        data object Broken : Restore
    }

    /** Writes the backup; returns its time, or null when the file could not be written. */
    suspend fun save(): Long? = withContext(Dispatchers.IO) {
        val at = System.currentTimeMillis()
        val json = JSONObject()
            .put("app", ctx.packageName)
            .put("version", VERSION)
            .put("at", at)
            .put("prefs", prefs.export())
        runCatching {
            val uri = ownFile() ?: insert()
            ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(json.toString(1).toByteArray()) }
            Log.i(TAG, "saved ${json.getJSONObject("prefs").length()} settings to $uri")
            at
        }.onFailure { Log.w(TAG, "backup failed", it) }.getOrNull()
    }

    /** Time of the backup this install wrote (or restored), or null when it has none of its own. */
    suspend fun ownSavedAt(): Long? = withContext(Dispatchers.IO) {
        ownFile()?.let { uri -> runCatching { parse(read(uri))?.optLong("at") }.getOrNull() }
    }

    /** Replaces every setting with the newest backup. */
    suspend fun restore(): Restore = withContext(Dispatchers.IO) {
        val own = ownFile()
        val text = if (own != null) {
            runCatching { read(own) }.getOrNull()
        } else {
            // Written by an earlier install: only the shell can still read it
            adb.shell("f=\$(ls -t $DIR/$NAME_PREFIX*.json 2>/dev/null | head -n1); [ -n \"\$f\" ] && cat \"\$f\"")
                ?: return@withContext Restore.NotAllowed
        }
        if (text.isNullOrBlank()) return@withContext Restore.NoBackup
        val json = parse(text) ?: return@withContext Restore.Broken
        val settings = json.optJSONObject("prefs") ?: return@withContext Restore.Broken
        prefs.import(settings)
        // Put a copy of our own in place of the orphaned file, so the next restore needs no shell
        if (own == null) {
            adb.shell("rm -f $DIR/$NAME_PREFIX*.json")
            save()
        }
        Restore.Done(json.optLong("at"))
    }

    private fun parse(text: String): JSONObject? =
        runCatching { JSONObject(text) }.getOrNull()?.takeIf { it.optString("app") == ctx.packageName }

    private fun read(uri: Uri): String = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }

    /** Our own backup file, newest first; MediaStore shows an app only the files it wrote. */
    private fun ownFile(): Uri? {
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        return ctx.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
            arrayOf(RELATIVE, "$NAME_PREFIX%"),
            "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
        )?.use { c -> if (c.moveToFirst()) Uri.withAppendedPath(collection, c.getLong(0).toString()) else null }
    }

    private fun insert(): Uri = ctx.contentResolver.insert(
        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
        ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$NAME_PREFIX.json")
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE)
        },
    )!!

    private companion object {
        const val TAG = "NeonBackup"
        const val VERSION = 1
        const val RELATIVE = "Download/Serwy/"
        const val DIR = "/sdcard/Download/Serwy"
        const val NAME_PREFIX = "neon-yedek"
    }
}
