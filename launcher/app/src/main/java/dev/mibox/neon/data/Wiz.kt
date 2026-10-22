package dev.mibox.neon.data

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import kotlin.math.roundToInt
import dev.mibox.neon.i18n.t

/** Snapshot of a WiZ light as getPilot reports it. */
@Immutable
data class WizPilot(
    val on: Boolean,
    val dimming: Int,
    val sceneId: Int,
    val r: Int,
    val g: Int,
    val b: Int,
    val temp: Int,
    val speed: Int,
) {
    /** setPilot params that put the light back exactly like this. */
    fun restoreParams(): JSONObject = JSONObject().apply {
        put("state", on)
        if (!on) return@apply
        put("dimming", dimming)
        when {
            sceneId > 0 -> {
                put("sceneId", sceneId)
                if (speed > 0) put("speed", speed)
            }
            temp > 0 -> put("temp", temp)
            else -> {
                put("r", r)
                put("g", g)
                put("b", b)
            }
        }
    }

    companion object {
        fun from(j: JSONObject) = WizPilot(
            on = j.optBoolean("state"),
            dimming = j.optInt("dimming", 100),
            sceneId = j.optInt("sceneId", 0),
            r = j.optInt("r"),
            g = j.optInt("g"),
            b = j.optInt("b"),
            temp = j.optInt("temp"),
            speed = j.optInt("speed"),
        )
    }
}

/** WiZ local protocol: JSON over UDP 38899, no cloud, no account. */
object Wiz {
    private const val PORT = 38899
    const val MIN_DIM = 10 // getModelConfig.minDimLevel of the SYBX sync box

    val Scenes: List<Pair<Int, String>> = listOf(
        1 to t("Okyanus"), 2 to t("Romantik"), 3 to t("Gün batımı"), 4 to t("Parti"), 5 to t("Şömine"),
        6 to t("Sıcacık"), 7 to t("Orman"), 8 to t("Pastel"), 9 to t("Uyanış"), 10 to t("Yatma vakti"),
        11 to t("Sıcak beyaz"), 12 to t("Gün ışığı"), 13 to t("Soğuk beyaz"), 14 to t("Gece lambası"),
        15 to t("Odak"), 16 to t("Rahatla"), 17 to t("Gerçek renkler"), 18 to t("TV zamanı"),
        19 to t("Bitki"), 20 to t("İlkbahar"), 21 to t("Yaz"), 22 to t("Sonbahar"), 23 to t("Derinlik"),
        24 to t("Tropik"), 25 to "Mojito", 26 to t("Kulüp"), 27 to t("Yılbaşı"), 28 to t("Cadılar bayramı"),
        29 to t("Mum ışığı"), 30 to t("Altın beyaz"), 31 to t("Nabız"), 32 to "Steampunk",
    )

    fun sceneName(id: Int): String? = Scenes.firstOrNull { it.first == id }?.second

    // Presets are stored by id: "film", "mac" or "scene:<id>"
    const val PARTY = "scene:4"
    val DefaultFavorites = listOf(PARTY, "film", "mac", "scene:14", "scene:1")
    val BuiltInPresets = listOf("film", "mac")

    fun presetName(id: String): String = when (id) {
        "film" -> t("Film")
        "mac" -> t("Maç")
        else -> sceneId(id)?.let(::sceneName) ?: id
    }

    fun presetEmoji(id: String): String = when (id) {
        "film" -> "🎬"
        "mac" -> "⚽"
        "scene:4" -> "🎉"
        "scene:14" -> "🌙"
        "scene:1" -> "🌊"
        "scene:5", "scene:29" -> "🔥"
        "scene:2" -> "💜"
        "scene:26" -> "🪩"
        else -> "✨"
    }

    fun sceneId(preset: String): Int? = preset.removePrefix("scene:").takeIf { preset.startsWith("scene:") }?.toIntOrNull()

    fun presetParams(id: String, dimming: Int, speed: Int): JSONObject? = when (id) {
        "film" -> white(2700, 30)
        "mac" -> white(6500, 100)
        else -> sceneId(id)?.let { scene(it, speed, dimming) }
    }

    /** Is the light currently showing this preset? */
    fun isActive(id: String, p: WizPilot?): Boolean = when {
        p == null || !p.on -> false
        id == "film" -> p.sceneId == 0 && p.temp == 2700 && p.dimming == 30
        id == "mac" -> p.sceneId == 0 && p.temp == 6500 && p.dimming == 100
        else -> p.sceneId == sceneId(id)
    }

    fun call(ip: String, method: String, params: JSONObject = JSONObject(), timeoutMs: Int = 700, tries: Int = 2): JSONObject? {
        val payload = JSONObject().put("method", method).put("params", params).toString().toByteArray()
        return runCatching {
            DatagramSocket().use { s ->
                s.soTimeout = timeoutMs
                val addr = InetAddress.getByName(ip)
                val buf = ByteArray(2048)
                repeat(tries) {
                    s.send(DatagramPacket(payload, payload.size, addr, PORT))
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        s.receive(p)
                        return@use JSONObject(String(p.data, 0, p.length))
                    } catch (_: SocketTimeoutException) {
                    }
                }
                null
            }
        }.getOrNull()
    }

    /** Broadcasts getSystemConfig and returns the first WiZ device that answers. */
    fun discover(): String? = runCatching {
        DatagramSocket().use { s ->
            s.broadcast = true
            s.soTimeout = 1200
            val payload = """{"method":"getSystemConfig","params":{}}""".toByteArray()
            val bcast = InetAddress.getByName("255.255.255.255")
            val buf = ByteArray(2048)
            repeat(3) {
                s.send(DatagramPacket(payload, payload.size, bcast, PORT))
                try {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    val result = JSONObject(String(p.data, 0, p.length)).optJSONObject("result")
                    if (result?.has("moduleName") == true) return@use p.address.hostAddress
                } catch (_: SocketTimeoutException) {
                }
            }
            null
        }
    }.getOrNull()

    fun pilot(ip: String): WizPilot? = call(ip, "getPilot")?.optJSONObject("result")?.let(WizPilot::from)

    fun set(ip: String, params: JSONObject): Boolean =
        call(ip, "setPilot", params)?.optJSONObject("result")?.optBoolean("success") == true

    fun color(c: Color, dimming: Int): JSONObject = JSONObject()
        .put("state", true)
        .put("r", (c.red * 255).roundToInt())
        .put("g", (c.green * 255).roundToInt())
        .put("b", (c.blue * 255).roundToInt())
        .put("dimming", dimming.coerceIn(MIN_DIM, 100))

    fun white(kelvin: Int, dimming: Int): JSONObject =
        JSONObject().put("state", true).put("temp", kelvin).put("dimming", dimming.coerceIn(MIN_DIM, 100))

    fun scene(id: Int, speed: Int, dimming: Int): JSONObject = JSONObject()
        .put("state", true)
        .put("sceneId", id)
        .put("speed", speed.coerceIn(10, 200))
        .put("dimming", dimming.coerceIn(MIN_DIM, 100))

    /** Red at 0 Mbps sliding to green at 100+ Mbps. */
    fun speedColor(mbps: Double): Color {
        val hue = (mbps.coerceIn(0.0, 100.0) * 1.2).toFloat()
        return Color.hsv(hue, 1f, 1f)
    }

    /** Light that matches the sky outside. */
    fun weatherLight(w: WeatherNow, dimming: Int): JSONObject = when (w.code) {
        0, 1 -> if (w.isDay) white(2700, dimming) else color(Color(0xFF1E3A8A), dimming)
        2, 3 -> white(4500, dimming)
        45, 48 -> white(6500, (dimming * 0.6).toInt())
        in 51..67, in 80..82 -> color(Color(0xFF2563EB), dimming)
        in 71..77, 85, 86 -> white(6500, dimming)
        in 95..99 -> color(Color(0xFF7C3AED), dimming)
        else -> white(4000, dimming)
    }
}
