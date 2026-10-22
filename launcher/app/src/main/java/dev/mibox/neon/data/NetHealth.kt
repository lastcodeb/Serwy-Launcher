package dev.mibox.neon.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import androidx.compose.runtime.Immutable
import java.util.Locale

/** One finished speed test. [auto] is true for the silent background probe. */
@Immutable
data class SpeedResult(val at: Long, val mbps: Double, val auto: Boolean)

enum class LinkType { ETHERNET, WIFI, OTHER, NONE }

/** What the newest recent speed result means for streaming. */
enum class NetVerdict {
    /** At least 25 Mbps: 4K streaming is fine. */
    UHD_OK,

    /** 8 to 25 Mbps: HD is fine, 4K may stutter. */
    HD_OK,

    /** Below 8 Mbps. */
    WEAK,

    /** No result in the last 3 hours. */
    UNKNOWN,
}

/** How the box is connected right now ([rssiDbm] and [linkMbps] are Wi-Fi only). */
@Immutable
data class LinkInfo(val type: LinkType, val rssiDbm: Int?, val linkMbps: Int?) {
    /** 0..4 bars from the Wi-Fi signal; null off Wi-Fi. */
    val signalBars: Int?
        get() = rssiDbm?.let {
            when {
                it >= -55 -> 4
                it >= -67 -> 3
                it >= -75 -> 2
                it >= -85 -> 1
                else -> 0
            }
        }
}

/** Everything the network card shows in one value. */
@Immutable
data class NetNow(
    val link: LinkType,
    val rssiDbm: Int?,
    val signalBars: Int?,
    val linkMbps: Int?,
    val latencyMs: Int?,
    val last: SpeedResult?,
    val verdict: NetVerdict,
)

/** One-shot: a streaming app was opened while the last test said 4K would stutter. */
@Immutable
data class NetWarning(val appLabel: String, val mbps: Double)

object NetHealth {
    const val UHD_MBPS = 25.0
    const val HD_MBPS = 8.0
    const val HISTORY_MAX = 30

    /** A result older than this says nothing about the line now. */
    const val FRESH_MS = 3L * 60 * 60 * 1000

    /** The background probe runs when the newest result is older than this. */
    const val AUTO_STALE_MS = 60L * 60 * 1000

    /**
     * Streaming apps that trigger the 4K warning. Verified against the package list of the real box
     * (device-backup): Netflix, YouTube for TV, Disney+, Prime Video, Apple TV, HBO Max
     * (`com.wbd.stream`) and Tabii (TRT). Not verified on a TV build: Exxen and Gain (the ids exist
     * on Google Play, but could not be checked against an Android TV install). TOD / beIN CONNECT
     * is deliberately missing: its TV package name could not be found; add it here when known.
     */
    val StreamingPackages: Set<String> = setOf(
        "com.netflix.ninja",
        "com.google.android.youtube.tv",
        "com.disney.disneyplus",
        "com.amazon.amazonvideo.livingroom",
        "com.apple.atve.androidtv.appletv",
        "com.wbd.stream",
        "com.trt.tabii.android",
        "com.exxen.android", // unverified on TV
        "com.gain.android", // unverified on TV
    )

    /** Newest result that is still fresh enough to judge the line by. */
    fun fresh(history: List<SpeedResult>, now: Long): SpeedResult? =
        history.lastOrNull()?.takeIf { now - it.at <= FRESH_MS }

    fun verdict(history: List<SpeedResult>, now: Long): NetVerdict {
        val r = fresh(history, now) ?: return NetVerdict.UNKNOWN
        return when {
            r.mbps >= UHD_MBPS -> NetVerdict.UHD_OK
            r.mbps >= HD_MBPS -> NetVerdict.HD_OK
            else -> NetVerdict.WEAK
        }
    }

    /** Transport, Wi-Fi signal and negotiated link speed; never needs the location permission. */
    @Suppress("DEPRECATION")
    fun link(ctx: Context): LinkInfo {
        val app = ctx.applicationContext
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return LinkInfo(LinkType.NONE, null, null)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return LinkInfo(LinkType.NONE, null, null)
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> LinkInfo(LinkType.ETHERNET, null, null)
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                val info = (caps.transportInfo as? WifiInfo)
                    ?: runCatching { app.getSystemService(WifiManager::class.java)?.connectionInfo }.getOrNull()
                val rssi = info?.rssi?.takeIf { it in -126..0 }
                val speed = info?.linkSpeed?.takeIf { it > 0 }
                LinkInfo(LinkType.WIFI, rssi, speed)
            }
            else -> LinkInfo(LinkType.OTHER, null, null)
        }
    }

    // History is one compact string in DataStore: "ts,mbps,a;ts,mbps,m;..."
    fun encode(list: List<SpeedResult>): String =
        list.joinToString(";") { String.format(Locale.US, "%d,%.2f,%s", it.at, it.mbps, if (it.auto) "a" else "m") }

    fun decode(s: String?): List<SpeedResult> = s.orEmpty().split(';').mapNotNull { e ->
        val p = e.split(',')
        if (p.size < 3) return@mapNotNull null
        val at = p[0].toLongOrNull() ?: return@mapNotNull null
        val mbps = p[1].toDoubleOrNull() ?: return@mapNotNull null
        SpeedResult(at, mbps, p[2] == "a")
    }
}
