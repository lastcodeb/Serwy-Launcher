package dev.mibox.neon.i18n

import java.util.Locale

/**
 * The UI is written in Turkish and translated by lookup: [t] returns the Turkish text on a
 * Turkish box and the English catalog entry ([EN]) on any other language, falling back to the
 * Turkish text for anything not translated yet. Templates use {0}, {1}… placeholders, so word
 * order can change between languages ("RAM %{0}" → "RAM {0}%").
 *
 * The language follows the box: Android restarts the launcher when it changes (see
 * [LocaleReceiver]), so values computed once per process are always in the current language.
 */
object I18n {
    /** True when the box is set to Turkish, the language the UI was written in. */
    val turkish: Boolean get() = Locale.getDefault().language == "tr"

    /** Locale for dates, month/day names and numbers: the box's own. */
    val locale: Locale get() = Locale.getDefault()

    /** Every Turkish source string that has a translation; used by the catalog test. */
    val catalog: Map<String, String> get() = EN
}

/** [tr] in the box's language. */
fun t(tr: String): String = if (I18n.turkish) tr else EN[tr] ?: tr

/** [tr] in the box's language with {0}, {1}… replaced by [args]. */
fun t(tr: String, vararg args: Any?): String {
    var s = t(tr)
    args.forEachIndexed { i, a -> s = s.replace("{$i}", a.toString()) }
    return s
}
