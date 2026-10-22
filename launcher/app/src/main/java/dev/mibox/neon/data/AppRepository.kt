package dev.mibox.neon.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.Settings
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.palette.graphics.Palette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.Locale

class AppRepository(private val ctx: Context) {
    private val pm = ctx.packageManager

    // The UI is laid out in a fixed 960dp-wide design space; render bitmaps at the real pixel
    // size of that space so logos stay sharp on a 1080p and a 4K UI alike.
    private val pxPerDp = ctx.resources.displayMetrics.let { maxOf(it.widthPixels, it.heightPixels) } / DESIGN_WIDTH_DP
    private val bannerW = (FOCUSED_CARD_W_DP * pxPerDp).toInt()
    private val bannerH = bannerW * 9 / 16
    private val iconPx = (ICON_DP * pxPerDp).toInt()
    // The backdrop logo is drawn at 9% opacity: half resolution is indistinguishable there
    private val heroIconPx = (HERO_ICON_DP * pxPerDp / 2).toInt()

    // Two decoder threads keep the UI thread and the weak CPU free while banners load
    @OptIn(ExperimentalCoroutinesApi::class)
    private val loader = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(2))
    private val arts = HashMap<String, Deferred<AppArt?>>()

    /** Leanback apps first, then phone-style apps that only declare LAUNCHER. */
    fun load(): List<AppEntry> {
        val result = LinkedHashMap<String, AppEntry>()
        fun add(category: String, leanback: Boolean) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (ri in pm.queryIntentActivities(intent, 0)) {
                val ai = ri.activityInfo
                if (ai.packageName == ctx.packageName || ai.packageName in result) continue
                result[ai.packageName] = AppEntry(
                    pkg = ai.packageName,
                    label = ri.loadLabel(pm).toString(),
                    component = ComponentName(ai.packageName, ai.name),
                    leanback = leanback,
                )
            }
        }
        add(Intent.CATEGORY_LEANBACK_LAUNCHER, leanback = true)
        add(Intent.CATEGORY_LAUNCHER, leanback = false)
        val collator = Collator.getInstance(Locale.getDefault())
        return result.values.sortedWith { a, b -> collator.compare(a.label, b.label) }
    }

    /** Packages that can act as a home screen (other launchers, setup fallbacks). */
    fun homePackages(): Set<String> = homes ?: pm
        .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
        .mapTo(HashSet()) { it.activityInfo.packageName }
        .also { homes = it }

    @Volatile
    private var homes: Set<String>? = null

    suspend fun art(app: AppEntry): AppArt? =
        synchronized(arts) {
            arts.getOrPut(app.pkg) { loader.async { runCatching { render(app) }.getOrNull() } }
        }.await()

    /** A Watch Next thumbnail, cropped to the card in the UI; vibrant color for its glow. */
    suspend fun watchArt(item: WatchItem): AppArt? {
        val uri = item.imageUri ?: return null
        return synchronized(arts) {
            arts.getOrPut("watch:${item.id}:$uri") {
                loader.async {
                    runCatching {
                        val bmp = WatchNext.loadThumbnail(ctx, uri, bannerW, bannerH) ?: return@runCatching null
                        bmp.prepareToDraw()
                        val p = Palette.from(bmp).maximumColorCount(16).generate()
                        val vibrant = Color(p.getVibrantColor(p.getDominantColor(FALLBACK_VIBRANT)))
                        AppArt(bmp.asImageBitmap(), isBanner = true, Color.Black, Color.Black, vibrant)
                    }.getOrNull()
                }
            }
        }.await()
    }

    fun launchWatch(item: WatchItem): Boolean {
        val intent = item.intentUri?.let { runCatching { Intent.parseUri(it, Intent.URI_INTENT_SCHEME) }.getOrNull() }
            ?: return false
        // Some apps (Netflix) publish a plain https link: pin it to the publishing app, or Android
        // shows an "Open with" chooser between the app, a browser and a downloader
        if (intent.component == null && intent.`package` == null) intent.setPackage(item.pkg)
        return runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    }

    fun invalidate(pkg: String) {
        synchronized(arts) { arts.remove(pkg) }
        homes = null
    }

    fun launch(app: AppEntry) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(if (app.leanback) Intent.CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER)
            .setComponent(app.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        runCatching { ctx.startActivity(intent) }
    }

    fun openAppInfo(pkg: String) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(intent) }
    }

    fun openSystemSettings() {
        runCatching { ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** Large app icon for the faint backdrop logo behind the hero; a few recent ones are kept. */
    suspend fun heroIcon(app: AppEntry): ImageBitmap? = withContext(Dispatchers.IO) {
        synchronized(heroCache) { heroCache[app.pkg] }
            ?: runCatching {
                val bmp = draw(pm.getActivityInfo(app.component, 0).loadIcon(pm), heroIconPx, heroIconPx).asImageBitmap()
                synchronized(heroCache) { heroCache[app.pkg] = bmp }
                bmp
            }.getOrNull()
    }

    // Access-ordered LRU: scrolling the row keeps re-showing the same few heroes
    private val heroCache = object : LinkedHashMap<String, ImageBitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>) = size > HERO_CACHE
    }

    private fun render(app: AppEntry): AppArt {
        val ai = pm.getActivityInfo(app.component, 0)
        val banner = ai.loadBanner(pm) ?: ai.applicationInfo.loadBanner(pm)
        val bitmap = if (banner != null) draw(banner, bannerW, bannerH) else draw(ai.loadIcon(pm), iconPx, iconPx)
        val palette = Palette.from(bitmap).maximumColorCount(16).generate()
        val vibrant = Color(palette.getVibrantColor(palette.getLightVibrantColor(palette.getDominantColor(FALLBACK_VIBRANT))))
        val fallback = Color(palette.getDominantColor(FALLBACK_DEEP))
        // Banners are often gradients: fill above/below the uncropped banner with its own top
        // and bottom edge colors, so the logo sits on the card with no visible seam
        val top = if (banner != null) rowColor(bitmap, 1) ?: fallback else readable(vibrant)
        val bottom = if (banner != null) rowColor(bitmap, bitmap.height - 2) ?: fallback else top
        // Fully opaque banners (most of them) need no alpha channel: RGB_565 halves their memory
        val stored = if (banner != null && isOpaque(bitmap)) {
            bitmap.copy(Bitmap.Config.RGB_565, false).also { bitmap.recycle() }
        } else {
            bitmap
        }
        stored.prepareToDraw()
        return AppArt(stored.asImageBitmap(), banner != null, top, bottom, vibrant)
    }

    private fun isOpaque(bmp: Bitmap): Boolean {
        val w = bmp.width - 1
        val h = bmp.height - 1
        return listOf(0 to 0, w to 0, 0 to h, w to h, w / 2 to 0, w / 2 to h)
            .all { (x, y) -> android.graphics.Color.alpha(bmp.getPixel(x, y)) == 255 }
    }

    /** Home went to the background: drop what is cheap to rebuild, keep the app banners. */
    fun trimCaches() {
        synchronized(arts) { arts.keys.removeAll { it.startsWith("watch:") } }
        synchronized(heroCache) { heroCache.clear() }
    }

    /** Average color of one pixel row; null when it is mostly transparent. */
    private fun rowColor(bmp: Bitmap, y: Int): Color? {
        val px = IntArray(bmp.width)
        bmp.getPixels(px, 0, bmp.width, 0, y, bmp.width, 1)
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0
        for (c in px) {
            if (android.graphics.Color.alpha(c) < 200) continue
            r += android.graphics.Color.red(c)
            g += android.graphics.Color.green(c)
            b += android.graphics.Color.blue(c)
            n++
        }
        if (n < px.size / 2) return null
        return Color((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    /** Darkens a color until white text on it stays readable. */
    private fun readable(c: Color): Color {
        var out = c
        while (out.luminance() > 0.35f) out = lerp(out, Color.Black, 0.15f)
        return out
    }

    private fun draw(d: Drawable, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, w, h)
        d.draw(Canvas(bmp))
        return bmp
    }

    private companion object {
        const val DESIGN_WIDTH_DP = 960f
        const val FOCUSED_CARD_W_DP = 147
        const val ICON_DP = 44
        const val HERO_ICON_DP = 230
        const val HERO_CACHE = 3
        const val FALLBACK_VIBRANT = 0xFF8B5CF6.toInt()
        const val FALLBACK_DEEP = 0xFF1E1A3D.toInt()
    }
}
