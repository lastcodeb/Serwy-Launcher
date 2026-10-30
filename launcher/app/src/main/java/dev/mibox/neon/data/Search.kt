package dev.mibox.neon.data

import android.app.SearchManager
import android.content.Intent
import android.net.Uri
import java.text.Normalizer
import java.util.Locale

/** An app that can be searched directly, by deep link or by the system search. */
enum class SearchTarget(val pkg: String, val label: String) {
    YOUTUBE("com.google.android.youtube.tv", "YouTube"),
    YOUTUBE_MUSIC("com.google.android.youtube.tvmusic", "YouTube Music"),
    SPOTIFY("com.spotify.tv.android", "Spotify"),
    GOOGLE("com.google.android.katniss", "Google TV"),
    ;

    fun intent(query: String): Intent {
        val q = Uri.encode(query.trim())
        val i = when (this) {
            YOUTUBE -> Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$q"))
            YOUTUBE_MUSIC -> Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/search?q=$q"))
            SPOTIFY -> Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com/search/$q"))
            GOOGLE -> Intent(SearchManager.INTENT_ACTION_GLOBAL_SEARCH).putExtra(SearchManager.QUERY, query.trim())
        }
        return i.setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

object AppSearch {
    /** Voice search (the remote's microphone key does the same): Google TV listens at once. */
    fun voiceIntent(): Intent = Intent(Intent.ACTION_ASSIST)
        .setPackage(SearchTarget.GOOGLE.pkg)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Apps whose name matches [query], best first: a name that starts with it, then a word that
     * does, then anywhere. Case and accents are ignored, so "netf" finds Netflix and "tum" Tüm.
     */
    fun apps(apps: List<AppEntry>, query: String, max: Int = 6): List<AppEntry> = rank(apps, query, max) { it.label }

    internal fun <T> rank(items: List<T>, query: String, max: Int, label: (T) -> String): List<T> {
        val q = fold(query)
        if (q.isEmpty()) return emptyList()
        return items.mapNotNull { a ->
            val name = fold(label(a))
            val rank = when {
                name.startsWith(q) -> 0
                name.split(' ', '-', '.').any { it.startsWith(q) } -> 1
                q in name -> 2
                else -> return@mapNotNull null
            }
            rank to a
        }.sortedWith(compareBy({ it.first }, { label(it.second).length })).take(max).map { it.second }
    }

    internal fun fold(s: String): String {
        val lower = s.trim().lowercase(Locale.ROOT).replace('ı', 'i')
        return Normalizer.normalize(lower, Normalizer.Form.NFD).replace(Marks, "")
    }

    private val Marks = Regex("\\p{Mn}+")
}
