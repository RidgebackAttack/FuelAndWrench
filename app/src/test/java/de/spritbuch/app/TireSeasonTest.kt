package de.spritbuch.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class TireSeasonTest {
    private fun at(y: Int, m: Int, d: Int) = Calendar.getInstance().apply { clear(); set(y, m - 1, d, 12, 0) }.timeInMillis
    private val summer = TireSet(id = "s", season = TireSeason.SUMMER)
    private val winter = TireSet(id = "w", season = TireSeason.WINTER)
    private val allSeason = TireSet(id = "a", season = "Ganzjahresreifen")

    @Test fun easterDates() {
        val e = TireSeason.easter(2027)
        assertEquals(Calendar.MARCH, e.get(Calendar.MONTH)); assertEquals(28, e.get(Calendar.DAY_OF_MONTH))
        val f = TireSeason.easter(2026)
        assertEquals(Calendar.APRIL, f.get(Calendar.MONTH)); assertEquals(5, f.get(Calendar.DAY_OF_MONTH))
    }

    @Test fun periods() {
        assertEquals(TireSeason.WINTER to "W2026", TireSeason.period(at(2026, 10, 1)))
        assertEquals(TireSeason.WINTER to "W2026", TireSeason.period(at(2027, 3, 1)))
        assertEquals(TireSeason.SUMMER to "S2027", TireSeason.period(at(2027, 3, 29)))
        assertEquals(TireSeason.SUMMER to "S2026", TireSeason.period(at(2026, 9, 30)))
    }

    @Test fun winterDueWhenSummerMounted() {
        assertEquals(TireSeason.Due(TireSeason.WINTER, "W2026"), TireSeason.decide(listOf(summer, winter), "s", at(2026, 10, 5)))
        assertNull(TireSeason.decide(listOf(summer, winter), "w", at(2026, 10, 5)))
    }

    @Test fun summerDueAfterEaster() {
        assertEquals(TireSeason.Due(TireSeason.SUMMER, "S2027"), TireSeason.decide(listOf(summer, winter), "w", at(2027, 4, 10)))
        assertNull(TireSeason.decide(listOf(summer, winter), "w", at(2027, 3, 20)))
    }

    @Test fun noReminderForSingleSetOrAllSeason() {
        assertNull(TireSeason.decide(listOf(summer), "s", at(2026, 11, 1)))
        assertNull(TireSeason.decide(listOf(allSeason, winter), "a", at(2026, 11, 1)))
        assertNull(TireSeason.decide(listOf(summer, winter), null, at(2026, 11, 1)))
    }
}
