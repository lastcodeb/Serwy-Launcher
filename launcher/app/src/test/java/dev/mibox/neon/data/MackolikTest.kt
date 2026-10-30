package dev.mibox.neon.data

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Rows as vd.mackolik.com/livedata sent them on 30/10/2026, during Konyaspor – Galatasaray. */
class MackolikTest {
    private val rows = JSONArray(
        """[
        [4542739, 447, "Konyaspor", 1, "Galatasaray", 3, "57", "0-2", 0, 0, 0, 0, 0, 4, 3258190, {}, "20:00"],
        [4539798, 32216, "Konyaspor U19", 18093, "Galatasaray U19", 4, "MS", "0-0", 0, 0, 0, 0, 0, 0, 0, {}, "15:00"],
        [4498864, 9, "Esenler Erokspor", 10, "Keçiörengücü", 3, "60", "0-1", 0, 0, 0, 0, 2, 1, 0, {}, "20:00"]
        ]""",
    )

    @Test
    fun awayScoreIsOursWhenWePlayAway() {
        val s = Mackolik.find(rows, "20:00")!!
        assertEquals(MatchPhase.LIVE, s.phase)
        assertEquals(4, s.gs)
        assertEquals(0, s.opp)
        assertEquals("57'", s.clock)
    }

    @Test
    fun otherKickoffsAndTeamsAreIgnored() {
        assertNull(Mackolik.find(rows, "21:45"))
    }

    @Test
    fun statusCodesMapToPhases() {
        fun phase(status: Int, minute: String) =
            Mackolik.find(JSONArray("""[[1, 1, "Galatasaray", 2, "X", $status, "$minute", "", 0, 0, 0, 0, 2, 1, 0, {}, "19:00"]]"""), "19:00")?.phase
        assertEquals(MatchPhase.PRE, phase(0, ""))
        assertEquals(MatchPhase.LIVE, phase(2, "IY"))
        assertEquals(MatchPhase.POST, phase(4, "MS"))
        assertEquals(MatchPhase.OFF, phase(9, "Ert."))
        assertNull(phase(14, "1P"))
    }
}
