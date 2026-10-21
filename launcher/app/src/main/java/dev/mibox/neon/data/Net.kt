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

private fun httpGet(url: String): String {
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

    /** WMO weather code -> emoji + Turkish description. */
    fun describe(code: Int, isDay: Boolean): Pair<String, String> = when (code) {
        0 -> (if (isDay) "☀️" else "🌙") to "Açık"
        1 -> (if (isDay) "🌤️" else "🌙") to "Az bulutlu"
        2 -> "⛅" to "Parçalı bulutlu"
        3 -> "☁️" to "Kapalı"
        45, 48 -> "🌫️" to "Sisli"
        in 51..57 -> "🌦️" to "Çisenti"
        in 61..67 -> "🌧️" to "Yağmurlu"
        in 71..77 -> "🌨️" to "Karlı"
        in 80..82 -> "🌧️" to "Sağanak"
        85, 86 -> "🌨️" to "Kar sağanağı"
        in 95..99 -> "⛈️" to "Fırtına"
        else -> "🌡️" to ""
    }
}

object SpeedTest {
    private const val DOWNLOAD_URL = "https://speed.cloudflare.com/__down?bytes=50000000"
    private const val MAX_NANOS = 8_000_000_000L
    private const val REPORT_NANOS = 250_000_000L

    /** Downloads for up to 8 s and returns the average Mbps, reporting progress along the way. */
    suspend fun run(onProgress: (Double) -> Unit): Double = withContext(Dispatchers.IO) {
        val conn = URL(DOWNLOAD_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        try {
            conn.inputStream.use { input ->
                val buf = ByteArray(64 * 1024)
                val start = System.nanoTime()
                var lastReport = start
                var bytes = 0L
                while (true) {
                    ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    bytes += n
                    val now = System.nanoTime()
                    if (now - lastReport > REPORT_NANOS) {
                        onProgress(mbps(bytes, now - start))
                        lastReport = now
                    }
                    if (now - start > MAX_NANOS) break
                }
                mbps(bytes, System.nanoTime() - start)
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun mbps(bytes: Long, nanos: Long) = bytes * 8.0 / (nanos / 1e9) / 1e6
}
