package dev.mibox.neon.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AppSearchTest {
    private val names = listOf("Prime Video", "Netflix", "YouTube", "YouTube Music", "Tüm Kanallar", "beIN CONNECT", "Spotify")

    private fun find(q: String) = AppSearch.rank(names, q, 6) { it }

    @Test
    fun startOfNameComesFirst() {
        assertEquals(listOf("YouTube", "YouTube Music"), find("you"))
    }

    @Test
    fun wordStartBeatsMiddle() {
        assertEquals(listOf("YouTube Music"), find("mu"))
        assertEquals(listOf("beIN CONNECT"), find("con"))
        assertEquals(listOf("Prime Video"), find("vid"))
    }

    @Test
    fun caseAndTurkishLettersAreIgnored() {
        assertEquals(listOf("Tüm Kanallar"), find("TUM"))
        assertEquals(listOf("beIN CONNECT"), find("bein"))
        assertEquals("isik", AppSearch.fold("IŞIK"))
        assertEquals("isik", AppSearch.fold("ışık"))
    }

    @Test
    fun emptyQueryFindsNothing() {
        assertEquals(emptyList<String>(), find("  "))
    }
}
