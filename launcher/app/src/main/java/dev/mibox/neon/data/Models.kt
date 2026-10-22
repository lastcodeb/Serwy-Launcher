package dev.mibox.neon.data

import android.content.ComponentName
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap

@Immutable
data class AppEntry(
    val pkg: String,
    val label: String,
    val component: ComponentName,
    val leanback: Boolean,
)

/**
 * The app's own banner (or icon when it has none), plus colors pulled out of it:
 * [cardTop]/[cardBottom] are the banner's edge colors so the logo sits seamlessly on the card,
 * [vibrant] drives glows and the backlight.
 */
@Immutable
class AppArt(
    val image: ImageBitmap,
    val isBanner: Boolean,
    val cardTop: Color,
    val cardBottom: Color,
    val vibrant: Color,
)

/** Why the idle hero shows this app. */
enum class HeroReason {
    /** Usage history says this is what is usually opened at this hour ("Genelde bu saatte"). */
    Predicted,

    /** The app used last. */
    Resume,

    /** Nothing better known: the first app of the row. */
    Default,
}

@Immutable
data class HeroState(val app: AppEntry, val reason: HeroReason) {
    val resume: Boolean get() = reason == HeroReason.Resume
}

enum class PinResult { Added, Removed, Full }

/** A Watch Next / "Senin için" item the user pinned to the Favoriler row. */
@Immutable
data class PinnedContent(val key: String, val item: WatchItem)

/** One card of the Favoriler row: a pinned app, or a pinned piece of content (episode, video…). */
sealed interface FavoriteCard {
    /** Stable Compose key. */
    val key: String

    /** The app that owns the card; its banner is the fallback art. */
    val app: AppEntry

    @Immutable
    data class App(override val app: AppEntry) : FavoriteCard {
        override val key: String get() = "a:${app.pkg}"
    }

    /** [item] is a copy of the original WatchItem; its [WatchItem.id] is a stable negative number. */
    @Immutable
    data class Content(override val app: AppEntry, val item: WatchItem, val pinKey: String) : FavoriteCard {
        override val key: String get() = "c:$pinKey"
    }
}

@Immutable
data class Ram(val total: Long, val avail: Long) {
    val usedPercent: Int get() = if (total == 0L) 0 else ((total - avail) * 100 / total).toInt()
}

@Immutable
data class City(val name: String, val lat: Double, val lon: Double)

val DefaultCity = City("Gazimağusa", 35.1250, 33.9417)

@Immutable
data class WeatherNow(val tempC: Double, val code: Int, val isDay: Boolean)

sealed interface SpeedState {
    data object Idle : SpeedState
    data class Running(val mbps: Double) : SpeedState
    data class Done(val mbps: Double) : SpeedState
    data object Failed : SpeedState
}
