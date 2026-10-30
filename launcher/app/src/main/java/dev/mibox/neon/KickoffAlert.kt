package dev.mibox.neon

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * "Maç başlıyor" card over whatever is on screen, a minute before kickoff. It lives in an
 * accessibility overlay of [RemoteKeys], so it needs no extra permission and never takes focus
 * from the app playing underneath: the service reads OK (open the match app) and Back (close)
 * itself while the card shows. It goes away on its own after [SHOW_MS].
 */
class KickoffAlert(private val service: AccessibilityService) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var view: View? = null
    private var target: String? = null

    val showing: Boolean get() = view != null

    /** [teams] like "Galatasaray – Konyaspor"; OK opens [pkg] (the match app). */
    fun show(teams: String, pkg: String?) = main.post {
        hide()
        target = pkg
        val v = build(teams, pkg?.let(::label))
        runCatching {
            wm.addView(
                v,
                WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    y = dp(36)
                    windowAnimations = android.R.style.Animation_Toast
                },
            )
            view = v
            main.postDelayed(::hide, SHOW_MS)
        }.onFailure { Log.w(TAG, "can't show the kickoff card", it) }
    }

    fun hide() {
        main.removeCallbacksAndMessages(null)
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }

    /** OK on the card: the match app, in front. */
    fun open() {
        val pkg = target
        hide()
        if (pkg == null) return
        val pm = service.packageManager
        val intent = (pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        runCatching { service.startActivity(intent) }.onFailure { Log.w(TAG, "can't open $pkg", it) }
    }

    private fun label(pkg: String): String? =
        runCatching { service.packageManager.run { getApplicationLabel(getApplicationInfo(pkg, 0)).toString() } }.getOrNull()

    private fun build(teams: String, app: String?): View {
        val ctx = service
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(Color.parseColor("#F0120E14"))
                setStroke(dp(2), Yellow)
            }
            clipToOutline = true
            elevation = dp(12).toFloat()
        }
        // The club's colors down the left edge
        card.addView(
            View(ctx).apply {
                background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Red, Yellow))
            },
            LinearLayout.LayoutParams(dp(10), LinearLayout.LayoutParams.MATCH_PARENT),
        )
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(26), dp(18), dp(34), dp(18))
        }
        body.addView(text(dev.mibox.neon.i18n.t("⚽  Maç başlıyor"), 26f, Color.WHITE, bold = true))
        body.addView(text(dev.mibox.neon.i18n.t("{0} · 1 dk sonra", teams), 17f, Color.parseColor("#E6FFFFFF")))
        body.addView(
            text(
                if (app != null) {
                    dev.mibox.neon.i18n.t("OK: {0}'te izle   ·   Geri: kapat", app)
                } else {
                    dev.mibox.neon.i18n.t("Geri: kapat")
                },
                14f,
                Yellow,
                bold = true,
            ).apply { setPadding(0, dp(8), 0, 0) },
        )
        card.addView(body)
        return card
    }

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(service).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun dp(v: Int): Int = (v * service.resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "NeonKickoff"
        private const val SHOW_MS = 75_000L
        private val Red = Color.parseColor("#E3263B")
        private val Yellow = Color.parseColor("#FDB912")

        /** Fallback match app when none is chosen in the match panel: beIN CONNECT. */
        const val BEIN = "com.digiturk.iq.mobil"
    }
}
