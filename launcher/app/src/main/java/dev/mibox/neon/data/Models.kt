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

@Immutable
data class HeroState(val app: AppEntry, val resume: Boolean)

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
