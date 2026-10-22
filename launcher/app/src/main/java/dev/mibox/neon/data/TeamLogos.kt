package dev.mibox.neon.data

import android.content.Context
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/**
 * Club crests from ESPN's CDN, 128 px each, kept for the life of the process: the match chip,
 * the scoreboard and every theme ask for the same two or three ids.
 */
object TeamLogos {
    private const val SIZE_PX = 128
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = HashMap<String, Deferred<ImageBitmap?>>()

    suspend fun get(ctx: Context, espnId: String): ImageBitmap? {
        if (espnId.isBlank()) return null
        val app = ctx.applicationContext
        val job = synchronized(cache) {
            cache.getOrPut(espnId) {
                scope.async {
                    WatchNext.loadImage(app, TeamCodes.logoUrl(espnId), SIZE_PX, SIZE_PX)
                        ?.also { it.prepareToDraw() }
                        ?.asImageBitmap()
                }
            }
        }
        // A failed download (offline at boot) must not stick for the whole session
        return job.await().also { if (it == null) synchronized(cache) { cache.remove(espnId) } }
    }
}
