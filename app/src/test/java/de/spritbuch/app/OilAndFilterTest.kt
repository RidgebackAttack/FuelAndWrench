package de.spritbuch.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OilAndFilterTest {
    private fun day(y: Int, m: Int, d: Int) = Calendar.getInstance().apply { clear(); set(y, m, d) }.timeInMillis
    private fun top(ts: Long, km: Int, l: Double) = ServiceEntry(timestamp = ts, odometer = km, category = OIL_TOPUP, title = "Öl", oilAmount = l)

    @Test fun consumptionSinceOilChange() {
        val list = listOf(
            ServiceEntry(timestamp = day(2025, 0, 1), odometer = 10_000, category = "Wartung", title = "Ölwechsel"),
            top(day(2024, 6, 1), 8_000, 1.0), // vor dem Ölwechsel – zählt nicht
            top(day(2025, 3, 1), 12_000, 0.5),
            top(day(2025, 6, 1), 14_000, 0.5),
        )
        val s = OilStats.summary(list)
        assertEquals(2, s.topUps.size)
        assertEquals(1.0, s.amount, 1e-9)
        // 1 l auf 4000 km
        assertEquals(0.25, s.per1000!!, 1e-9)
    }

    @Test fun withoutOilChangeFirstTopUpIsStart() {
        val s = OilStats.summary(listOf(top(day(2025, 0, 1), 10_000, 0.7), top(day(2025, 5, 1), 12_000, 0.4)))
        assertEquals(0.2, s.per1000!!, 1e-9)
        assertNull(OilStats.summary(listOf(top(day(2025, 0, 1), 10_000, 0.7))).per1000)
    }

    @Test fun explicitFlagAndTitle() {
        assertTrue(ServiceEntry(title = "Inspektion", oilChange = true).isOilChange)
        assertTrue(ServiceEntry(title = "Öl-Wechsel mit Filter").isOilChange)
        assertTrue(!ServiceEntry(title = "Bremsen").isOilChange)
    }

    @Test fun perYear() {
        val refuels = listOf(
            Refuel(timestamp = day(2024, 0, 5), odometer = 10_000), Refuel(timestamp = day(2024, 11, 20), odometer = 20_000),
            Refuel(timestamp = day(2025, 11, 20), odometer = 28_000),
        )
        val years = OilStats.perYear(listOf(top(day(2024, 5, 1), 15_000, 1.0), top(day(2025, 5, 1), 24_000, 2.0)), refuels)
        val y24 = years.first { it.year == 2024 }
        val y25 = years.first { it.year == 2025 }
        assertEquals(10_000, y24.distance)
        assertEquals(0.1, y24.per1000!!, 1e-9)
        assertEquals(8_000, y25.distance)
        assertEquals(0.25, y25.per1000!!, 1e-9)
    }

    @Test fun filterOnlyAndWithout() {
        val list = listOf("Wartung", "Reifen", HU_CATEGORY, OIL_TOPUP).map { ServiceEntry(id = it, category = it) }
        var f = ServiceFilter.next(emptyMap(), "Reifen")
        assertEquals(listOf("Reifen"), ServiceFilter.apply(list, f).map { it.id })
        f = ServiceFilter.next(f, "Wartung")
        assertEquals(listOf("Wartung", "Reifen"), ServiceFilter.apply(list, f).map { it.id })
        // zweimal tippen = ohne
        f = ServiceFilter.next(ServiceFilter.next(emptyMap(), OIL_TOPUP), OIL_TOPUP)
        assertEquals(listOf("Wartung", "Reifen", HU_CATEGORY), ServiceFilter.apply(list, f).map { it.id })
        // dreimal = aus
        assertTrue(ServiceFilter.next(f, OIL_TOPUP).isEmpty())
    }

    @Test fun specsRoundTripAndOrder() {
        val fields = listOf(SpecField(Specs.CUSTOM, "123", "Schlüssel"), SpecField("oil_spec", "5W-30"), SpecField("vin", "WBA0TEST"), SpecField("hsn", " "))
        val v = Vehicle(specs = Specs.encode(fields))
        assertEquals(listOf("vin", "oil_spec", Specs.CUSTOM), Specs.of(v).map { it.k })
        assertEquals("5W-30", Specs.value(v, "oil_spec"))
        assertTrue("Kette" in Specs.keys("Motorrad").map { Specs.name(it) } || "chain" in Specs.keys("Motorrad"))
    }
}
