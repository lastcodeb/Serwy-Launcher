package dev.mibox.neon.i18n

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process

/**
 * The box's language changed. Strings, month names and team labels are partly computed once per
 * process, so the simplest correct thing is to start over: Android relaunches the home screen at
 * once, now entirely in the new language. The user is in Settings when this happens, not here.
 */
class LocaleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_LOCALE_CHANGED) Process.killProcess(Process.myPid())
    }
}
