package dev.mibox.neon.data

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context

class SystemStats(private val ctx: Context) {
    private val am = ctx.getSystemService(ActivityManager::class.java)

    fun ram(): Ram {
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return Ram(mi.totalMem, mi.availMem)
    }

    /** Only cached/background processes die; whatever is on screen is untouched. */
    @Suppress("DEPRECATION")
    fun killBackground() {
        for (p in ctx.packageManager.getInstalledPackages(0)) {
            if (p.packageName != ctx.packageName) am.killBackgroundProcesses(p.packageName)
        }
    }

    /** Apps among [candidates], most recently visible first; empty without the usage-stats appop. */
    fun recent(candidates: Set<String>, limit: Int): List<String> {
        val usm = ctx.getSystemService(UsageStatsManager::class.java) ?: return emptyList()
        val now = System.currentTimeMillis()
        val lastSeen = HashMap<String, Long>()
        for (s in usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - 14 * DAY_MS, now)) {
            if (s.packageName !in candidates || s.lastTimeVisible <= 0) continue
            lastSeen[s.packageName] = maxOf(lastSeen[s.packageName] ?: 0L, s.lastTimeVisible)
        }
        return lastSeen.entries.sortedByDescending { it.value }.take(limit).map { it.key }
    }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
