package dev.mibox.neon.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TeamCodesTest {
    @Test fun turkishAbbreviations() {
        assertEquals("FB", TeamCodes.short("Fenerbahçe", "FEN"))
        assertEquals("BJK", TeamCodes.short("Beşiktaş", "BES"))
        assertEquals("İBFK", TeamCodes.short("Başakşehir", "IBFK"))
    }

    @Test fun unknownClubsKeepEspnCode() {
        assertEquals("LIL", TeamCodes.short("Lille", "LIL"))
    }

    @Test fun logoUrlIsResized() {
        assertEquals(
            "https://a.espncdn.com/combiner/i?img=/i/teamlogos/soccer/500/436.png&w=128&h=128",
            TeamCodes.logoUrl("436"),
        )
    }
}
