package dev.mibox.neon.dream

import android.service.dreams.DreamService
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.mibox.neon.data.Prefs
import dev.mibox.neon.data.Wiz
import dev.mibox.neon.data.WizPilot
import dev.mibox.neon.ui.NeonTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The Neon screensaver. Only runs while the system dreams (not a background service): slow cover
 * art from Watch Next and the apps' channels, a big clock and the weather. With ambient on, the
 * WiZ backlight takes each cover's color and is put back when the dream ends.
 */
class NeonDream : DreamService(), LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var wizIp: String? = null
    @Volatile private var before: WizPilot? = null
    // The sky sends its color on the first frame, before the light is ready: keep it for then
    @Volatile private var lastColor: Color? = null

    override fun onCreate() {
        super.onCreate()
        savedState.performRestore(null)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = true
        // Compose looks for its owners on the window's root view
        window.decorView.setViewTreeLifecycleOwner(this)
        window.decorView.setViewTreeSavedStateRegistryOwner(this)
        setContentView(
            ComposeView(this).apply {
                setContent { NeonTheme { DreamScreen(onColor = ::tintLight) } }
            },
        )
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        io.launch {
            val prefs = Prefs(this@NeonDream)
            if (!prefs.ambient.first()) return@launch
            val ip = prefs.wizIp.first() ?: return@launch
            // The launcher puts its own ambient color back when it pauses: let that land first
            delay(1500)
            val p = Wiz.pilot(ip) ?: return@launch
            if (!p.on) return@launch // never switch on a light that is off
            before = p
            wizIp = ip
            lastColor?.let { Wiz.set(ip, Wiz.color(it, p.dimming)) }
        }
    }

    private fun tintLight(c: Color) {
        lastColor = c
        val ip = wizIp ?: return
        val dim = before?.dimming ?: return
        io.launch { Wiz.set(ip, Wiz.color(c, dim)) }
    }

    override fun onDreamingStopped() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        val ip = wizIp
        val p = before
        wizIp = null
        // Plain thread: the scope is cancelled right after, the restore must still go out
        if (ip != null && p != null) Thread { Wiz.set(ip, p.restoreParams()) }.start()
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        io.cancel()
        super.onDetachedFromWindow()
    }
}
