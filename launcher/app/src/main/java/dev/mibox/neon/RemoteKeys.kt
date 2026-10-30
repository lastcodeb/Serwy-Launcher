package dev.mibox.neon

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * The app buttons on the Mi remote (YouTube, Netflix, Prime Video, TV+, Apps). Xiaomi's GlobalKey
 * service hands them to Google TV Home (launcherx), which is disabled on this box, so they did
 * nothing. As an accessibility service with key filtering this sees them first, opens the app
 * itself and swallows the key. Every other key passes through untouched.
 *
 * Enabled from adb (install.ps1): settings put secure enabled_accessibility_services
 * dev.mibox.neon/dev.mibox.neon.RemoteKeys
 */
class RemoteKeys : AccessibilityService() {

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_ALL_APPS) {
            // Android's own app list lives in Google TV Home, which is disabled: show ours
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .putExtra(MainActivity.EXTRA_ALL_APPS, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            return true
        }
        val target = Buttons[event.keyCode] ?: return false
        // Launch on the first press only; the release and auto-repeats are swallowed too
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            Log.i(TAG, "remote key ${event.keyCode} -> ${target.pkg}")
            open(target)
        }
        return true
    }

    private fun open(t: Target) {
        val special = t.action?.let { a ->
            Intent(a).setPackage(t.pkg).apply { t.activity?.let { component = ComponentName(t.pkg, it) } }
        }
        val plain = packageManager.getLeanbackLaunchIntentForPackage(t.pkg)
            ?: packageManager.getLaunchIntentForPackage(t.pkg)
        for (intent in listOfNotNull(special, plain)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            if (runCatching { startActivity(intent) }.onFailure { Log.w(TAG, "can't start $intent", it) }.isSuccess) return
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    /** [action]/[activity]: the intent the app expects from its own button, when it has one. */
    private class Target(val pkg: String, val action: String? = null, val activity: String? = null)

    private companion object {
        const val TAG = "NeonKeys"

        // Key codes from /vendor/usr/keylayout/Vendor_2717_Product_32b9.kl (the Mi remote)
        val Buttons = mapOf(
            KeyEvent.KEYCODE_BUTTON_3 to Target("com.google.android.youtube.tv"),
            KeyEvent.KEYCODE_BUTTON_6 to Target("com.netflix.ninja", action = "com.netflix.action.NETFLIX_KEY_START"),
            KeyEvent.KEYCODE_BUTTON_7 to Target(
                "com.amazon.amazonvideo.livingroom",
                action = "com.amazon.amazonvideo.livingroom.AMAZON_BUTTON",
                activity = "com.amazon.ignition.IgnitionActivity",
            ),
            // Xiaomi TV+ (its own app is disabled): Apple TV
            KeyEvent.KEYCODE_BUTTON_5 to Target("com.apple.atve.androidtv.appletv"),
        )
    }
}
