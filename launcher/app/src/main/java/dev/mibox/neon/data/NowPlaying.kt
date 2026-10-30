package dev.mibox.neon.data

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.app.NotificationManagerCompat

/**
 * Exists only to be enabled: an app may read other apps' media sessions while one of its
 * notification listeners is allowed. Nothing here reads notifications. Enabled from adb:
 * cmd notification allow_listener dev.mibox.neon/dev.mibox.neon.data.MediaListener
 */
class MediaListener : NotificationListenerService()

/** What another app is playing (Spotify in the background, a paused YouTube video…). */
data class NowPlaying(
    val pkg: String,
    val title: String,
    val artist: String?,
    val art: ImageBitmap?,
    val playing: Boolean,
    val canPrev: Boolean,
    val canNext: Boolean,
)

/**
 * Follows the system's media sessions while started: the session that is playing wins, else the
 * newest paused one with a title. Callbacks run on the main thread.
 */
class NowPlayingWatcher(private val ctx: Context, private val onChange: (NowPlaying?) -> Unit) {
    private val msm = ctx.getSystemService(MediaSessionManager::class.java)
    private val listener = ComponentName(ctx, MediaListener::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var controllers: List<MediaController> = emptyList()
    private var current: MediaController? = null
    private var started = false

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { list -> follow(list.orEmpty()) }

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onSessionDestroyed() = publish()
    }

    /** False until the listener is allowed (adb, or [LocalAdb] from the Control Center). */
    fun hasAccess(): Boolean = ctx.packageName in NotificationManagerCompat.getEnabledListenerPackages(ctx)

    fun start() {
        if (started || !hasAccess()) {
            if (!started) onChange(null)
            return
        }
        runCatching {
            msm.addOnActiveSessionsChangedListener(sessionsChanged, listener, main)
            started = true
            follow(msm.getActiveSessions(listener))
        }.onFailure { Log.w(TAG, "media sessions unavailable", it) }
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { msm.removeOnActiveSessionsChangedListener(sessionsChanged) }
        controllers.forEach { runCatching { it.unregisterCallback(callback) } }
        controllers = emptyList()
        current = null
    }

    fun toggle() {
        val c = current ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
    }

    fun next() {
        current?.transportControls?.skipToNext()
    }

    fun previous() {
        current?.transportControls?.skipToPrevious()
    }

    private fun follow(list: List<MediaController>) {
        controllers.forEach { runCatching { it.unregisterCallback(callback) } }
        controllers = list.filter { it.packageName != ctx.packageName }
        controllers.forEach { it.registerCallback(callback, main) }
        publish()
    }

    private fun publish() {
        // The list is ordered by priority, the most recently active first
        val withTitle = controllers.filter { !it.metadata?.title().isNullOrBlank() }
        val pick = withTitle.firstOrNull { it.playbackState?.state in Playing }
            ?: withTitle.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PAUSED }
        current = pick
        onChange(pick?.let(::describe))
    }

    private fun describe(c: MediaController): NowPlaying {
        val m = c.metadata
        val actions = c.playbackState?.actions ?: 0L
        val art: Bitmap? = m?.let {
            it.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: it.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: it.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        }
        return NowPlaying(
            pkg = c.packageName,
            title = m.title().orEmpty(),
            artist = m?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: m?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
                ?: m?.getString(MediaMetadata.METADATA_KEY_ALBUM),
            art = art?.let { runCatching { it.asImageBitmap() }.getOrNull() },
            playing = c.playbackState?.state in Playing,
            canPrev = actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L,
            canNext = actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L,
        )
    }

    private fun MediaMetadata?.title(): String? =
        this?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: this?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)

    private companion object {
        const val TAG = "NeonMedia"
        val Playing = setOf(PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING)
    }
}
