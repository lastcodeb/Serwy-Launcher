package dev.mibox.neon

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import dev.mibox.neon.data.Fixture
import dev.mibox.neon.data.MatchConfig
import dev.mibox.neon.data.MatchEffectKind
import dev.mibox.neon.data.MatchEffects
import dev.mibox.neon.data.MatchBridge
import dev.mibox.neon.data.MatchPhase
import dev.mibox.neon.data.MatchSource
import dev.mibox.neon.data.MatchStore
import dev.mibox.neon.data.MatchTrack
import dev.mibox.neon.data.Prefs
import dev.mibox.neon.data.Wiz
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Match-day alarm chain, in the spirit of [SleepReceiver]: no service, the process only lives while
 * an alarm is being handled. Two kinds of alarm:
 *
 *  - POLL: fetch the scoreboard once, compare with the remembered score, schedule the next poll
 *    (every ~50 s from kickoff - 5 min until full time, at most kickoff + 180 min);
 *  - EFFECT: play one light effect (goal, kickoff, win) at detection time + the broadcast delay.
 *
 * Works with the launcher in the background and the ViewModel gone. While home is visible the
 * ViewModel registers [MatchBridge.sink] and effects go through its light mutex instead.
 */
class MatchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                when (intent.action) {
                    MatchAlarms.ACTION_POLL -> poll(app)
                    MatchAlarms.ACTION_EFFECT -> {
                        val kind = runCatching { MatchEffectKind.valueOf(intent.getStringExtra(MatchAlarms.EXTRA_KIND).orEmpty()) }.getOrNull()
                        if (kind != null) effect(app, kind)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "match alarm failed", e)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun poll(ctx: Context) {
        val store = MatchStore(ctx)
        val track = store.track() ?: return
        val prefs = Prefs(ctx)
        val now = System.currentTimeMillis()
        if (!runBlocking { prefs.matchEnabled.first() } || now > track.kickoffMs + MatchConfig.WINDOW_MS) {
            store.clearTrack()
            return
        }
        val snap = runCatching { MatchSource.live(track.slug, track.eventId, track.kickoffMs) }
            .onFailure { Log.w(TAG, "poll failed", it) }
            .getOrNull()
        if (snap == null) {
            // Offline or not listed yet: keep the chain alive
            MatchAlarms.schedulePoll(ctx, now + POLL_MS)
            return
        }
        Log.i(TAG, "poll ${track.eventId}: ${snap.phase} ${snap.clock} ${snap.gs}-${snap.opp} (last ${track.lastGs}-${track.lastOpp})")
        store.applySnapshot(track.eventId, snap)
        if (snap.phase == MatchPhase.OFF) {
            store.clearTrack()
            return
        }
        val delayMs = runBlocking { prefs.matchDelay.first() } * 1000L
        val goalFx = runBlocking { prefs.matchGoalEffect.first() }
        var t = track
        var kickoffNow = false
        var final = false

        if (snap.phase != MatchPhase.PRE && !t.kickoffFired) {
            // Only a kickoff we actually saw: starting the launcher mid-match must not flash
            kickoffNow = snap.phase == MatchPhase.LIVE && now - t.kickoffMs < LATE_KICKOFF_MS
            t = t.copy(kickoffFired = true)
        }
        val gs = snap.gs
        val opp = snap.opp
        if (gs != null && opp != null) {
            if (t.lastGs < 0) {
                t = t.copy(lastGs = gs, lastOpp = opp) // first look: baseline, nothing to celebrate
            } else {
                if (gs > t.lastGs && goalFx) MatchAlarms.scheduleEffect(ctx, MatchEffectKind.GOAL, now + delayMs)
                // A VAR-disallowed goal lowers the score; remember it so the next real goal counts
                t = t.copy(lastGs = gs, lastOpp = opp)
            }
        }
        if (snap.phase == MatchPhase.POST && !t.finalFired) {
            final = true
            t = t.copy(finalFired = true)
            if (goalFx && gs != null && opp != null && gs > opp) MatchAlarms.scheduleEffect(ctx, MatchEffectKind.WIN, now + delayMs)
        }
        store.saveTrack(t)
        if (snap.phase == MatchPhase.POST) {
            store.clearTrack()
            MatchAlarms.armNext(ctx, afterEvent = track.eventId)
        } else {
            MatchAlarms.schedulePoll(ctx, now + POLL_MS)
        }
        if (kickoffNow) {
            if (delayMs > 0) MatchAlarms.scheduleEffect(ctx, MatchEffectKind.KICKOFF, now + delayMs) else effect(ctx, MatchEffectKind.KICKOFF)
        }
    }

    private fun effect(ctx: Context, kind: MatchEffectKind) {
        Log.i(TAG, "effect $kind")
        val prefs = Prefs(ctx)
        if (!runBlocking { prefs.matchEnabled.first() }) return
        if (kind != MatchEffectKind.KICKOFF && !runBlocking { prefs.matchGoalEffect.first() }) return
        MatchBridge.sink?.let {
            it(kind)
            return
        }
        val ip = runBlocking { prefs.wizIp.first() } ?: return
        synchronized(EffectLock) {
            val before = Wiz.pilot(ip) ?: return
            // A light that is off stays off
            if (!before.on) return
            try {
                runBlocking { MatchEffects.play(kind) { Wiz.set(ip, it) } }
            } finally {
                Wiz.set(ip, before.restoreParams())
            }
        }
    }

    private companion object {
        const val TAG = "NeonMatch"
        const val POLL_MS = 50_000L
        const val LATE_KICKOFF_MS = 20L * 60 * 1000
        val EffectLock = Any()
    }
}

/** Arming and scheduling of the match alarms; used by the ViewModel and the receiver. */
object MatchAlarms {
    const val ACTION_POLL = "dev.mibox.neon.MATCH_POLL"
    const val ACTION_EFFECT = "dev.mibox.neon.MATCH_EFFECT"
    const val EXTRA_KIND = "kind"
    private const val CODE_POLL = 7001
    private const val CODE_EFFECT = 7002
    private const val POLL_GRACE_MS = 30_000L

    private fun pollIntent(ctx: Context) = Intent(ctx, MatchReceiver::class.java).setAction(ACTION_POLL)

    private fun pollPending(ctx: Context, flags: Int) =
        PendingIntent.getBroadcast(ctx, CODE_POLL, pollIntent(ctx), flags or PendingIntent.FLAG_IMMUTABLE)

    /**
     * Starts the chain for the next relevant fixture in [fixtures] when it is within 24 h (or
     * already under way). Safe to call repeatedly: a chain that is already ticking is left alone,
     * and the remembered score survives for the same event.
     */
    fun arm(ctx: Context, fixtures: List<Fixture>, now: Long = System.currentTimeMillis()) {
        val app = ctx.applicationContext
        val next = fixtures
            .filter { it.phase == MatchPhase.PRE || it.phase == MatchPhase.LIVE }
            .filter { now <= it.kickoffMs + MatchConfig.WINDOW_MS && it.kickoffMs - now <= MatchConfig.ARM_AHEAD_MS }
            .minByOrNull { it.kickoffMs } ?: return
        val store = MatchStore(app)
        val old = store.track()
        val moved = old != null && old.eventId == next.eventId && old.kickoffMs != next.kickoffMs
        // A fired alarm leaves its PendingIntent behind, so liveness is judged by the stored due time
        if (old?.eventId == next.eventId && !moved && store.nextPollAt() > now - POLL_GRACE_MS) return
        when {
            old?.eventId != next.eventId -> store.saveTrack(MatchTrack(next.eventId, next.slug, next.kickoffMs))
            moved -> store.saveTrack(old!!.copy(kickoffMs = next.kickoffMs)) // rescheduled by the league
        }
        schedulePoll(app, maxOf(now, next.kickoffMs - MatchConfig.LEAD_MS))
    }

    /** After a match ended: chain straight into the next fixture if it is within 24 h. */
    fun armNext(ctx: Context, afterEvent: String) {
        val fixtures = MatchStore(ctx).fixtures().filter { it.eventId != afterEvent }
        arm(ctx, fixtures)
    }

    fun schedulePoll(ctx: Context, atMs: Long) {
        MatchStore(ctx).setNextPollAt(atMs)
        set(ctx, pollPending(ctx, PendingIntent.FLAG_UPDATE_CURRENT), atMs)
    }

    /** Several effects can be pending at once (two goals inside the delay), so each has its own data URI. */
    fun scheduleEffect(ctx: Context, kind: MatchEffectKind, atMs: Long) {
        val intent = Intent(ctx, MatchReceiver::class.java)
            .setAction(ACTION_EFFECT)
            .setData(Uri.parse("neon://match/${kind.name}/$atMs"))
            .putExtra(EXTRA_KIND, kind.name)
        set(ctx, PendingIntent.getBroadcast(ctx, CODE_EFFECT, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT), atMs)
    }

    /** Stops polling and forgets the match (pending effect alarms check the setting when they fire). */
    fun cancel(ctx: Context) {
        val app = ctx.applicationContext
        app.getSystemService(AlarmManager::class.java).cancel(pollPending(app, PendingIntent.FLAG_UPDATE_CURRENT))
        MatchStore(app).clearTrack()
    }

    private fun set(ctx: Context, pi: PendingIntent, atMs: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
        }
    }
}
