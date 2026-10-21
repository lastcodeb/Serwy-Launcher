package dev.mibox.neon.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

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
            it[SHOWCASE] = (if (pkg in cur) cur - pkg else cur + pkg).joinToString(",")
        }
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
    }
}
