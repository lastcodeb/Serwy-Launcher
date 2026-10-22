package dev.mibox.neon.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.store by preferencesDataStore(name = "neon")

class Prefs(private val ctx: Context) {
    val order: Flow<List<String>> = ctx.store.data
        .map { p -> p[ORDER]?.split(',')?.filter(String::isNotEmpty).orEmpty() }
        .distinctUntilChanged()

    val hidden: Flow<Set<String>> = ctx.store.data
        .map { it[HIDDEN].orEmpty() }
        .distinctUntilChanged()

    val city: Flow<City> = ctx.store.data
        .map { p ->
            val name = p[CITY_NAME]
            val lat = p[CITY_LAT]
            val lon = p[CITY_LON]
            if (name != null && lat != null && lon != null) City(name, lat, lon) else DefaultCity
        }
        .distinctUntilChanged()

    val wizIp: Flow<String?> = ctx.store.data.map { it[WIZ_IP] }.distinctUntilChanged()

    val ambient: Flow<Boolean> = ctx.store.data.map { it[AMBIENT] ?: true }.distinctUntilChanged()

    val favorites: Flow<List<String>> = ctx.store.data
        .map { p -> p[FAVORITES]?.split(',')?.filter(String::isNotEmpty) ?: Wiz.DefaultFavorites }
        .distinctUntilChanged()

    /** Per-app light preset, applied when that app is launched. */
    val profiles: Flow<Map<String, String>> = ctx.store.data
        .map { p ->
            p[PROFILES].orEmpty().split(';').mapNotNull { e ->
                val i = e.indexOf('=')
                if (i > 0) e.substring(0, i) to e.substring(i + 1) else null
            }.toMap()
        }
        .distinctUntilChanged()

    /** Apps pinned to the idle showcase, in the order they were added. */
    val showcase: Flow<List<String>> = ctx.store.data
        .map { p -> p[SHOWCASE]?.split(',')?.filter(String::isNotEmpty).orEmpty() }
        .distinctUntilChanged()

    suspend fun toggleShowcase(pkg: String) {
        ctx.store.edit {
            val cur = it[SHOWCASE]?.split(',')?.filter(String::isNotEmpty).orEmpty()
            val adding = pkg !in cur
            it[SHOWCASE] = (if (adding) cur + pkg else cur - pkg).joinToString(",")
            it[FAV_ORDER] = withToken(it[FAV_ORDER], "a:$pkg", adding)
        }
    }

    /**
     * Add order of the mixed Favoriler row: tokens "a:<pkg>" (app) and "c:<key>" (content). Apps
     * pinned before this existed have no token and simply come first.
     */
    val favoriteOrder: Flow<List<String>> = ctx.store.data
        .map { p -> p[FAV_ORDER]?.split(',')?.filter(String::isNotEmpty).orEmpty() }
        .distinctUntilChanged()

    /** Pinned Watch Next / "Senin için" items, in the order they were added. */
    val pinnedContent: Flow<List<PinnedContent>> = ctx.store.data
        .map { p -> decodePins(p[PINNED_CONTENT]) }
        .distinctUntilChanged()

    /**
     * Adds [item] (or removes it when [key] is already pinned). Pins whose app is not in
     * [installedPkgs] are dropped first so a removed app never holds a slot. Returns the outcome.
     */
    suspend fun togglePinnedContent(key: String, item: WatchItem, max: Int, installedPkgs: Set<String>): PinResult {
        var result = PinResult.Added
        ctx.store.edit {
            val cur = decodePins(it[PINNED_CONTENT])
            if (cur.any { c -> c.key == key }) {
                it[PINNED_CONTENT] = encodePins(cur.filterNot { c -> c.key == key })
                it[FAV_ORDER] = withToken(it[FAV_ORDER], "c:$key", false)
                result = PinResult.Removed
                return@edit
            }
            val live = cur.filter { c -> c.item.pkg in installedPkgs }
            if (live.size >= max) {
                result = PinResult.Full
                return@edit
            }
            it[PINNED_CONTENT] = encodePins(live + PinnedContent(key, item))
            it[FAV_ORDER] = withToken(it[FAV_ORDER], "c:$key", true)
        }
        return result
    }

    private fun withToken(order: String?, token: String, add: Boolean): String {
        val cur = order?.split(',')?.filter(String::isNotEmpty).orEmpty() - token
        return (if (add) cur + token else cur).joinToString(",")
    }

    private fun encodePins(list: List<PinnedContent>): String = JSONArray().apply {
        for (c in list) {
            put(
                JSONObject()
                    .put("k", c.key).put("p", c.item.pkg).put("t", c.item.title)
                    .put("s", c.item.subtitle ?: JSONObject.NULL)
                    .put("i", c.item.imageUri ?: JSONObject.NULL)
                    .put("u", c.item.intentUri ?: JSONObject.NULL),
            )
        }
    }.toString()

    private fun decodePins(s: String?): List<PinnedContent> {
        if (s.isNullOrEmpty()) return emptyList()
        return runCatching {
            val a = JSONArray(s)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                val key = o.getString("k")
                PinnedContent(
                    key,
                    WatchItem(
                        id = stablePinId(key),
                        pkg = o.getString("p"),
                        title = o.getString("t"),
                        subtitle = if (o.isNull("s")) null else o.getString("s"),
                        imageUri = if (o.isNull("i")) null else o.getString("i"),
                        intentUri = if (o.isNull("u")) null else o.getString("u"),
                        progress = null,
                        lastEngaged = 0L,
                    ),
                )
            }
        }.getOrDefault(emptyList())
    }

    // --- Network health ---------------------------------------------------------------------
    /** Last [NetHealth.HISTORY_MAX] speed results, oldest first. */
    val speedHistory: Flow<List<SpeedResult>> = ctx.store.data
        .map { NetHealth.decode(it[SPEED_HISTORY]) }
        .distinctUntilChanged()

    suspend fun addSpeedResult(r: SpeedResult) {
        ctx.store.edit {
            it[SPEED_HISTORY] = NetHealth.encode((NetHealth.decode(it[SPEED_HISTORY]) + r).takeLast(NetHealth.HISTORY_MAX))
        }
    }

    // --- Neon Wrapped -----------------------------------------------------------------------
    /** The last month whose summary the user opened, as "yyyy-MM". */
    val wrappedSeen: Flow<String?> = ctx.store.data.map { it[WRAPPED_SEEN] }.distinctUntilChanged()

    suspend fun setWrappedSeen(month: String) {
        ctx.store.edit { it[WRAPPED_SEEN] = month }
    }

    // --- Galatasaray match day --------------------------------------------------------------
    val matchEnabled: Flow<Boolean> = ctx.store.data.map { it[MATCH_ENABLED] ?: true }.distinctUntilChanged()

    /** Seconds the light effects wait after the API reports an event: the TV stream lags the API. */
    val matchDelay: Flow<Int> = ctx.store.data.map { it[MATCH_DELAY] ?: MatchConfig.DEFAULT_DELAY_S }.distinctUntilChanged()

    val matchGoalEffect: Flow<Boolean> = ctx.store.data.map { it[MATCH_GOAL_FX] ?: true }.distinctUntilChanged()

    /** Package of the app the user watches matches in (optional). */
    val matchApp: Flow<String?> = ctx.store.data.map { it[MATCH_APP] }.distinctUntilChanged()

    suspend fun setMatchEnabled(on: Boolean) {
        ctx.store.edit { it[MATCH_ENABLED] = on }
    }

    suspend fun setMatchDelay(seconds: Int) {
        ctx.store.edit { it[MATCH_DELAY] = seconds }
    }

    suspend fun setMatchGoalEffect(on: Boolean) {
        ctx.store.edit { it[MATCH_GOAL_FX] = on }
    }

    suspend fun setMatchApp(pkg: String?) {
        ctx.store.edit { if (pkg == null) it.remove(MATCH_APP) else it[MATCH_APP] = pkg }
    }

    suspend fun toggleFavorite(id: String) {
        ctx.store.edit {
            val cur = it[FAVORITES]?.split(',')?.filter(String::isNotEmpty) ?: Wiz.DefaultFavorites
            it[FAVORITES] = (if (id in cur) cur - id else cur + id).joinToString(",")
        }
    }

    suspend fun setProfile(pkg: String, preset: String?) {
        ctx.store.edit {
            val cur = it[PROFILES].orEmpty().split(';').filter { e -> e.isNotEmpty() && !e.startsWith("$pkg=") }
            it[PROFILES] = (if (preset == null) cur else cur + "$pkg=$preset").joinToString(";")
        }
    }

    suspend fun setWizIp(ip: String) {
        ctx.store.edit { it[WIZ_IP] = ip }
    }

    suspend fun setAmbient(on: Boolean) {
        ctx.store.edit { it[AMBIENT] = on }
    }

    suspend fun setOrder(order: List<String>) {
        ctx.store.edit { it[ORDER] = order.joinToString(",") }
    }

    suspend fun setHidden(pkg: String, hide: Boolean) {
        ctx.store.edit {
            val current = it[HIDDEN].orEmpty()
            it[HIDDEN] = if (hide) current + pkg else current - pkg
        }
    }

    suspend fun setCity(city: City) {
        ctx.store.edit {
            it[CITY_NAME] = city.name
            it[CITY_LAT] = city.lat
            it[CITY_LON] = city.lon
        }
    }

    private companion object {
        val ORDER = stringPreferencesKey("order")
        val HIDDEN = stringSetPreferencesKey("hidden")
        val CITY_NAME = stringPreferencesKey("city_name")
        val CITY_LAT = doublePreferencesKey("city_lat")
        val CITY_LON = doublePreferencesKey("city_lon")
        val WIZ_IP = stringPreferencesKey("wiz_ip")
        val AMBIENT = booleanPreferencesKey("wiz_ambient")
        val FAVORITES = stringPreferencesKey("wiz_favorites")
        val PROFILES = stringPreferencesKey("wiz_profiles")
        val SHOWCASE = stringPreferencesKey("showcase")
        val FAV_ORDER = stringPreferencesKey("fav_order")
        val PINNED_CONTENT = stringPreferencesKey("pinned_content")
        val SPEED_HISTORY = stringPreferencesKey("speed_history")
        val WRAPPED_SEEN = stringPreferencesKey("wrapped_seen")
        val MATCH_ENABLED = booleanPreferencesKey("match_enabled")
        val MATCH_DELAY = intPreferencesKey("match_delay_s")
        val MATCH_GOAL_FX = booleanPreferencesKey("match_goal_fx")
        val MATCH_APP = stringPreferencesKey("match_app")
    }
}
