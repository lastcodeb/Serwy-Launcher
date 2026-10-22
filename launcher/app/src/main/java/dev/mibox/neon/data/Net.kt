package dev.mibox.neon.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale
import dev.mibox.neon.i18n.I18n
import dev.mibox.neon.i18n.t

internal fun httpGet(url: String): String {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = 6000
    conn.readTimeout = 6000
    try {
        if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
        return conn.inputStream.bufferedReader().use { it.readText() }
    } finally {
        conn.disconnect()
    }
}

/** Open-Meteo: free, no API key. */
object Weather {
    fun geocode(query: String): City? {
        val url = "https://geocoding-api.open-meteo.com/v1/search?count=1&language=tr&format=json&name=" +
            URLEncoder.encode(query.trim(), "UTF-8")
        val results = JSONObject(httpGet(url)).optJSONArray("results") ?: return null
        if (results.length() == 0) return null
        val r = results.getJSONObject(0)
        return City(r.getString("name"), r.getDouble("latitude"), r.getDouble("longitude"))
    }

    fun now(city: City): WeatherNow {
        // Locale.US: the Turkish locale would format 41.0 as "41,0" and break the query
        val url = String.format(
            Locale.US,
            "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f" +
                "&current=temperature_2m,weather_code,is_day&timezone=auto",
            city.lat, city.lon,
        )
        val c = JSONObject(httpGet(url)).getJSONObject("current")
        return WeatherNow(c.getDouble("temperature_2m"), c.getInt("weather_code"), c.optInt("is_day", 1) == 1)
    }

    /** Today's sunrise and sunset in the city's own time zone; drives the screensaver sky. */
    fun sun(city: City): Pair<LocalTime, LocalTime>? {
        val url = String.format(
            Locale.US,
            "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f" +
                "&daily=sunrise,sunset&timezone=auto&forecast_days=1",
            city.lat, city.lon,
        )
        val daily = JSONObject(httpGet(url)).optJSONObject("daily") ?: return null
        val rise = daily.optJSONArray("sunrise")?.optString(0) ?: return null
        val set = daily.optJSONArray("sunset")?.optString(0) ?: return null
        return LocalDateTime.parse(rise).toLocalTime() to LocalDateTime.parse(set).toLocalTime()
    }

    /** WMO weather code -> emoji + description in the box's language. */
    fun describe(code: Int, isDay: Boolean): Pair<String, String> = when (code) {
        0 -> (if (isDay) "☀️" else "🌙") to weatherWord("Açık", "Clear")
        1 -> (if (isDay) "🌤️" else "🌙") to t("Az bulutlu")
        2 -> "⛅" to t("Parçalı bulutlu")
        3 -> "☁️" to weatherWord("Kapalı", "Overcast")
        45, 48 -> "🌫️" to t("Sisli")
        in 51..57 -> "🌦️" to t("Çisenti")
        in 61..67 -> "🌧️" to t("Yağmurlu")
        in 71..77 -> "🌨️" to t("Karlı")
        in 80..82 -> "🌧️" to t("Sağanak")
        85, 86 -> "🌨️" to t("Kar sağanağı")
        in 95..99 -> "⛈️" to t("Fırtına")
        else -> "🌡️" to ""
    }

    // "Açık" and "Kapalı" also mean on/off in the catalog, so the sky words skip it
    private fun weatherWord(tr: String, en: String) = if (I18n.turkish) tr else en
}

object SpeedTest {
    private const val DOWNLOAD_URL = "https://speed.cloudflare.com/__down?bytes=50000000"
    private const val FULL_MS = 8_000L
    private const val REPORT_NANOS = 250_000_000L

    /** Runs shorter than this skip the first [WARMUP_NANOS]: TCP slow start would understate the line. */
    private const val SHORT_MS = 5_000L
    private const val WARMUP_NANOS = 600_000_000L

    /**
     * Downloads for up to [durationMs] and returns the average Mbps, reporting progress along the
     * way. Short runs (the silent background probe) leave out the ramp-up second.
     */
    suspend fun run(durationMs: Long = FULL_MS, onProgress: (Double) -> Unit = {}): Double = withContext(Dispatchers.IO) {
        val maxNanos = durationMs * 1_000_000L
        val warmup = if (durationMs < SHORT_MS) WARMUP_NANOS else 0L
        val conn = URL(DOWNLOAD_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        try {
            conn.inputStream.use { input ->
                val buf = ByteArray(64 * 1024)
                val start = System.nanoTime()
                var lastReport = start
                var bytes = 0L
                var warmAt = start
                var warmBytes = 0L
                var warmed = warmup == 0L
                while (true) {
                    ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    bytes += n
                    val now = System.nanoTime()
                    if (!warmed && now - start >= warmup) {
                        warmed = true
                        warmAt = now
                        warmBytes = bytes
                    }
                    if (now - lastReport > REPORT_NANOS) {
                        onProgress(mbps(bytes - warmBytes, now - warmAt))
                        lastReport = now
                    }
                    if (now - start > maxNanos) break
                }
                mbps(bytes - warmBytes, System.nanoTime() - warmAt)
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun mbps(bytes: Long, nanos: Long) = if (nanos <= 0) 0.0 else bytes * 8.0 / (nanos / 1e9) / 1e6
}

/** Round-trip time to Cloudflare's edge: a tiny HTTPS request, so it also shows the DNS/TLS path is alive. */
object Latency {
    private const val URL_204 = "https://cp.cloudflare.com/generate_204"

    /** Milliseconds, or null when offline. The first request warms the connection and is not counted. */
    fun probe(): Int? = runCatching {
        var best = Long.MAX_VALUE
        repeat(3) { i ->
            val conn = URL(URL_204).openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.useCaches = false
            val t0 = System.nanoTime()
            val code = conn.responseCode
            conn.inputStream.close() // fully drained 204: keeps the connection alive for the next try
            val dt = System.nanoTime() - t0
            if (code !in 200..299) throw IOException("HTTP $code")
            if (i > 0) best = minOf(best, dt)
        }
        (best / 1_000_000L).toInt().coerceAtLeast(1)
    }.getOrNull()
}
