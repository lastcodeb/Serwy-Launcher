package dev.mibox.neon.dream

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import dev.mibox.neon.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalTime
import dev.mibox.neon.i18n.t

/** One playable clip and who made it (kept for reference; the screensaver shows no credits). */
@Immutable
data class SkyClip(val url: String, val author: String)

/**
 * What the sky outside looks like right now, and how the scene should feel. A clip only counts
 * when its tags contain one of [tags]: Pixabay's search alone happily returns spooky forests for
 * "moon over sea".
 */
@Immutable
data class SkyMood(val key: String, val query: String, val label: String, val light: Color, val tags: List<String>)

private val SEA = listOf("sea", "ocean", "beach", "coast", "waves")
private val BANNED = listOf("horror", "spooky", "ghost", "halloween", "zombie", "gruesome", "blood", "scary")

/**
 * Real footage for the screensaver, searched on Pixabay by the actual time of day and weather in
 * the user's city. Pixabay requires results to be cached for 24 hours, which also means the API
 * is hit only a handful of times a day. Its docs allow embedding videos directly in apps.
 */
object SkyVideos {
    private const val PREFS = "sky_videos"
    private const val TTL_MS = 24L * 60 * 60 * 1000

    val hasKey: Boolean get() = BuildConfig.PIXABAY_KEY.isNotBlank()

    fun moodFor(now: LocalTime, sun: Pair<LocalTime, LocalTime>?, weatherCode: Int?): SkyMood {
        val rise = (sun?.first ?: LocalTime.of(6, 30)).toSecondOfDay() / 60
        val set = (sun?.second ?: LocalTime.of(18, 30)).toSecondOfDay() / 60
        val m = now.toSecondOfDay() / 60
        val time = when {
            m in (rise - 45)..(rise + 45) -> "dawn"
            m in (set - 45)..(set + 45) -> "dusk"
            m in rise..set -> "day"
            else -> "night"
        }
        val weather = when (weatherCode) {
            null, 0, 1 -> "clear"
            2, 3 -> "cloudy"
            45, 48 -> "fog"
            in 51..67, in 80..82 -> "rain"
            in 71..77, 85, 86 -> "snow"
            in 95..99 -> "storm"
            else -> "clear"
        }
        // Weather that changes the whole picture wins over the time of day
        return when (weather) {
            "storm" -> SkyMood("storm", "lightning storm", t("Fırtına"), Color(0xFF3A3F7A), listOf("lightning", "thunderstorm", "thunder"))
            "rain" -> SkyMood("rain", "rain drops", t("Yağmur"), Color(0xFF3D5A80), listOf("rain", "raindrops", "rainy"))
            "snow" -> SkyMood("snow", "snowfall", t("Kar"), Color(0xFFDDE7F5), listOf("snow", "snowfall", "snowflakes"))
            "fog" -> SkyMood("fog", "fog sea", t("Sis"), Color(0xFFB8C2CC), listOf("fog", "mist", "foggy"))
            else -> when (time) {
                "dawn" -> SkyMood("dawn", "sunrise sea", t("Gün doğumu"), Color(0xFFFF9466), SEA)
                "dusk" -> SkyMood("dusk", "sunset sea", t("Gün batımı"), Color(0xFFFF7A45), SEA)
                "day" -> if (weather == "cloudy") {
                    SkyMood("day-cloudy", "clouds sky timelapse", t("Bulutlu gökyüzü"), Color(0xFFE6EEF7), listOf("clouds", "cloud", "sky"))
                } else {
                    SkyMood("day-clear", "sea waves beach", t("Açık deniz"), Color(0xFFFFF1D6), SEA)
                }
                else -> if (weather == "cloudy") {
                    SkyMood("night-cloudy", "night sky clouds moon", t("Bulutlu gece"), Color(0xFF26306E), listOf("moon", "night sky", "stars"))
                } else {
                    SkyMood("night-clear", "moon sea", t("Aylı gece"), Color(0xFF26306E), SEA)
                }
            }
        }
    }

    /** Up to [max] landscape clips for [mood]; 4K files when [uhd], else 1080p. */
    fun clips(ctx: Context, mood: SkyMood, uhd: Boolean, max: Int = 6): List<SkyClip> {
        if (!hasKey) return emptyList()
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cacheKey = "${mood.key}-${if (uhd) "uhd" else "hd"}"
        val cached = prefs.getString(cacheKey, null)
        if (cached != null && System.currentTimeMillis() - prefs.getLong("$cacheKey-at", 0) < TTL_MS) {
            return parseCache(cached)
        }
        val fresh = search(mood, uhd, max)
        if (fresh.isNotEmpty()) {
            val json = JSONArray().apply { fresh.forEach { put(JSONObject().put("u", it.url).put("a", it.author)) } }
            prefs.edit().putString(cacheKey, json.toString()).putLong("$cacheKey-at", System.currentTimeMillis()).apply()
        }
        return fresh.ifEmpty { cached?.let(::parseCache).orEmpty() }
    }

    private fun search(mood: SkyMood, uhd: Boolean, max: Int): List<SkyClip> {
        // Real footage only (no animation), at least Full HD, most popular first
        val url = "https://pixabay.com/api/videos/?video_type=film&min_width=1920&order=popular&per_page=80" +
            "&safesearch=true&key=${BuildConfig.PIXABAY_KEY}&q=" + URLEncoder.encode(mood.query, "UTF-8")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 6000
        conn.readTimeout = 8000
        val body = try {
            if (conn.responseCode !in 200..299) throw IOException("Pixabay HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
        val hits = JSONObject(body).optJSONArray("hits") ?: return emptyList()
        val out = ArrayList<SkyClip>()
        for (i in 0 until hits.length()) {
            val v = hits.getJSONObject(i)
            // Very short clips loop too visibly
            if (v.optInt("duration") < 10) continue
            val tags = v.optString("tags").lowercase()
            if (mood.tags.none { it in tags } || BANNED.any { it in tags }) continue
            val files = v.optJSONObject("videos") ?: continue
            val pick = pickFile(files, uhd) ?: continue
            out += SkyClip(pick, v.optString("user"))
            if (out.size >= max) break
        }
        return out
    }

    /**
     * The sharpest landscape rendition the panel can use. Pixabay's "large" is 4K on newer
     * uploads but only 1080p on older ones, and vertical videos come back too.
     */
    private fun pickFile(files: JSONObject, uhd: Boolean): String? {
        val maxWidth = if (uhd) 3840 else 1920
        return listOf("large", "medium")
            .mapNotNull(files::optJSONObject)
            .filter { f ->
                val w = f.optInt("width")
                f.optString("url").isNotEmpty() && w > f.optInt("height") && w in 1920..maxWidth
            }
            .maxByOrNull { it.optInt("width") }
            ?.optString("url")
    }

    private fun parseCache(json: String): List<SkyClip> = runCatching {
        val arr = JSONArray(json)
        List(arr.length()) { arr.getJSONObject(it).let { o -> SkyClip(o.getString("u"), o.optString("a")) } }
    }.getOrDefault(emptyList())
}
