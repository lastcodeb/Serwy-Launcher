package dev.mibox.neon.data

import android.content.Context
import android.util.Log
import dadb.AdbKeyPair
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The box's own adbd, reached over the network port it already listens on (adb tcpip 5555). An
 * app may only ask Android to drop *cached* processes; a real `am force-stop` needs the shell.
 * The first connection makes the TV ask "Allow debugging?" for this app's key; after "Always
 * allow" it is silent. The key never leaves the app's private files.
 */
class LocalAdb(private val ctx: Context) {

    sealed interface Result {
        /** [stopped] packages were force-stopped. */
        data class Done(val stopped: List<String>) : Result

        /** adbd did not accept us (yet): the TV is showing, or the user declined, the approval. */
        data object NotAllowed : Result
    }

    /**
     * Force-stops every app in [candidates] that has a running process, except [keep]. Returns
     * [Result.NotAllowed] when the connection is refused or not approved within [timeoutMs].
     */
    suspend fun forceStopRunning(candidates: Set<String>, keep: Set<String>, timeoutMs: Long = 30_000): Result =
        withContext(Dispatchers.IO) {
            val adb = runCatching { Dadb.create(HOST, PORT, keyPair()) }.getOrElse {
                Log.w(TAG, "adb connect failed", it)
                return@withContext Result.NotAllowed
            }
            coroutineScope {
                // A pending approval blocks the first read; closing the socket ends the wait
                val watchdog = async {
                    delay(timeoutMs)
                    runCatching { adb.close() }
                }
                try {
                    val running = adb.shell("ps -A -o NAME").output.lineSequence()
                        .map { it.trim().substringBefore(':') }
                        .filter { it.isNotEmpty() }
                        .toSet()
                    val targets = candidates.filter { it in running && it !in keep }
                    for (pkg in targets) adb.shell("am force-stop $pkg")
                    Log.i(TAG, "force-stopped $targets")
                    Result.Done(targets)
                } catch (e: Exception) {
                    Log.w(TAG, "adb shell failed", e)
                    Result.NotAllowed
                } finally {
                    watchdog.cancel()
                    runCatching { adb.close() }
                }
            }
        }

    private fun keyPair(): AdbKeyPair {
        val dir = File(ctx.filesDir, "adb").apply { mkdirs() }
        val priv = File(dir, "adbkey")
        val pub = File(dir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
        return AdbKeyPair.read(priv, pub)
    }

    private companion object {
        const val TAG = "NeonAdb"
        const val HOST = "127.0.0.1"
        const val PORT = 5555
    }
}
