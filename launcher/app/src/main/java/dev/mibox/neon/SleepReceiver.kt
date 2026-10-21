package dev.mibox.neon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.mibox.neon.data.Wiz
import org.json.JSONObject

/** Fired by the sleep-timer alarm: dims the backlight, then switches it off. No service needed. */
class SleepReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ip = intent.getStringExtra(EXTRA_IP) ?: return
        val pending = goAsync()
        Thread {
            try {
                Wiz.set(ip, JSONObject().put("dimming", Wiz.MIN_DIM))
                Thread.sleep(4000)
                Wiz.set(ip, JSONObject().put("state", false))
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        const val EXTRA_IP = "ip"
    }
}
