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
import dev.mibox.neon.data.HeroState
import dev.mibox.neon.data.Prefs
import dev.mibox.neon.data.Ram
import dev.mibox.neon.data.SpeedState
import dev.mibox.neon.data.SpeedTest
import dev.mibox.neon.data.SystemStats
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Collections

class LauncherViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = AppRepository(app)
    private val prefs = Prefs(app)
    private val stats = SystemStats(app)

    private val installed = MutableStateFlow<List<AppEntry>?>(null)
    private val draftOrder = MutableStateFlow<List<String>?>(null)
    private val lastUsedPkg = MutableStateFlow<String?>(null)

    private val _moving = MutableStateFlow<String?>(null)
    val moving: StateFlow<String?> = _moving.asStateFlow()

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

    val hero: StateFlow<HeroState?> =
        combine(installed.filterNotNull(), lastUsedPkg, apps) { list, last, visible ->
            list.firstOrNull { it.pkg == last }?.let { HeroState(it, resume = true) }
                ?: visible.firstOrNull { it.pkg !in repo.homePackages() }?.let { HeroState(it, resume = false) }
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

    private val _watchLoaded = MutableStateFlow(false)
    /** True once the first Watch Next query finished, so initial focus can pick the right row. */
    val watchLoaded: StateFlow<Boolean> = _watchLoaded.asStateFlow()

    private val _lastInput = MutableStateFlow(System.currentTimeMillis())
    val lastInput: StateFlow<Long> = _lastInput.asStateFlow()

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            intent.data?.schemeSpecificPart?.let(repo::invalidate)
            reloadApps()
        }
    }

    init {
        reloadApps()
        viewModelScope.launch { city.drop(1).collect { refreshWeather(force = true) } }
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
        getApplication<Application>().unregisterReceiver(packageReceiver)
    }

    suspend fun art(app: AppEntry): AppArt? = repo.art(app)
    suspend fun heroIcon(app: AppEntry): ImageBitmap? = repo.heroIcon(app)

    fun launch(app: AppEntry) {
        prepareLaunch(app.pkg, app)
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
        if (_moving.value != null) finishMove()
        _homePressed.tryEmit(Unit)
    }

    fun onBack() {
        if (_moving.value != null) finishMove()
    }

    private var resumed = false

    fun onResumed() {
        resumed = true
        refreshRam()
        refreshLastUsed()
        refreshWatchNext()
        refreshWeather()
        wizEnterHome()
    }

    fun onPaused() {
        resumed = false
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

    fun move(delta: Int) {
        val pkg = _moving.value ?: return
        val list = draftOrder.value?.toMutableList() ?: return
        val i = list.indexOf(pkg)
        val j = i + delta
        if (i < 0 || j !in list.indices) return
        Collections.swap(list, i, j)
        draftOrder.value = list
    }

    fun finishMove() {
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
        if (!repo.launchWatch(item)) app?.let(repo::launch)
    }

    private fun arrange(list: List<AppEntry>, order: List<String>): List<AppEntry> {
        if (order.isEmpty()) return list
        val rank = HashMap<String, Int>(order.size).apply { order.forEachIndexed { i, p -> put(p, i) } }
        // Stable sort: apps the user never placed keep their alphabetical spot at the end
        return list.sortedBy { rank[it.pkg] ?: Int.MAX_VALUE }
    }

    private companion object {
        const val WEATHER_TTL_MS = 30L * 60 * 1000
        const val FLASH_MS = 900L
        const val SHOWCASE_SIZE = 5
        const val TAG = "Neon"
        val SLEEP_STEPS = listOf(15, 30, 60, 90)
    }
}
