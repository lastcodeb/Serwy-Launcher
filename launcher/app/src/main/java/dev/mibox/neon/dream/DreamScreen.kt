package dev.mibox.neon.dream

import android.content.Context
import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.tv.material3.Text
import dev.mibox.neon.data.City
import dev.mibox.neon.data.DefaultCity
import dev.mibox.neon.data.Prefs
import dev.mibox.neon.data.Weather
import dev.mibox.neon.data.WeatherNow
import dev.mibox.neon.ui.Neon
import dev.mibox.neon.ui.Poppins
import dev.mibox.neon.ui.TR
import dev.mibox.neon.ui.rememberNow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val BigTime = DateTimeFormatter.ofPattern("HH:mm", TR)
private val LongDate = DateTimeFormatter.ofPattern("d MMMM EEEE", TR)
private const val CACHE_BYTES = 100L * 1024 * 1024

/** Text over moving footage needs a shadow, not a box. */
private val Shade = Shadow(Color.Black.copy(alpha = 0.6f), blurRadius = 18f)

/**
 * The screensaver: real sea-and-sky footage from Pixabay, picked by the real time of day and
 * weather in the user's city, streamed through a 100 MB cache. Clock and weather in front.
 * [onColor] receives the scene's light color for the WiZ backlight.
 */
@Composable
fun DreamScreen(onColor: (Color) -> Unit) {
    val ctx = LocalContext.current
    val now by rememberNow()
    val city by produceState<City>(DefaultCity) { value = Prefs(ctx).city.first() }
    val weather by produceState<WeatherNow?>(null, city) {
        value = withContext(Dispatchers.IO) { runCatching { Weather.now(city) }.getOrNull() }
    }
    val sun by produceState<Pair<LocalTime, LocalTime>?>(null, city) {
        value = withContext(Dispatchers.IO) { runCatching { Weather.sun(city) }.getOrNull() }
    }
    // Re-evaluated each minute; the mood (and its clips) only changes a few times a day
    val mood = SkyVideos.moodFor(now.toLocalTime(), sun, weather?.code)
    LaunchedEffect(mood.light) { onColor(mood.light) }

    val uhd = remember { supportsUhd(ctx) }
    val clips by produceState(emptyList<SkyClip>(), mood.key) {
        value = withContext(Dispatchers.IO) { runCatching { SkyVideos.clips(ctx, mood, uhd) }.getOrDefault(emptyList()) }
    }

    var showing by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Neon.Bg1)) {
        if (clips.isNotEmpty()) {
            VideoLayer(clips, onPlaying = { showing = it })
        }
        // Fades to black between clips and while the first one buffers
        val veil by animateFloatAsState(if (showing) 0f else 1f, tween(700), label = "veil")
        Box(Modifier.fillMaxSize().alpha(veil).background(Color.Black))

        // A soft corner shade so the clock reads on bright skies
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    drawRect(
                        Brush.radialGradient(
                            listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent),
                            center = Offset(0f, size.height),
                            radius = size.width * 0.7f,
                        ),
                    )
                },
        )

        // Burn-in care: the block drifts a few dp each minute
        val drift = (now.minute % 6) * 3
        val w = weather
        val text = TextStyle(fontFamily = Poppins, color = Color.White, shadow = Shade)
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .offset(x = drift.dp, y = (-drift).dp)
                .padding(start = 48.dp, bottom = 40.dp),
        ) {
            Text(now.format(BigTime), style = text.copy(fontSize = 96.sp, fontWeight = FontWeight.Bold, letterSpacing = (-2).sp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(now.format(LongDate), style = text.copy(fontSize = 17.sp, color = Color.White.copy(alpha = 0.9f)))
                if (w != null) {
                    Spacer(Modifier.width(18.dp))
                    Text(
                        "${w.tempC.roundToInt()}° · ${Weather.describe(w.code, w.isDay).second} · ${city.name}",
                        style = text.copy(fontSize = 17.sp, color = Color.White.copy(alpha = 0.9f)),
                    )
                }
            }
        }
    }
}

/** ExoPlayer on a SurfaceView: the video bypasses the UI and plays at the panel's own resolution. */
@OptIn(UnstableApi::class)
@Composable
private fun VideoLayer(clips: List<SkyClip>, onPlaying: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val player = remember(clips) {
        ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(DefaultMediaSourceFactory(cachedDataSource(ctx)))
            .build()
            .apply {
                volume = 0f
                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                repeatMode = Player.REPEAT_MODE_ALL
                shuffleModeEnabled = true
                setMediaItems(clips.map { MediaItem.fromUri(it.url) })
                prepare()
                playWhenReady = true
            }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) = onPlaying(isPlaying)
        }
        player.addListener(listener)
        onPlaying(false)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    AndroidView(
        factory = { SurfaceView(it).also(player::setVideoSurfaceView) },
        modifier = Modifier.fillMaxSize(),
    )
}

@OptIn(UnstableApi::class)
private fun cachedDataSource(ctx: Context): CacheDataSource.Factory = CacheDataSource.Factory()
    .setCache(SkyCache.get(ctx))
    .setUpstreamDataSourceFactory(DefaultHttpDataSource.Factory().setUserAgent("SerwyLauncher"))
    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

/** One SimpleCache per process (it refuses two instances on the same folder). */
@OptIn(UnstableApi::class)
private object SkyCache {
    private var cache: SimpleCache? = null

    @Synchronized
    fun get(ctx: Context): SimpleCache = cache ?: SimpleCache(
        File(ctx.cacheDir, "sky"),
        LeastRecentlyUsedCacheEvictor(CACHE_BYTES),
        StandaloneDatabaseProvider(ctx),
    ).also { cache = it }
}

/** 4K files only when the HDMI output really is 4K; otherwise 1080p saves bandwidth. */
private fun supportsUhd(ctx: Context): Boolean {
    val display = (ctx.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager)
        .getDisplay(android.view.Display.DEFAULT_DISPLAY) ?: return false
    return display.supportedModes.any { it.physicalWidth >= 3840 } && display.mode.physicalWidth >= 3840
}
