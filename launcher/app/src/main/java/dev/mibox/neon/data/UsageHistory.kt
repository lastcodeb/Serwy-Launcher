package dev.mibox.neon.data

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import android.util.AtomicFile
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.pow
import dev.mibox.neon.i18n.t

@Immutable
data class AppUsage(val pkg: String, val label: String, val hours: Double, val share: Double)

@Immutable
data class LongestSession(val pkg: String, val label: String, val minutes: Int)

enum class PersonaKind { NIGHT_OWL, EARLY_BIRD, WEEKEND_MARATHONER, LOYALIST, EXPLORER, BALANCED, LIGHT }

/** [title] is the Turkish label, [blurb] the one-line reason. */
@Immutable
data class Persona(val kind: PersonaKind, val title: String, val blurb: String)

/**
 * One month of TV. Fields that need per-day detail are null when the month only exists as a
 * monthly total from the system ([approximate] = true).
 */
@Immutable
data class WrappedSummary(
    val month: YearMonth,
    val totalHours: Double,
    val topApps: List<AppUsage>,
    val busiestWeekday: DayOfWeek?,
    val busiestHour: Int?,
    val longestSession: LongestSession?,
    /** Longest run of consecutive days with at least 5 minutes of use in this month. */
    val streakDays: Int?,
    val distinctApps: Int,
    /** Days of this month with any data. */
    val activeDays: Int,
    /** Versus the previous month, in percent; null when there is nothing to compare with. */
    val changePercent: Int?,
    val persona: Persona,
    val approximate: Boolean,
)

/**
 * Compact long-term app usage, built from UsageStatsManager events (the system only keeps a week
 * or so of them). Two small JSON files in filesDir, rewritten atomically:
 *
 *  - `usage_days.json`: per day and package foreground ms, per-hour totals, the longest session,
 *    and monthly totals for months older than the events reach. ~400 days are kept.
 *  - `usage_slots.json`: per package a 7x24 hour-of-week weight, exponentially decayed
 *    (half-life 4 weeks); this is what [predict] reads.
 *
 * Nothing is held in memory between calls; every method parses, works and lets go. Home packages,
 * this app, system UI and the screensaver never count.
 */
class UsageHistory(private val ctx: Context) {
    private val daysFile = AtomicFile(File(ctx.filesDir, "usage_days.json"))
    private val slotsFile = AtomicFile(File(ctx.filesDir, "usage_slots.json"))
    private val meta = ctx.getSharedPreferences("usage_meta", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val zone: ZoneId get() = ZoneId.systemDefault()

    /** True while the usage-stats appop is granted (install.ps1 does it with `appops set`). */
    fun hasAccess(): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java) ?: return false
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Reads every event since the last call and folds finished sessions into the files. [allowed]
     * are the launchable packages, [excluded] the ones that never count (homes, this app…).
     * Returns true when something was added.
     */
    suspend fun ingest(allowed: Set<String>, excluded: Set<String>, now: Long = System.currentTimeMillis()): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (!hasAccess()) return@withLock false
                val usm = ctx.getSystemService(UsageStatsManager::class.java) ?: return@withLock false
                val booted = meta.getBoolean(KEY_BOOT, false)
                val from = if (booted) meta.getLong(KEY_CURSOR, now - 2 * DAY_MS) else now - BOOT_LOOKBACK_MS
                val carry = if (booted) readCarry() else emptyMap()
                val scan = runCatching { scan(usm, from, now, allowed, excluded, carry) }.getOrNull() ?: return@withLock false

                val days = readDays()
                val slots = readSlots(now)
                for (s in scan.sessions) addSession(days, slots, s, now)
                var changed = scan.sessions.isNotEmpty()
                var settled = booted
                if (!booted) {
                    val found = bootstrapTotals(usm, allowed, excluded, now, scan.sessions, days)
                    changed = changed || found
                    // With no data at all (appop just granted, fresh system) bootstrap again next time
                    settled = found || scan.sessions.isNotEmpty()
                }
                prune(days, now)
                if (changed) {
                    writeDays(days)
                    writeSlots(slots, now)
                }
                if (settled) finishBoot(now, scan.open)
                scan.sessions.isNotEmpty()
            }
        }

    /**
     * The package this box is usually used at this hour, or null when the history doesn't give a
     * confident answer (fewer than ~2 sessions' worth of weight, or under 35 % of the slot).
     * Weekdays and weekend days are kept apart; the two neighbouring hours count half.
     */
    suspend fun predict(now: Long = System.currentTimeMillis(), allowed: Set<String>? = null): String? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val w = readSlots(now).weights
                val at = Instant.ofEpochMilli(now).atZone(zone)
                val hour = at.hour
                val weekend = at.dayOfWeek == DayOfWeek.SATURDAY || at.dayOfWeek == DayOfWeek.SUNDAY
                val days = if (weekend) listOf(5, 6) else listOf(0, 1, 2, 3, 4)
                var total = 0.0
                var best = 0.0
                var bestPkg: String? = null
                for ((pkg, arr) in w) {
                    if (allowed != null && pkg !in allowed) continue
                    var score = 0.0
                    for (d in days) {
                        score += arr[d * 24 + hour]
                        if (hour > 0) score += 0.5 * arr[d * 24 + hour - 1]
                        if (hour < 23) score += 0.5 * arr[d * 24 + hour + 1]
                    }
                    total += score
                    if (score > best) {
                        best = score
                        bestPkg = pkg
                    }
                }
                bestPkg?.takeIf { best >= MIN_SCORE && best / total >= MIN_SHARE }
            }
        }

    /** Months that have any data, newest first. */
    suspend fun months(): List<YearMonth> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val d = readDays()
            val out = HashSet<YearMonth>()
            d.optJSONObject("days")?.keys()?.forEach { out += monthOfDay(it) }
            d.optJSONObject("months")?.keys()?.forEach { out += YearMonth.of(it.substring(0, 4).toInt(), it.substring(4).toInt()) }
            out.sortedDescending()
        }
    }

    /** The summary of [month], or null without data. [label] turns a package into an app name. */
    suspend fun wrapped(month: YearMonth, label: (String) -> String): WrappedSummary? = withContext(Dispatchers.IO) {
        mutex.withLock { summarize(readDays(), month, label) }
    }

    // --- summary ---------------------------------------------------------------------------
    private class MonthData(
        val perPkg: Map<String, Long>,
        val byWeekday: LongArray?,
        val byHour: LongArray?,
        val longest: Pair<String, Long>?,
        val activeDays: List<Int>,
        val approximate: Boolean,
    ) {
        val total: Long get() = perPkg.values.sum()
    }

    private fun load(d: JSONObject, month: YearMonth): MonthData? {
        val days = d.optJSONObject("days")
        val prefix = month.year * 100 + month.monthValue
        val perPkg = HashMap<String, Long>()
        val weekday = LongArray(7)
        val hours = LongArray(24)
        var longest: Pair<String, Long>? = null
        val active = ArrayList<Int>()
        var found = false
        days?.keys()?.forEach { key ->
            if (key.toInt() / 100 != prefix) return@forEach
            found = true
            val o = days.getJSONObject(key)
            val p = o.optJSONObject("p")
            p?.keys()?.forEach { perPkg.merge(it, p.getLong(it), Long::plus) }
            val t = o.optLong("t")
            val day = key.toInt() % 100
            weekday[month.atDay(day).dayOfWeek.value - 1] += t
            o.optJSONArray("h")?.let { h -> for (i in 0 until 24.coerceAtMost(h.length())) hours[i] += h.getLong(i) }
            if (t >= ACTIVE_DAY_MS) active += day
            val ls = o.optLong("ls")
            if (ls > (longest?.second ?: 0L)) longest = o.optString("lp") to ls
        }
        if (found) return MonthData(perPkg, weekday, hours, longest, active.sorted(), approximate = false)
        val agg = d.optJSONObject("months")?.optJSONObject(prefix.toString()) ?: return null
        val m = agg.keys().asSequence().associateWith { agg.getLong(it) }
        return MonthData(m, null, null, null, emptyList(), approximate = true)
    }

    private fun summarize(d: JSONObject, month: YearMonth, label: (String) -> String): WrappedSummary? {
        val cur = load(d, month) ?: return null
        val total = cur.total
        if (total < 60_000L) return null
        val prev = load(d, month.minusMonths(1))
        val top = cur.perPkg.entries.sortedByDescending { it.value }.take(5)
            .map { AppUsage(it.key, label(it.key), it.value / HOUR_MS, it.value.toDouble() / total) }
        val distinct = cur.perPkg.count { it.value >= 60_000L }
        val weekday = cur.byWeekday?.let { w -> w.indices.maxByOrNull { w[it] }?.let { DayOfWeek.of(it + 1) } }
        val hour = cur.byHour?.let { h -> h.indices.maxByOrNull { h[it] } }
        val change = prev?.total?.takeIf { it > 0 }?.let { ((total - it) * 100.0 / it).toInt() }
        return WrappedSummary(
            month = month,
            totalHours = total / HOUR_MS,
            topApps = top,
            busiestWeekday = weekday,
            busiestHour = hour,
            longestSession = cur.longest?.let { LongestSession(it.first, label(it.first), (it.second / 60_000L).toInt()) },
            streakDays = if (cur.approximate) null else longestRun(cur.activeDays),
            distinctApps = distinct,
            activeDays = cur.activeDays.size,
            changePercent = change,
            persona = persona(cur, top, distinct),
            approximate = cur.approximate,
        )
    }

    private fun longestRun(days: List<Int>): Int {
        var best = 0
        var run = 0
        var last = -10
        for (d in days) {
            run = if (d == last + 1) run + 1 else 1
            last = d
            if (run > best) best = run
        }
        return best
    }

    /**
     * Persona rules, first match wins:
     *  1. under 3 h in the month -> Hafif izleyici;
     *  2. top app >= 60 % of the time -> Sadık izleyici;
     *  3. >= 40 % of the time between 22:00 and 04:00 -> Gece kuşu;
     *  4. >= 30 % between 05:00 and 12:00 -> Sabahçı;
     *  5. >= 45 % on Saturday/Sunday (they are 29 % of a week) -> Hafta sonu maratoncusu;
     *  6. 12 or more apps used for at least a minute -> Kaşif;
     *  7. otherwise Dengeli izleyici.
     * Rules 3 to 5 need per-day data and are skipped for months known only by their monthly total.
     */
    private fun persona(m: MonthData, top: List<AppUsage>, distinct: Int): Persona {
        val total = m.total.toDouble()
        val topApp = top.firstOrNull()
        if (total / HOUR_MS < 3.0) return Persona(PersonaKind.LIGHT, t("Hafif izleyici"), t("Bu ay televizyon pek açılmadı."))
        if (topApp != null && topApp.share >= 0.60) {
            return Persona(PersonaKind.LOYALIST, t("Sadık izleyici"), t("Zamanının %{0}'ini {1} ile geçirdin.", (topApp.share * 100).toInt(), topApp.label))
        }
        val h = m.byHour
        if (h != null) {
            val night = (h[22] + h[23] + h[0] + h[1] + h[2] + h[3]) / total
            if (night >= 0.40) return Persona(PersonaKind.NIGHT_OWL, t("Gece kuşu"), t("İzlemenin %{0}'i saat 22'den sonra.", (night * 100).toInt()))
            val morning = (5..11).sumOf { h[it] } / total
            if (morning >= 0.30) return Persona(PersonaKind.EARLY_BIRD, t("Sabahçı"), t("İzlemenin %{0}'i sabah 5 ile öğlen arasında.", (morning * 100).toInt()))
        }
        val w = m.byWeekday
        if (w != null) {
            val weekend = (w[5] + w[6]) / total
            if (weekend >= 0.45) return Persona(PersonaKind.WEEKEND_MARATHONER, t("Hafta sonu maratoncusu"), t("İzlemenin %{0}'i cumartesi ve pazar günleri.", (weekend * 100).toInt()))
        }
        if (distinct >= 12) return Persona(PersonaKind.EXPLORER, t("Kaşif"), t("Bu ay {0} farklı uygulama açtın.", distinct))
        return Persona(PersonaKind.BALANCED, t("Dengeli izleyici"), t("Zamanını birkaç uygulamaya dengeli dağıttın."))
    }

    // --- event scanning --------------------------------------------------------------------
    private class Session(val pkg: String, val start: Long, var end: Long)

    private class Scan(val sessions: List<Session>, val open: Map<String, Long>)

    /**
     * Turns ACTIVITY_RESUMED / ACTIVITY_PAUSED events into per-package sessions. Activity switches
     * inside one app (a gap under 5 s) stay one session; the screen going off or a shutdown ends
     * everything. Sessions still open at [to] are carried to the next call instead of committed.
     */
    private fun scan(
        usm: UsageStatsManager, from: Long, to: Long, allowed: Set<String>, excluded: Set<String>, carry: Map<String, Long>,
    ): Scan {
        val cur = HashMap<String, Session>()
        val open = HashSet<String>()
        val done = ArrayList<Session>()
        for ((pkg, start) in carry) {
            cur[pkg] = Session(pkg, start, start)
            open += pkg
        }
        fun flush(s: Session) {
            val end = minOf(s.end, s.start + MAX_SESSION_MS)
            if (end - s.start >= MIN_SESSION_MS) done += Session(s.pkg, s.start, end)
        }
        val events = usm.queryEvents(from, to)
        val ev = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            val t = ev.timeStamp
            val pkg = ev.packageName ?: continue
            when (ev.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    if (pkg in excluded || pkg !in allowed || pkg in open) continue
                    val s = cur[pkg]
                    if (s != null && t - s.end <= MERGE_GAP_MS) {
                        open += pkg
                    } else {
                        s?.let(::flush)
                        cur[pkg] = Session(pkg, t, t)
                        open += pkg
                    }
                }
                UsageEvents.Event.ACTIVITY_PAUSED -> if (pkg in open) {
                    cur[pkg]?.end = t
                    open -= pkg
                }
                UsageEvents.Event.SCREEN_NON_INTERACTIVE, UsageEvents.Event.DEVICE_SHUTDOWN -> {
                    for (p in open) cur[p]?.end = t
                    open.clear()
                }
            }
        }
        val carried = HashMap<String, Long>()
        for ((pkg, s) in cur) {
            if (pkg in open && to - s.start < MAX_SESSION_MS) {
                carried[pkg] = s.start
            } else {
                if (pkg in open) s.end = to
                flush(s)
            }
        }
        return Scan(done, carried)
    }

    private fun readCarry(): Map<String, Long> = runCatching {
        val o = JSONObject(meta.getString(KEY_CARRY, "{}"))
        o.keys().asSequence().associateWith { o.getLong(it) }
    }.getOrDefault(emptyMap())

    private fun finishBoot(now: Long, open: Map<String, Long>) {
        val o = JSONObject()
        for ((k, v) in open) o.put(k, v)
        meta.edit().putBoolean(KEY_BOOT, true).putLong(KEY_CURSOR, now).putString(KEY_CARRY, o.toString()).commit()
    }

    // --- bootstrap -------------------------------------------------------------------------
    /**
     * First run: events only reach back a few days, so days before the first event come from the
     * system's daily buckets, and months before that from its monthly buckets (those two carry no
     * hour detail). Returns true when it found anything or there is simply nothing to find.
     */
    private fun bootstrapTotals(
        usm: UsageStatsManager, allowed: Set<String>, excluded: Set<String>, now: Long,
        sessions: List<Session>, days: JSONObject,
    ): Boolean {
        val dayMap = days.optJSONObject("days") ?: JSONObject().also { days.put("days", it) }
        val firstEventDay = sessions.minOfOrNull { dayKey(it.start) } ?: dayKey(now)
        var firstDay = firstEventDay
        var added = false
        fun ok(pkg: String) = pkg in allowed && pkg !in excluded

        val daily = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 60 * DAY_MS, now).orEmpty()
        for (u in daily) {
            if (!ok(u.packageName) || u.totalTimeInForeground < 30_000L) continue
            val key = dayKey((u.firstTimeStamp + u.lastTimeStamp) / 2)
            if (key >= firstEventDay) continue
            val day = dayMap.optJSONObject(key.toString()) ?: newDay().also { dayMap.put(key.toString(), it) }
            val p = day.getJSONObject("p")
            p.put(u.packageName, p.optLong(u.packageName) + u.totalTimeInForeground)
            day.put("t", day.optLong("t") + u.totalTimeInForeground)
            if (key < firstDay) firstDay = key
            added = true
        }

        val monthMap = days.optJSONObject("months") ?: JSONObject().also { days.put("months", it) }
        val firstMonth = firstDay / 100
        val monthly = usm.queryUsageStats(UsageStatsManager.INTERVAL_MONTHLY, now - 400 * DAY_MS, now).orEmpty()
        for (u in monthly) {
            if (!ok(u.packageName) || u.totalTimeInForeground < 60_000L) continue
            val key = dayKey((u.firstTimeStamp + u.lastTimeStamp) / 2) / 100
            if (key >= firstMonth) continue
            val m = monthMap.optJSONObject(key.toString()) ?: JSONObject().also { monthMap.put(key.toString(), it) }
            m.put(u.packageName, m.optLong(u.packageName) + u.totalTimeInForeground)
            added = true
        }
        return added || sessions.isNotEmpty()
    }

    // --- aggregation -----------------------------------------------------------------------
    private fun newDay() = JSONObject().put("t", 0L).put("p", JSONObject()).put("h", JSONArray(List(24) { 0L })).put("ls", 0L)

    private fun dayKey(ms: Long): Int {
        val d = Instant.ofEpochMilli(ms).atZone(zone)
        return d.year * 10000 + d.monthValue * 100 + d.dayOfMonth
    }

    private fun monthOfDay(key: String): YearMonth = YearMonth.of(key.substring(0, 4).toInt(), key.substring(4, 6).toInt())

    private class Slots(var weights: MutableMap<String, DoubleArray>)

    /** Adds one session to the day totals (split at hour boundaries) and to the decayed slot weights. */
    private fun addSession(days: JSONObject, slots: Slots, s: Session, now: Long) {
        val dayMap = days.optJSONObject("days") ?: JSONObject().also { days.put("days", it) }
        var t = s.start
        val arr = slots.weights.getOrPut(s.pkg) { DoubleArray(SLOTS) }
        var first = true
        while (t < s.end) {
            val z = Instant.ofEpochMilli(t).atZone(zone)
            val hourEnd = z.truncatedTo(ChronoUnit.HOURS).plusHours(1).toInstant().toEpochMilli()
            val to = minOf(hourEnd, s.end)
            val seg = to - t
            val key = (z.year * 10000 + z.monthValue * 100 + z.dayOfMonth).toString()
            val day = dayMap.optJSONObject(key) ?: newDay().also { dayMap.put(key, it) }
            val p = day.getJSONObject("p")
            p.put(s.pkg, p.optLong(s.pkg) + seg)
            day.put("t", day.optLong("t") + seg)
            val h = day.getJSONArray("h")
            h.put(z.hour, h.optLong(z.hour) + seg)
            if (first) {
                val dur = s.end - s.start
                if (dur > day.optLong("ls")) day.put("ls", dur).put("lp", s.pkg)
            }
            val decay = 0.5.pow((now - t).coerceAtLeast(0L).toDouble() / HALF_LIFE_MS)
            val idx = (z.dayOfWeek.value - 1) * 24 + z.hour
            arr[idx] += seg / HOUR_MS * decay + if (first) 0.5 * decay else 0.0
            first = false
            t = to
        }
    }

    private fun prune(days: JSONObject, now: Long) {
        val cutoff = dayKey(now - KEEP_DAYS * DAY_MS)
        val dayMap = days.optJSONObject("days") ?: return
        val old = dayMap.keys().asSequence().filter { it.toInt() < cutoff }.toList()
        old.forEach(dayMap::remove)
    }

    // --- files -----------------------------------------------------------------------------
    private fun readDays(): JSONObject = runCatching { JSONObject(String(daysFile.readFully())) }
        .getOrElse { JSONObject().put("v", 1).put("days", JSONObject()).put("months", JSONObject()) }

    private fun writeDays(d: JSONObject) = write(daysFile, d.toString())

    /** Weights decayed to [now]. */
    private fun readSlots(now: Long): Slots {
        val out = HashMap<String, DoubleArray>()
        runCatching {
            val o = JSONObject(String(slotsFile.readFully()))
            val factor = 0.5.pow((now - o.getLong("at")).coerceAtLeast(0L).toDouble() / HALF_LIFE_MS)
            val w = o.getJSONObject("w")
            w.keys().forEach { pkg ->
                val a = w.getJSONArray(pkg)
                if (a.length() == SLOTS) out[pkg] = DoubleArray(SLOTS) { a.getDouble(it) * factor }
            }
        }
        return Slots(out)
    }

    private fun writeSlots(slots: Slots, now: Long) {
        val w = JSONObject()
        for ((pkg, arr) in slots.weights) {
            if (arr.sum() < 0.05) continue
            w.put(pkg, JSONArray().also { a -> arr.forEach { a.put(Math.round(it * 1000) / 1000.0) } })
        }
        write(slotsFile, JSONObject().put("v", 1).put("at", now).put("w", w).toString())
    }

    private fun write(file: AtomicFile, text: String) {
        val out = file.startWrite()
        try {
            out.write(text.toByteArray())
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
    }

    companion object {
        /** Never counted, whatever the home-package query says. */
        val AlwaysExcluded = setOf("android", "com.android.systemui", "com.google.android.apps.tv.dreamx")

        private const val KEY_BOOT = "booted"
        private const val KEY_CURSOR = "cursor"
        private const val KEY_CARRY = "carry"

        private const val SLOTS = 7 * 24
        private const val HOUR_MS = 3_600_000.0
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val KEEP_DAYS = 400L
        private const val BOOT_LOOKBACK_MS = 30 * DAY_MS
        private const val HALF_LIFE_MS = 28.0 * DAY_MS
        private const val MERGE_GAP_MS = 5_000L
        private const val MIN_SESSION_MS = 10_000L
        private const val MAX_SESSION_MS = 6L * 60 * 60 * 1000
        private const val ACTIVE_DAY_MS = 5L * 60 * 1000
        private const val MIN_SCORE = 2.5
        private const val MIN_SHARE = 0.35
    }
}
