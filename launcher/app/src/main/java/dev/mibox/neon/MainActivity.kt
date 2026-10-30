package dev.mibox.neon

import android.content.ComponentCallbacks2
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import dev.mibox.neon.ui.LauncherScreen
import dev.mibox.neon.ui.NeonTheme

class MainActivity : ComponentActivity() {
    private val vm: LauncherViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A home screen has nowhere to go back to
        onBackPressedDispatcher.addCallback(this) { vm.onBack() }
        if (intent?.getBooleanExtra(EXTRA_ALL_APPS, false) == true) vm.requestAllApps()
        setContent {
            NeonTheme {
                LauncherScreen(vm)
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Any remote key ends the idle showcase
        vm.onUserInput()
        return super.dispatchKeyEvent(event)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // UI_HIDDEN: another app is in front, so cover art and hero logos can go
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) vm.trimMemory()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        vm.onHomePressed()
        if (intent.getBooleanExtra(EXTRA_ALL_APPS, false)) vm.requestAllApps()
    }

    companion object {
        /** Set by [RemoteKeys] for the remote's Apps key: open the grid of every app. */
        const val EXTRA_ALL_APPS = "dev.mibox.neon.ALL_APPS"
    }
}
