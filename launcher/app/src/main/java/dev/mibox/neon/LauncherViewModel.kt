package dev.mibox.neon

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.mibox.neon.data.AppArt
import dev.mibox.neon.data.AppEntry
import dev.mibox.neon.data.AppRepository
import dev.mibox.neon.data.City
import dev.mibox.neon.data.DefaultCity
import dev.mibox.neon.data.FavoriteCard
import dev.mibox.neon.data.GazeteEdition
import dev.mibox.neon.data.Headline
import dev.mibox.neon.data.HeadlineApps
import dev.mibox.neon.data.HeadlineEngine
import dev.mibox.neon.data.HeadlineKind
import dev.mibox.neon.data.HeadlineTarget
import dev.mibox.neon.data.HomeTheme
import dev.mibox.neon.data.Fixture
import dev.mibox.neon.data.HeroReason
import dev.mibox.neon.data.HeroState
import dev.mibox.neon.data.Latency
import dev.mibox.neon.data.LinkInfo
import dev.mibox.neon.data.LinkType
import dev.mibox.neon.data.MatchBridge
import dev.mibox.neon.data.MatchConfig
import dev.mibox.neon.data.MatchEffectKind
import dev.mibox.neon.data.MatchEffects
import dev.mibox.neon.data.MatchPhase
import dev.mibox.neon.data.MatchSource
import dev.mibox.neon.data.MatchState
import dev.mibox.neon.data.MatchStore
import dev.mibox.neon.data.NetHealth
import dev.mibox.neon.data.NetNow
import dev.mibox.neon.data.NetWarning
import dev.mibox.neon.data.PinResult
import dev.mibox.neon.data.Prefs
import dev.mibox.neon.data.Ram
import dev.mibox.neon.data.SpeedResult
import dev.mibox.neon.data.SpeedState
import dev.mibox.neon.data.SpeedTest
import dev.mibox.neon.data.SystemStats
import dev.mibox.neon.data.UsageHistory
import dev.mibox.neon.data.WrappedSummary
import dev.mibox.neon.data.pinKey
import dev.mibox.neon.data.stablePinId
import dev.mibox.neon.data.Weather
import dev.mibox.neon.data.WatchItem
import dev.mibox.neon.data.WatchNext
import dev.mibox.neon.data.WeatherNow
import dev.mibox.neon.data.Wiz
import dev.mibox.neon.data.WizPilot
import dev.mibox.neon.ui.Neon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.Instant
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.util.Collections
import dev.mibox.neon.i18n.t

class LauncherViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = AppRepository(app)
    private val prefs = Prefs(app)
    private val stats = SystemStats(app)
    private val usage = UsageHistory(app)
    private val matchStore = MatchStore(app)

    private val installed = MutableStateFlow<List<AppEntry>?>(null)
    private val draftOrder = MutableStateFlow<List<String>?>(null)
    private val lastUsedPkg = MutableStateFlow<String?>(null)
    /** Epoch millis each launchable app was last on screen (usage history); empty without usage access. */
    private val lastSeen = MutableStateFlow<Map<String, Long>>(emptyMap())

    private val _moving = MutableStateFlow<String?>(null)
    val moving: StateFlow<String?> = _moving.asStateFlow()

    // Moving a Favoriler card works like moving an app, on the row's own order
    private val draftFavOrder = MutableStateFlow<List<String>?>(null)
    private val _movingFavorite = MutableStateFlow<String?>(null)
    /** [FavoriteCard.key] of the Favoriler card being moved, if any. */
    val movingFavorite: StateFlow<String?> = _movingFavorite.asStateFlow()

    private val _ram = MutableStateFlow(stats.ram())
    val ram: StateFlow<Ram> = _ram.asStateFlow()

    private val _freed = MutableStateFlow<Long?>(null)
    val freed: StateFlow<Long?> = _freed.asStateFlow()

    private val _speed = MutableStateFlow<SpeedState>(SpeedState.Idle)
    val speed: StateFlow<SpeedState> = _speed.asStateFlow()

    private val _weather = MutableStateFlow<WeatherNow?>(null)
    val weather: StateFlow<WeatherNow?> = _weather.asStateFlow()

    private val _homePressed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val homePressed: SharedFlow<Unit> = _homePressed

    val city: StateFlow<City> = prefs.city.stateIn(viewModelScope, SharingStarted.Eagerly, DefaultCity)

    /** Visible apps in the user's order; while moving a card the unsaved draft order wins. */
    val apps: StateFlow<List<AppEntry>> =
        combine(installed.filterNotNull(), prefs.order, prefs.hidden, draftOrder) { list, order, hidden, draft ->
            arrange(list.filterNot { it.pkg in hidden }, draft ?: order)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val hiddenApps: StateFlow<List<AppEntry>> =
        combine(installed.filterNotNull(), prefs.hidden) { list, hidden -> list.filter { it.pkg in hidden } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** App the usage history expects at this hour; null without a confident prediction. */
    private val predictedPkg = MutableStateFlow<String?>(null)

    /**
     * What the hero shows when nothing is focused: the predicted app (reason [HeroReason.Predicted])
     * when the history is confident, else the app used last, else the first app of the row.
     */
    val hero: StateFlow<HeroState?> =
        combine(installed.filterNotNull(), lastUsedPkg, apps, predictedPkg) { list, last, visible, predicted ->
            val homes = repo.homePackages()
            visible.firstOrNull { it.pkg == predicted && it.pkg !in homes }?.let { HeroState(it, HeroReason.Predicted) }
                ?: list.firstOrNull { it.pkg == last }?.let { HeroState(it, HeroReason.Resume) }
                ?: visible.firstOrNull { it.pkg !in homes }?.let { HeroState(it, HeroReason.Default) }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private var weatherAt = 0L
    private var weatherJob: Job? = null
    private var boostJob: Job? = null

    // --- WiZ backlight -------------------------------------------------------------------
    // Every light command goes through one mutex so effects and restores never interleave.
    private val wizLock = Mutex()
    val wizIp: StateFlow<String?> = prefs.wizIp.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val ambient: StateFlow<Boolean> = prefs.ambient.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    private val _wiz = MutableStateFlow<WizPilot?>(null)
    val wiz: StateFlow<WizPilot?> = _wiz.asStateFlow()

    private val _sleepAt = MutableStateFlow<Long?>(null)
    val sleepAt: StateFlow<Long?> = _sleepAt.asStateFlow()

    /** How the light looked when the home screen appeared — normally the box's TV-sync state. */
    private var entrySnapshot: WizPilot? = null
    private var ambientActive = false
    private var restoreOnLeave = false
    private var flashUntil = 0L
    private val accent = MutableStateFlow<Color?>(null)
    /** Light profile of the app being launched; applied instead of the restore when home is left. */
    private var pendingProfile: String? = null
    /** What the light showed before the quick (long-press) toggle, to switch back to. */
    private var beforeQuick: WizPilot? = null

    val favorites: StateFlow<List<String>> = prefs.favorites.stateIn(viewModelScope, SharingStarted.Eagerly, Wiz.DefaultFavorites)
    val profiles: StateFlow<Map<String, String>> = prefs.profiles.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    // Declared before init: reloadApps() there can reach these from a background thread
    private val recentPkgs = MutableStateFlow<List<String>>(emptyList())

    private val _watchNext = MutableStateFlow<List<WatchItem>>(emptyList())
    val watchNext: StateFlow<List<WatchItem>> = _watchNext.asStateFlow()

    private val _forYou = MutableStateFlow<List<WatchItem>>(emptyList())
    /** "Senin için": the apps' own recommendation channels. */
    val forYou: StateFlow<List<WatchItem>> = _forYou.asStateFlow()

    /** Pinned showcase apps, or the most recently used ones when nothing is pinned. */
    val showcase: StateFlow<List<AppEntry>> =
        combine(installed.filterNotNull(), prefs.showcase, recentPkgs) { list, pinned, recent ->
            val byPkg = list.associateBy { it.pkg }
            (pinned.ifEmpty { recent }).mapNotNull(byPkg::get)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val pinnedShowcase: StateFlow<List<String>> = prefs.showcase.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** The user's favorites row (the same list the idle showcase cycles through). */
    val pinnedApps: StateFlow<List<AppEntry>> =
        combine(installed.filterNotNull(), prefs.showcase) { list, pinned ->
            val byPkg = list.associateBy { it.pkg }
            pinned.mapNotNull(byPkg::get)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // --- Favoriler row: pinned apps and pinned content, in the order they were added --------------
    /**
     * The mixed Favoriler row. Pins whose app was uninstalled are left out (not deleted, so an app
     * update that briefly removes the package doesn't lose them).
     */
    val favoriteCards: StateFlow<List<FavoriteCard>> =
        combine(installed.filterNotNull(), prefs.showcase, prefs.pinnedContent, prefs.favoriteOrder, draftFavOrder) { list, apps, content, saved, draft ->
            val order = draft ?: saved
            val byPkg = list.associateBy { it.pkg }
            val cards = apps.mapNotNull { byPkg[it]?.let { a -> FavoriteCard.App(a) } } +
                content.mapNotNull { c -> byPkg[c.item.pkg]?.let { a -> FavoriteCard.Content(a, c.item, c.key) } }
            val rank = HashMap<String, Int>(order.size).apply { order.forEachIndexed { i, t -> put(t, i) } }
            // Stable sort: apps pinned before content pins existed have no token and stay first
            cards.sortedBy { rank[it.key] ?: -1 }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Pin keys ([pinKeyOf]) of the pinned content, for showing "already pinned" in a menu. */
    val pinnedContentKeys: StateFlow<Set<String>> =
        prefs.pinnedContent.map { l -> l.mapTo(HashSet<String>()) { it.key } as Set<String> }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private val _pinResult = MutableSharedFlow<PinResult>(extraBufferCapacity = 1)
    /** Outcome of the last [togglePinned]: Added, Removed, or Full (12 content pins). */
    val pinResult: SharedFlow<PinResult> = _pinResult

    fun pinKeyOf(item: WatchItem): String = item.pinKey()

    fun isPinned(item: WatchItem): Boolean = item.pinKey() in pinnedContentKeys.value

    /**
     * Long-press action on a Watch Next / "Senin için" card: pins the item to Favoriler, or unpins
     * it. The poster is copied into filesDir so it survives the source URI expiring.
     */
    fun togglePinned(item: WatchItem) {
        viewModelScope.launch(Dispatchers.IO) {
            val key = item.pinKey()
            if (key in pinnedContentKeys.value) {
                prefs.togglePinnedContent(key, item, PIN_MAX, emptySet())
                posterFile(key).delete()
                _pinResult.tryEmit(PinResult.Removed)
                return@launch
            }
            val installedPkgs = installed.value.orEmpty().mapTo(HashSet()) { it.pkg }
            val local = savePoster(key, item.imageUri)
            val stored = item.copy(id = stablePinId(key), imageUri = local ?: item.imageUri, progress = null)
            val result = prefs.togglePinnedContent(key, stored, PIN_MAX, installedPkgs)
            if (result != PinResult.Added) posterFile(key).delete()
            _pinResult.tryEmit(result)
        }
    }

    private fun posterFile(key: String) = File(File(getApplication<Application>().filesDir, "pins"), "$key.img")

    /** Copies the poster bytes (max 2 MB) into the app's data; returns a file:// URI or null. */
    private fun savePoster(key: String, uri: String?): String? {
        if (uri == null) return null
        val bytes = WatchNext.readBytes(getApplication(), uri)?.takeIf { it.size in 1..POSTER_MAX_BYTES } ?: return null
        return runCatching {
            val f = posterFile(key)
            f.parentFile?.mkdirs()
            f.writeBytes(bytes)
            f.toURI().toString()
        }.getOrNull()
    }

    /** Opens a Favoriler card: an app normally, or straight into the pinned episode / video. */
    fun launchPinned(card: FavoriteCard) {
        when (card) {
            is FavoriteCard.App -> launch(card.app)
            is FavoriteCard.Content -> launchWatch(card.item)
        }
    }

    /**
     * Art for a pinned content card: its saved poster, or the app's own banner when the poster can
     * no longer be loaded.
     */
    suspend fun favoriteArt(card: FavoriteCard.Content): AppArt? = repo.watchArt(card.item) ?: repo.art(card.app)

    private val _watchLoaded = MutableStateFlow(false)
    /** True once the first Watch Next query finished, so initial focus can pick the right row. */
    val watchLoaded: StateFlow<Boolean> = _watchLoaded.asStateFlow()

    private val _lastInput = MutableStateFlow(System.currentTimeMillis())
    val lastInput: StateFlow<Long> = _lastInput.asStateFlow()

    // --- Ticker: everything that updates while the home screen is visible ----------------------
    /** Wall clock, set on resume and every ticker round; derived flows (verdict age, match card…) re-evaluate on it. */
    private val _clock = MutableStateFlow(System.currentTimeMillis())
    private var tickJob: Job? = null
    private var probeJob: Job? = null
    private var usageJob: Job? = null
    private var lastPredictAt = 0L
    private var lastLatencyAt = 0L
    private var lastMatchLiveAt = 0L
    private var lastSunCheckAt = 0L
    private var lastProbeTry = 0L

    /** Runs only between onResumed and onPaused, so nothing here touches the network in the background. */
    private fun startTicker() {
        tickJob?.cancel()
        tickJob = viewModelScope.launch {
            while (true) {
                delay(TICK_MS)
                val now = System.currentTimeMillis()
                _clock.value = now
                refreshNet(latency = now - lastLatencyAt >= LATENCY_EVERY_MS)
                maybeAutoProbe(now)
                if (now - lastMatchLiveAt >= MATCH_LIVE_EVERY_MS) refreshMatchLive()
                if (now - lastPredictAt >= PREDICT_EVERY_MS) refreshPrediction()
                if (now - lastSunCheckAt >= SUN_CHECK_EVERY_MS) refreshSun()
            }
        }
    }

    // --- Network health ---------------------------------------------------------------------
    private val _link = MutableStateFlow(LinkInfo(LinkType.NONE, null, null))
    private val _latency = MutableStateFlow<Int?>(null)

    /** The last 30 speed tests (manual and automatic), oldest first. */
    val netHistory: StateFlow<List<SpeedResult>> = prefs.speedHistory.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Connection type, Wi-Fi signal, link speed, latency, newest result and the streaming verdict. */
    val netNow: StateFlow<NetNow> =
        combine(_link, _latency, netHistory, _clock) { link, latency, history, now ->
            NetNow(
                link = link.type,
                rssiDbm = link.rssiDbm,
                signalBars = link.signalBars,
                linkMbps = link.linkMbps,
                latencyMs = latency,
                last = history.lastOrNull(),
                verdict = NetHealth.verdict(history, now),
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, NetNow(LinkType.NONE, null, null, null, null, null, NetHealth.verdict(emptyList(), 0L)))

    private val _netWarning = MutableSharedFlow<NetWarning>(extraBufferCapacity = 1)
    /**
     * One-shot, emitted when a streaming app is launched while the newest result (at most 3 h old)
     * is under 25 Mbps. The launch itself is never delayed; show a Toast.
     */
    val netWarning: SharedFlow<NetWarning> = _netWarning

    private fun warnIfSlow(pkg: String, label: String) {
        if (pkg !in NetHealth.StreamingPackages) return
        val r = NetHealth.fresh(netHistory.value, System.currentTimeMillis()) ?: return
        if (r.mbps < NetHealth.UHD_MBPS) _netWarning.tryEmit(NetWarning(label, r.mbps))
    }

    /** Re-reads the link (cheap); with [latency] also pings Cloudflare. The UI may call it when a network card opens. */
    fun refreshNet(latency: Boolean = false) {
        if (latency) lastLatencyAt = System.currentTimeMillis()
        viewModelScope.launch(Dispatchers.IO) {
            val link = NetHealth.link(getApplication())
            _link.value = link
            if (latency) _latency.value = if (link.type == LinkType.NONE) null else Latency.probe()
        }
    }

    /** Quiet 3 s test when home is visible, the user has been idle for a minute and the newest result is over an hour old. */
    private fun maybeAutoProbe(now: Long) {
        if (probeJob?.isActive == true || _speed.value is SpeedState.Running) return
        if (now - _lastInput.value < PROBE_IDLE_MS || now - lastProbeTry < PROBE_RETRY_MS) return
        if (_link.value.type == LinkType.NONE) return
        val last = netHistory.value.lastOrNull()
        if (last != null && now - last.at < NetHealth.AUTO_STALE_MS) return
        lastProbeTry = now
        // No WiZ gauge and no _speed updates: the user never sees this one
        probeJob = viewModelScope.launch {
            try {
                val mbps = SpeedTest.run(PROBE_MS)
                if (mbps > 0.0) prefs.addSpeedResult(SpeedResult(System.currentTimeMillis(), mbps, auto = true))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.i(TAG, "background speed probe failed: $e")
            }
        }
    }

    // --- Usage history: time-aware hero and Neon Wrapped --------------------------------------
    private val _usageAccess = MutableStateFlow(true)
    /** False when the usage-stats appop is missing (the hero, prediction and Wrapped then stay empty). */
    val usageAccess: StateFlow<Boolean> = _usageAccess.asStateFlow()

    private val _wrappedMonths = MutableStateFlow<List<YearMonth>>(emptyList())
    /** Months with data, newest first (the running month included). */
    val wrappedAvailableMonths: StateFlow<List<YearMonth>> = _wrappedMonths.asStateFlow()

    /**
     * True during the first 3 days of a month while last month has data and its summary has not
     * been opened. Opening it through [wrapped] clears the badge.
     */
    val wrappedBadge: StateFlow<Boolean> =
        combine(_wrappedMonths, prefs.wrappedSeen, _clock) { months, seen, now ->
            val today = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            if (today.dayOfMonth > 3) {
                false
            } else {
                val prev = YearMonth.from(today).minusMonths(1)
                prev in months && seen != prev.toString()
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Ingests new usage events, then refreshes the prediction and the Wrapped month list. */
    private fun refreshUsage() {
        if (usageJob?.isActive == true) return
        usageJob = viewModelScope.launch(Dispatchers.IO) {
            val list = installed.filterNotNull().first()
            _usageAccess.value = usage.hasAccess()
            val excluded = repo.homePackages() + getApplication<Application>().packageName + UsageHistory.AlwaysExcluded
            runCatching { usage.ingest(list.mapTo(HashSet()) { it.pkg }, excluded) }
                .onSuccess { Log.i(TAG, "usage ingest: new sessions=$it") }
                .onFailure { Log.w(TAG, "usage ingest failed", it) }
            refreshPrediction()
            _wrappedMonths.value = runCatching { usage.months() }.getOrDefault(emptyList())
            Log.i(TAG, "usage: access=${_usageAccess.value}, months=${_wrappedMonths.value}")
        }
    }

    private fun refreshPrediction() {
        lastPredictAt = System.currentTimeMillis()
        val allowed = installed.value?.mapTo(HashSet()) { it.pkg } ?: return
        viewModelScope.launch(Dispatchers.IO) {
            predictedPkg.value = runCatching { usage.predict(System.currentTimeMillis(), allowed) }.getOrNull()
            Log.i(TAG, "predicted hero: ${predictedPkg.value}")
        }
    }

    /**
     * The summary of [month], or null without data (no usage access, or a month the system and the
     * history never saw). Opening last month's summary clears [wrappedBadge].
     */
    suspend fun wrapped(month: YearMonth): WrappedSummary? {
        val s = usage.wrapped(month) { pkg -> installed.value?.firstOrNull { it.pkg == pkg }?.label ?: pkg.substringAfterLast('.') }
        if (s != null && month == YearMonth.now().minusMonths(1)) prefs.setWrappedSeen(month.toString())
        return s
    }

    // --- Galatasaray match day ----------------------------------------------------------------
    private val _fixtures = MutableStateFlow<List<Fixture>>(emptyList())
    private val _matchSim = MutableStateFlow<MatchState?>(null)

    val matchEnabled: StateFlow<Boolean> = prefs.matchEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    /** Seconds the light effects wait after the API reports an event; one of [MatchConfig.DelayOptions]. */
    val matchDelay: StateFlow<Int> = prefs.matchDelay.stateIn(viewModelScope, SharingStarted.Eagerly, MatchConfig.DEFAULT_DELAY_S)
    /** Light effects for goals and a win (the kickoff flash only needs [matchEnabled]). */
    val matchGoalEffect: StateFlow<Boolean> = prefs.matchGoalEffect.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    /** Package of the app matches are watched in, or null. */
    val matchApp: StateFlow<String?> = prefs.matchApp.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * What the match card shows: the live match, else a result from the last ~12 h, else the next
     * fixture; null when disabled or nothing is known. A [simulateMatch] state wins over all of it.
     */
    val match: StateFlow<MatchState?> =
        combine(_fixtures, _matchSim, matchEnabled, _clock) { fixtures, sim, enabled, now ->
            sim ?: if (enabled) MatchSource.stateOf(fixtures, now) else null
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun setMatchEnabled(on: Boolean) {
        viewModelScope.launch {
            prefs.setMatchEnabled(on)
            if (on) refreshMatch(force = true) else withContext(Dispatchers.IO) { MatchAlarms.cancel(getApplication()) }
        }
    }

    fun setMatchDelay(seconds: Int) {
        viewModelScope.launch { prefs.setMatchDelay(seconds) }
    }

    fun setMatchGoalEffect(on: Boolean) {
        viewModelScope.launch { prefs.setMatchGoalEffect(on) }
    }

    fun setMatchApp(pkg: String?) {
        viewModelScope.launch { prefs.setMatchApp(pkg) }
    }

    /** Opens the match app (with the usual light profile and 4K check). False when none is set or installed. */
    fun launchMatchApp(): Boolean {
        val app = matchApp.value?.let(::appFor) ?: return false
        launch(app)
        return true
    }

    /** Plays the goal strobe now (through the light mutex; a light that is off stays off). */
    fun testGoalEffect() = playMatchEffect(MatchEffectKind.GOAL)

    /** Plays any of the match effects now. */
    fun testMatchEffect(kind: MatchEffectKind) = playMatchEffect(kind)

    /** Shows [state] on the match card regardless of the real fixtures; null returns to the real data. */
    fun simulateMatch(state: MatchState?) {
        _matchSim.value = state
    }

    private fun playMatchEffect(kind: MatchEffectKind) = wizEffect { ip -> MatchEffects.play(kind) { Wiz.set(ip, it) } }

    private fun refreshMatch(force: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            if (!prefs.matchEnabled.first()) {
                MatchAlarms.cancel(app)
                return@launch
            }
            val cached = matchStore.fixtures()
            if (cached.isNotEmpty()) _fixtures.value = cached
            val now = System.currentTimeMillis()
            // Caches written before crests existed have no opponent id: refetch them once
            val stale = cached.any { it.opponentId.isBlank() }
            if (force || stale || cached.isEmpty() || now - matchStore.fetchedAt() >= MatchConfig.FIXTURES_TTL_MS) {
                runCatching { MatchSource.fixtures() }
                    .onSuccess {
                        matchStore.saveFixtures(it, now)
                        _fixtures.value = matchStore.fixtures()
                    }
                    .onFailure { Log.w(TAG, "fixtures unavailable", it) }
            }
            Log.i(TAG, "match: ${_fixtures.value.size} fixtures, card=${match.value}")
            // Idempotent: a chain that is already ticking is left alone
            MatchAlarms.arm(app, _fixtures.value)
        }
    }

    /** While a match is on, folds the newest score into the card (the alarm chain does its own polling). */
    private fun refreshMatchLive() {
        val now = System.currentTimeMillis()
        lastMatchLiveAt = now
        viewModelScope.launch(Dispatchers.IO) {
            val stored = matchStore.fixtures()
            if (stored.isNotEmpty()) _fixtures.value = stored
            val fx = _fixtures.value.filter { it.phase == MatchPhase.PRE || it.phase == MatchPhase.LIVE }
                .minByOrNull { it.kickoffMs } ?: return@launch
            if (now !in (fx.kickoffMs - 10 * 60_000L)..(fx.kickoffMs + MatchConfig.WINDOW_MS)) return@launch
            val snap = runCatching { MatchSource.live(fx.slug, fx.eventId, fx.kickoffMs) }.getOrNull() ?: return@launch
            matchStore.applySnapshot(fx.eventId, snap)
            _fixtures.value = matchStore.fixtures()
        }
    }

    // --- Home theme, day/night ------------------------------------------------------------------
    // The initial value is read once, synchronously (a few ms), so a Gazete/Teletext user never sees Neon flash first.
    val theme: StateFlow<HomeTheme> =
        prefs.theme.stateIn(viewModelScope, SharingStarted.Eagerly, runBlocking { runCatching { prefs.theme.first() }.getOrDefault(HomeTheme.NEON) })

    fun setTheme(theme: HomeTheme) {
        viewModelScope.launch { prefs.setTheme(theme) }
    }

    val gazeteEdition: StateFlow<GazeteEdition> =
        prefs.gazeteEdition.stateIn(
            viewModelScope, SharingStarted.Eagerly,
            runBlocking { runCatching { prefs.gazeteEdition.first() }.getOrDefault(GazeteEdition.AUTO) },
        )

    fun setGazeteEdition(edition: GazeteEdition) {
        viewModelScope.launch { prefs.setGazeteEdition(edition) }
    }

    /** Sunrise and sunset of [city] for [date], in the city's own time zone. */
    private class SunTimes(val city: City, val date: java.time.LocalDate, val rise: LocalTime, val set: LocalTime)

    private val sun = MutableStateFlow<SunTimes?>(null)
    private var sunJob: Job? = null
    private var sunTriedAt = 0L

    /**
     * True between sunset and the next sunrise of the selected city. Sunrise/sunset come from
     * Open-Meteo once per day per city (fetched only while home is visible); without them, or
     * while offline, night is 19:00-07:00 local. Re-evaluated on every ticker round and on resume.
     */
    val isNight: StateFlow<Boolean> =
        combine(sun, city, _clock) { times, c, now ->
            val t = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalTime()
            val usable = times?.takeIf { it.city == c }
            val rise = usable?.rise ?: LocalTime.of(7, 0)
            val set = usable?.set ?: LocalTime.of(19, 0)
            t >= set || t < rise
        }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** The Gazete theme's edition: fixed by the user, or [isNight] on AUTO. */
    val gazeteNight: StateFlow<Boolean> =
        combine(gazeteEdition, isNight) { edition, night ->
            when (edition) {
                GazeteEdition.NIGHT -> true
                GazeteEdition.DAY -> false
                GazeteEdition.AUTO -> night
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Fetches today's sun times when missing for this city (failed attempts are retried after 5 min). */
    private fun refreshSun() {
        val now = System.currentTimeMillis()
        lastSunCheckAt = now
        val c = city.value
        val today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
        val have = sun.value
        if (have != null && have.city == c && have.date == today) return
        if (sunJob?.isActive == true || now - sunTriedAt < SUN_RETRY_MS) return
        sunTriedAt = now
        sunJob = viewModelScope.launch(Dispatchers.IO) {
            runCatching { Weather.sun(c) }
                .onSuccess { pair ->
                    if (pair != null) {
                        sun.value = SunTimes(c, today, pair.first, pair.second)
                        sunTriedAt = 0L
                    }
                }
                .onFailure { Log.i(TAG, "sun times unavailable: $it") }
        }
    }

    // --- Headline ("manset"): the lead story of the Gazete and Teletext themes ---------------------
    private val headlineApps = object : HeadlineApps {
        private val categories = HashMap<String, String?>()

        override fun label(pkg: String): String? = appFor(pkg)?.label

        override fun lastSeen(pkg: String): Long? = this@LauncherViewModel.lastSeen.value[pkg]

        override fun category(pkg: String): String? = synchronized(categories) {
            if (categories.containsKey(pkg)) return@synchronized categories[pkg]
            run {
                val cat = runCatching {
                    getApplication<Application>().packageManager.getApplicationInfo(pkg, 0).category
                }.getOrNull()
                when (cat) {
                    android.content.pm.ApplicationInfo.CATEGORY_GAME -> t("Oyun")
                    android.content.pm.ApplicationInfo.CATEGORY_AUDIO -> t("Müzik")
                    android.content.pm.ApplicationInfo.CATEGORY_VIDEO -> t("Video")
                    android.content.pm.ApplicationInfo.CATEGORY_IMAGE -> t("Fotoğraf")
                    android.content.pm.ApplicationInfo.CATEGORY_SOCIAL -> t("Sosyal")
                    android.content.pm.ApplicationInfo.CATEGORY_NEWS -> t("Haber")
                    android.content.pm.ApplicationInfo.CATEGORY_MAPS -> t("Harita")
                    android.content.pm.ApplicationInfo.CATEGORY_PRODUCTIVITY -> t("Araçlar")
                    android.content.pm.ApplicationInfo.CATEGORY_ACCESSIBILITY -> t("Erişilebilirlik")
                    else -> null
                }
            }.also { categories[pkg] = it }
        }
    }

    /** Turkish category of an app ("Video", "Oyun"…), or null when it declares none. Cached per package. */
    fun categoryOf(pkg: String): String? = headlineApps.category(pkg)

    /** Newest finished fixture from the cached fixtures (up to 14 days old), independent of the match card. */
    val lastResult: StateFlow<MatchState.Finished?> =
        combine(_fixtures, _clock) { fixtures, now ->
            fixtures.filter { it.phase == MatchPhase.POST && it.gs != null && it.opp != null && now - it.kickoffMs in 0..MatchConfig.LAST_RESULT_KEEP_MS }
                .maxByOrNull { it.kickoffMs }?.let(MatchSource::finished)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The next fixture, independent of the card (which may still show an old result). A simulated match wins. */
    private fun nextFixture(sim: MatchState?, fixtures: List<Fixture>, enabled: Boolean, now: Long): MatchState.Upcoming? {
        if (sim != null) return sim as? MatchState.Upcoming
        if (!enabled) return null
        return fixtures.filter { it.phase == MatchPhase.PRE && it.kickoffMs > now - MatchConfig.LEAD_MS }
            .minByOrNull { it.kickoffMs }?.let(MatchSource::upcoming)
    }

    /**
     * The lead story when nothing is focused: live match, fresh result, match today, Watch Next,
     * predicted app, match in the next 3 days, last used app, greeting. Never null.
     */
    val headline: StateFlow<Headline> =
        combine(match, _fixtures, _watchNext, hero, _clock) { m, fixtures, watch, h, now ->
            HeadlineEngine.compute(
                now = now,
                match = m,
                upcoming = nextFixture(_matchSim.value, fixtures, matchEnabled.value, now),
                watch = watch,
                hero = h,
                lights = m != null && matchEnabled.value && wizIp.value != null,
                apps = headlineApps,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, HeadlineEngine.greeting(System.currentTimeMillis()))

    /** A focused Watch Next / "Senin için" item in headline format. */
    fun headlineFor(item: WatchItem): Headline {
        // Items from the Watch Next row are "continue watching"; recommendations and pins are just the app's name
        val resume = _watchNext.value.any { it.id == item.id }
        return HeadlineEngine.continueWatching(item, headlineApps, System.currentTimeMillis(), resume)
    }

    /** A focused app in headline format. */
    fun headlineFor(app: AppEntry): Headline {
        val kind = when (app.pkg) {
            predictedPkg.value -> HeadlineKind.PREDICTED
            lastUsedPkg.value -> HeadlineKind.LAST_USED
            else -> HeadlineKind.APP
        }
        return HeadlineEngine.forApp(app, kind, headlineApps, System.currentTimeMillis())
    }

    /**
     * Performs a headline's action through the usual launch paths (light profile, flash, slow-net
     * warning). Returns false when nothing was launched: [HeadlineTarget.None], an app that is
     * gone, or [HeadlineTarget.OpenMatch] with no match app chosen (the UI may open the match panel then).
     */
    fun act(target: HeadlineTarget): Boolean = when (target) {
        is HeadlineTarget.OpenApp -> appFor(target.pkg)?.let { launch(it); true } ?: false
        is HeadlineTarget.OpenWatch -> { launchWatch(target.item); true }
        HeadlineTarget.OpenMatch -> launchMatchApp()
        HeadlineTarget.None -> false
    }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            intent.data?.schemeSpecificPart?.let(repo::invalidate)
            reloadApps()
        }
    }

    init {
        reloadApps()
        viewModelScope.launch { city.drop(1).collect {
                refreshWeather(force = true)
                sunTriedAt = 0L
                if (resumed) refreshSun()
            } }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(app, packageReceiver, filter, ContextCompat.RECEIVER_EXPORTED)

        viewModelScope.launch(Dispatchers.IO) {
            if (prefs.wizIp.first() == null) Wiz.discover()?.let { prefs.setWizIp(it) }
        }
        // The saved IP loads (or discovery finishes) after the first resume: catch up then
        viewModelScope.launch {
            wizIp.filterNotNull().collect { if (resumed) wizEnterHome() }
        }
        // Ambient: follow the focused app's color, debounced so fast D-pad scrolling sends one packet
        viewModelScope.launch(Dispatchers.IO) {
            accent.filterNotNull().distinctUntilChanged().collectLatest { c ->
                delay(250)
                val ip = wizIp.value ?: return@collectLatest
                wizLock.withLock {
                    if (ambientActive) Wiz.set(ip, Wiz.color(c, entrySnapshot?.dimming ?: 100))
                }
            }
        }
    }

    override fun onCleared() {
        MatchBridge.sink = null
        getApplication<Application>().unregisterReceiver(packageReceiver)
    }

    suspend fun art(app: AppEntry): AppArt? = repo.art(app)
    suspend fun heroIcon(app: AppEntry): ImageBitmap? = repo.heroIcon(app)

    fun launch(app: AppEntry) {
        prepareLaunch(app.pkg, app)
        warnIfSlow(app.pkg, app.label)
        repo.launch(app)
    }

    /** Light side of opening an app: its profile, or a flash of its color while ambient is on. */
    private fun prepareLaunch(pkg: String, app: AppEntry?) {
        val profile = profiles.value[pkg]
        if (profile != null && wizIp.value != null) {
            pendingProfile = profile
        } else if (ambientActive && app != null) {
            // Flash the app's color; leaving home waits for it, then restores the previous light
            flashUntil = System.currentTimeMillis() + FLASH_MS
            viewModelScope.launch(Dispatchers.IO) {
                val c = repo.art(app)?.vibrant ?: return@launch
                val ip = wizIp.value ?: return@launch
                wizLock.withLock { Wiz.set(ip, Wiz.color(c, 100)) }
            }
        }
    }
    fun openAppInfo(pkg: String) = repo.openAppInfo(pkg)
    fun openSystemSettings() = repo.openSystemSettings()

    fun onHomePressed() {
        if (_moving.value != null || _movingFavorite.value != null) finishMove()
        _homePressed.tryEmit(Unit)
    }

    fun onBack() {
        if (_moving.value != null || _movingFavorite.value != null) finishMove()
    }

    private var resumed = false

    fun onResumed() {
        resumed = true
        _clock.value = System.currentTimeMillis()
        refreshRam()
        refreshLastUsed()
        refreshWatchNext()
        refreshWeather()
        wizEnterHome()
        refreshUsage()
        refreshNet(latency = true)
        refreshMatch()
        refreshSun()
        MatchBridge.sink = ::playMatchEffect
        startTicker()
    }

    fun onPaused() {
        resumed = false
        tickJob?.cancel()
        probeJob?.cancel()
        MatchBridge.sink = null
        wizLeaveHome()
    }

    fun onAccent(c: Color) {
        accent.value = c
    }

    private fun wizEnterHome() {
        val ip = wizIp.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            // Coming back from the screensaver: let it put the light back before we look at it
            delay(700)
            wizLock.withLock {
                val p = Wiz.pilot(ip) ?: return@withLock
                _wiz.value = p
                // Never switch on a light the user turned off (or the sleep timer did)
                if (!ambient.value || !p.on || ambientActive) return@withLock
                entrySnapshot = p
                ambientActive = true
                restoreOnLeave = true
                accent.value?.let { Wiz.set(ip, Wiz.color(it, p.dimming)) }
            }
        }
    }

    private fun wizLeaveHome() {
        val ip = wizIp.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            wizLock.withLock {
                val wasActive = ambientActive
                val restore = restoreOnLeave
                ambientActive = false
                restoreOnLeave = false
                val profile = pendingProfile
                pendingProfile = null
                if (profile != null) {
                    val cur = _wiz.value
                    Wiz.presetParams(profile, cur?.dimming ?: 100, cur?.speed?.takeIf { it > 0 } ?: 100)
                        ?.let { Wiz.set(ip, it) }
                    return@withLock
                }
                val wait = flashUntil - System.currentTimeMillis()
                if (wait > 0) delay(wait)
                if (wasActive && restore) entrySnapshot?.let { Wiz.set(ip, it.restoreParams()) }
            }
        }
    }

    /** A manual choice in the light panel wins: stop ambient and keep it after leaving home. */
    private fun wizManual(params: () -> JSONObject?) {
        val ip = wizIp.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            wizLock.withLock {
                val p = params() ?: return@withLock
                ambientActive = false
                restoreOnLeave = false
                Wiz.set(ip, p)
                _wiz.value = Wiz.pilot(ip)
            }
        }
    }

    /** Runs a light effect, then puts back the ambient color or whatever was showing before. */
    private fun wizEffect(block: suspend (ip: String) -> Unit) {
        val ip = wizIp.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            wizLock.withLock {
                val before = if (ambientActive) null else Wiz.pilot(ip)
                if (!ambientActive && before?.on != true) return@withLock
                block(ip)
                val back = if (ambientActive) {
                    accent.value?.let { Wiz.color(it, entrySnapshot?.dimming ?: 100) }
                } else {
                    before?.restoreParams()
                }
                back?.let { Wiz.set(ip, it) }
            }
        }
    }

    fun wizRefresh() {
        val ip = wizIp.value
        viewModelScope.launch(Dispatchers.IO) {
            if (ip == null) {
                Wiz.discover()?.let { prefs.setWizIp(it) }
                return@launch
            }
            _wiz.value = Wiz.pilot(ip)
        }
    }

    fun wizToggle() = wizManual {
        JSONObject().put("state", !(_wiz.value?.on ?: false))
    }

    fun wizDim(delta: Int) = wizManual {
        val cur = _wiz.value ?: return@wizManual null
        JSONObject().put("dimming", (cur.dimming + delta).coerceIn(Wiz.MIN_DIM, 100))
    }

    fun wizColor(c: Color) = wizManual { Wiz.color(c, _wiz.value?.dimming ?: 100) }

    fun wizWhite(kelvin: Int) = wizManual { Wiz.white(kelvin, _wiz.value?.dimming ?: 100) }

    fun wizScene(id: Int) = wizManual {
        Wiz.scene(id, _wiz.value?.speed?.takeIf { it > 0 } ?: 100, _wiz.value?.dimming ?: 100)
    }

    fun wizSpeed(delta: Int) = wizManual {
        val cur = _wiz.value ?: return@wizManual null
        if (cur.sceneId <= 0) return@wizManual null
        Wiz.scene(cur.sceneId, (cur.speed.takeIf { it > 0 } ?: 100) + delta, cur.dimming)
    }

    fun wizWeather() = wizManual {
        val w = weather.value ?: return@wizManual null
        Wiz.weatherLight(w, _wiz.value?.dimming ?: 100)
    }

    /** Puts back the light as it was when the home screen appeared. */
    fun wizBackToPrevious() = wizManual { entrySnapshot?.restoreParams() }

    fun wizPreset(id: String) = wizManual {
        val cur = _wiz.value
        Wiz.presetParams(id, cur?.dimming ?: 100, cur?.speed?.takeIf { it > 0 } ?: 100)
    }

    /** Long-press on the bulb chip: first favorite (Parti) on, or back to what was before. */
    fun wizQuick() {
        val id = favorites.value.firstOrNull() ?: Wiz.PARTY
        val cur = _wiz.value
        if (Wiz.isActive(id, cur)) {
            wizManual { beforeQuick?.restoreParams() ?: JSONObject().put("state", false) }
            beforeQuick = null
        } else {
            beforeQuick = if (ambientActive) entrySnapshot else cur
            wizPreset(id)
        }
    }

    fun toggleFavorite(id: String) {
        viewModelScope.launch { prefs.toggleFavorite(id) }
    }

    fun setLightProfile(pkg: String, preset: String?) {
        viewModelScope.launch { prefs.setProfile(pkg, preset) }
    }

    fun wizToggleAmbient() {
        val on = !ambient.value
        viewModelScope.launch {
            prefs.setAmbient(on)
            if (on) {
                wizEnterHome()
            } else {
                val ip = wizIp.value ?: return@launch
                withContext(Dispatchers.IO) {
                    wizLock.withLock {
                        if (ambientActive) entrySnapshot?.let { Wiz.set(ip, it.restoreParams()) }
                        ambientActive = false
                        restoreOnLeave = false
                    }
                }
            }
        }
    }

    /** Cycles off → 15 → 30 → 60 → 90 min → off. */
    fun wizCycleSleep() {
        val ip = wizIp.value ?: return
        val ctx = getApplication<Application>()
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            ctx, 0,
            Intent(ctx, SleepReceiver::class.java).putExtra(SleepReceiver.EXTRA_IP, ip),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val now = System.currentTimeMillis()
        val left = _sleepAt.value?.let { ((it - now) / 60_000).toInt() } ?: 0
        val next = SLEEP_STEPS.firstOrNull { it > left + 1 }
        if (next == null) {
            am.cancel(pi)
            _sleepAt.value = null
            return
        }
        val at = now + next * 60_000L
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
        _sleepAt.value = at
    }

    fun refreshRam() {
        _ram.value = stats.ram()
    }

    fun boost() {
        if (boostJob?.isActive == true) return
        boostJob = viewModelScope.launch {
            val before = stats.ram().avail
            withContext(Dispatchers.IO) {
                stats.killBackground()
                // Some apps restart their background process straight away: a second pass gets them
                delay(1200)
                stats.killBackground()
            }
            delay(1000) // give lmkd/AM a moment to actually reclaim the memory
            val after = stats.ram()
            _ram.value = after
            _freed.value = (after.avail - before).coerceAtLeast(0)
            // Purple "breath" on the backlight
            wizEffect { ip ->
                repeat(2) {
                    Wiz.set(ip, Wiz.color(Neon.Purple, 100))
                    delay(450)
                    Wiz.set(ip, Wiz.color(Neon.Purple, 25))
                    delay(450)
                }
            }
            delay(3500)
            _freed.value = null
        }
    }

    fun runSpeedTest() {
        if (_speed.value is SpeedState.Running) return
        viewModelScope.launch {
            _speed.value = SpeedState.Running(0.0)
            // Backlight works as a gauge: red → green as the measured speed climbs
            wizEffect { ip ->
                while (true) {
                    val s = _speed.value
                    val mbps = when (s) {
                        is SpeedState.Running -> s.mbps
                        is SpeedState.Done -> s.mbps
                        else -> break
                    }
                    Wiz.set(ip, Wiz.color(Wiz.speedColor(mbps), 100))
                    if (s is SpeedState.Done) {
                        delay(2000)
                        break
                    }
                    delay(300)
                }
            }
            _speed.value = runCatching { SpeedTest.run { _speed.value = SpeedState.Running(it) } }
                .fold({ SpeedState.Done(it) }, { SpeedState.Failed })
            (_speed.value as? SpeedState.Done)?.takeIf { it.mbps > 0.0 }?.let {
                prefs.addSpeedResult(SpeedResult(System.currentTimeMillis(), it.mbps, auto = false))
            }
        }
    }

    fun refreshWeather(force: Boolean = false) {
        if (!force && (weatherJob?.isActive == true || System.currentTimeMillis() - weatherAt < WEATHER_TTL_MS)) return
        weatherJob?.cancel()
        weatherJob = viewModelScope.launch(Dispatchers.IO) {
            runCatching { Weather.now(city.value) }.onSuccess {
                _weather.value = it
                weatherAt = System.currentTimeMillis()
            }
        }
    }

    /** Returns false when Open-Meteo doesn't know the city. */
    suspend fun setCity(name: String): Boolean {
        val found = withContext(Dispatchers.IO) { runCatching { Weather.geocode(name) }.getOrNull() } ?: return false
        _weather.value = null
        prefs.setCity(found)
        return true
    }

    fun setHidden(pkg: String, hidden: Boolean) {
        viewModelScope.launch { prefs.setHidden(pkg, hidden) }
    }

    fun startMove(pkg: String) {
        draftOrder.value = apps.value.map { it.pkg }
        _moving.value = pkg
    }

    /** Starts moving the Favoriler card with [key] ([FavoriteCard.key]); [move] and [finishMove] then act on it. */
    fun startFavoriteMove(key: String) {
        draftFavOrder.value = favoriteCards.value.map { it.key }
        _movingFavorite.value = key
    }

    fun move(delta: Int) {
        _movingFavorite.value?.let { key ->
            val list = draftFavOrder.value?.toMutableList() ?: return
            val i = list.indexOf(key)
            val j = i + delta
            if (i < 0 || j !in list.indices) return
            Collections.swap(list, i, j)
            draftFavOrder.value = list
            return
        }
        val pkg = _moving.value ?: return
        val list = draftOrder.value?.toMutableList() ?: return
        val i = list.indexOf(pkg)
        val j = i + delta
        if (i < 0 || j !in list.indices) return
        Collections.swap(list, i, j)
        draftOrder.value = list
    }

    fun finishMove() {
        if (_movingFavorite.value != null) {
            val order = draftFavOrder.value ?: return
            _movingFavorite.value = null
            viewModelScope.launch {
                prefs.setFavoriteOrder(order)
                prefs.favoriteOrder.first { it == order }
                draftFavOrder.value = null
            }
            return
        }
        val order = draftOrder.value ?: return
        _moving.value = null
        viewModelScope.launch {
            prefs.setOrder(order)
            // Drop the draft only once the saved order is flowing, so the row never jumps back
            prefs.order.first { it == order }
            draftOrder.value = null
        }
    }

    private fun reloadApps() {
        viewModelScope.launch(Dispatchers.IO) {
            installed.value = repo.load()
            refreshLastUsed()
        }
    }

    private fun refreshLastUsed() {
        val list = installed.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            // Other launchers (Projectivy…) are "used" every time home shows; never a hero
            val homes = repo.homePackages()
            val recent = stats.recent(list.mapNotNullTo(HashSet()) { it.pkg.takeIf { p -> p !in homes } }, SHOWCASE_SIZE)
            recentPkgs.value = recent
            lastUsedPkg.value = recent.firstOrNull()
            lastSeen.value = stats.lastVisible(list.mapNotNullTo(HashSet()) { it.pkg.takeIf { p -> p !in homes } })
        }
    }

    // --- Watch Next, showcase, idle ---------------------------------------------------------
    fun onUserInput() {
        _lastInput.value = System.currentTimeMillis()
    }

    fun toggleShowcase(pkg: String) {
        viewModelScope.launch { prefs.toggleShowcase(pkg) }
    }

    private fun refreshWatchNext() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { WatchNext.query(getApplication()) }
                .onSuccess {
                    _watchNext.value = it
                    Log.i(TAG, "Watch Next: ${it.size} items")
                }
                .onFailure { Log.w(TAG, "Watch Next unavailable", it) }
            runCatching { WatchNext.previews(getApplication(), exclude = _watchNext.value.mapTo(HashSet()) { it.title }) }
                .onSuccess { _forYou.value = it }
                .onFailure { Log.w(TAG, "Preview programs unavailable", it) }
            _watchLoaded.value = true
        }
    }

    suspend fun watchArt(item: WatchItem): AppArt? = repo.watchArt(item)
    suspend fun largeArt(item: WatchItem): ImageBitmap? = repo.largeArt(item)
    suspend fun largeArt(app: AppEntry): ImageBitmap? = repo.largeArt(app)

    /**
     * Called when home is no longer visible: give memory back to the app being watched. The rows
     * are emptied too, otherwise their composed cards would keep every thumbnail reachable; they
     * are reloaded in onResumed anyway.
     */
    fun trimMemory() {
        _watchNext.value = emptyList()
        _forYou.value = emptyList()
        repo.trimCaches()
    }

    fun appFor(pkg: String): AppEntry? = installed.value?.firstOrNull { it.pkg == pkg }

    fun launchWatch(item: WatchItem) {
        val app = appFor(item.pkg)
        prepareLaunch(item.pkg, app)
        warnIfSlow(item.pkg, app?.label ?: item.pkg)
        if (!repo.launchWatch(item)) app?.let(repo::launch)
    }

    private fun arrange(list: List<AppEntry>, order: List<String>): List<AppEntry> {
        if (order.isEmpty()) return list
        val rank = HashMap<String, Int>(order.size).apply { order.forEachIndexed { i, p -> put(p, i) } }
        // Stable sort: apps the user never placed keep their alphabetical spot at the end
        return list.sortedBy { rank[it.pkg] ?: Int.MAX_VALUE }
    }

    private companion object {
        const val PIN_MAX = 12
        const val POSTER_MAX_BYTES = 2 * 1024 * 1024
        const val TICK_MS = 15_000L
        const val PREDICT_EVERY_MS = 15L * 60 * 1000
        const val LATENCY_EVERY_MS = 5L * 60 * 1000
        const val MATCH_LIVE_EVERY_MS = 60_000L
        const val SUN_CHECK_EVERY_MS = 5L * 60 * 1000
        const val SUN_RETRY_MS = 5L * 60 * 1000
        const val PROBE_IDLE_MS = 60_000L
        const val PROBE_RETRY_MS = 5L * 60 * 1000
        const val PROBE_MS = 3_000L
        const val WEATHER_TTL_MS = 30L * 60 * 1000
        const val FLASH_MS = 900L
        const val SHOWCASE_SIZE = 5
        const val TAG = "Neon"
        val SLEEP_STEPS = listOf(15, 30, 60, 90)
    }
}
