package dev.mibox.neon.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.Immutable
import java.net.HttpURLConnection
import java.net.URL

/** One "continue watching" entry an app published to the system Watch Next list. */
@Immutable
data class WatchItem(
    val id: Long,
    val pkg: String,
    val title: String,
    val subtitle: String?,
    val imageUri: String?,
    val intentUri: String?,
    val progress: Float?,
    val lastEngaged: Long,
)

object WatchNext {
    private val URI = Uri.parse("content://android.media.tv/watch_next_program")
    private val PREVIEW_URI = Uri.parse("content://android.media.tv/preview_program")

    /** Newest first, at most [perApp] entries from any single app. Throws if the provider refuses us. */
    fun query(ctx: Context, perApp: Int = 2, max: Int = 16): List<WatchItem> = read(ctx, URI)
        .sortedByDescending { it.lastEngaged }
        .groupBy { it.pkg }
        .flatMap { (_, perPkg) -> perPkg.take(perApp) }
        .sortedByDescending { it.lastEngaged }
        .take(max)

    /**
     * What apps recommend on their home-screen channels ("Senin için"), at most [perApp] per app,
     * dealt round-robin so one app never fills the row. Titles already in [exclude] are skipped.
     */
    fun previews(ctx: Context, perApp: Int = 3, max: Int = 24, exclude: Set<String> = emptySet()): List<WatchItem> {
        val byApp = read(ctx, PREVIEW_URI)
            .filter { it.imageUri != null && it.title !in exclude }
            .distinctBy { it.title }
            .groupBy { it.pkg }
            .mapValues { it.value.take(perApp) }
            .values.toList()
        val out = ArrayList<WatchItem>()
        var i = 0
        while (out.size < max && byApp.any { i < it.size }) {
            for (list in byApp) if (i < list.size && out.size < max) out += list[i]
            i++
        }
        return out
    }

    private fun read(ctx: Context, uri: Uri): List<WatchItem> {
        val items = ArrayList<WatchItem>()
        // Projection null: column sets differ between TvProvider versions
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            fun str(name: String): String? = c.getColumnIndex(name).takeIf { it >= 0 && !c.isNull(it) }?.let(c::getString)
            fun num(name: String): Long? = c.getColumnIndex(name).takeIf { it >= 0 && !c.isNull(it) }?.let(c::getLong)
            while (c.moveToNext()) {
                if (num("browsable") == 0L) continue
                val title = str("title") ?: continue
                val season = str("season_display_number")
                val episode = str("episode_display_number")
                val episodeTitle = str("episode_title")
                val subtitle = listOfNotNull(
                    if (season != null && episode != null) "S$season · B$episode" else episode?.let { "B$it" },
                    episodeTitle,
                ).joinToString("  ·  ").ifEmpty { null }
                val pos = num("last_playback_position_millis")
                val dur = num("duration_millis")
                items += WatchItem(
                    id = num("_id") ?: continue,
                    pkg = str("package_name") ?: continue,
                    title = title,
                    subtitle = subtitle,
                    imageUri = str("thumbnail_uri") ?: str("poster_art_uri"),
                    intentUri = str("intent_uri"),
                    progress = if (pos != null && dur != null && dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else null,
                    lastEngaged = num("last_engagement_time_utc_millis") ?: 0L,
                )
            }
        }
        return items
    }

    /**
     * A card thumbnail cropped to exactly [wPx]×[hPx] in RGB_565: half the memory of ARGB and no
     * larger than what is drawn (decoding alone can leave it up to twice the card size).
     */
    fun loadThumbnail(ctx: Context, uri: String, wPx: Int, hPx: Int): Bitmap? {
        val src = loadImage(ctx, uri, wPx, hPx) ?: return null
        val scale = maxOf(wPx.toFloat() / src.width, hPx.toFloat() / src.height)
        val scaled = Bitmap.createScaledBitmap(src, (src.width * scale).toInt().coerceAtLeast(wPx), (src.height * scale).toInt().coerceAtLeast(hPx), true)
        if (scaled !== src) src.recycle()
        val cropped = Bitmap.createBitmap(scaled, (scaled.width - wPx) / 2, (scaled.height - hPx) / 2, wPx, hPx)
        if (cropped !== scaled) scaled.recycle()
        return cropped.copy(Bitmap.Config.RGB_565, false).also { cropped.recycle() }
    }

    /** Thumbnails are content://, android.resource:// or https URLs; decoded near the card size. */
    fun loadImage(ctx: Context, uri: String, wPx: Int, hPx: Int): Bitmap? = runCatching {
        val u = Uri.parse(uri)
        val bytes = when (u.scheme) {
            "http", "https" -> (URL(uri).openConnection() as HttpURLConnection).run {
                connectTimeout = 5000
                readTimeout = 8000
                try {
                    inputStream.use { it.readBytes() }
                } finally {
                    disconnect()
                }
            }
            else -> ctx.contentResolver.openInputStream(u)?.use { it.readBytes() }
        } ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= wPx && bounds.outHeight / (sample * 2) >= hPx) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()
}
