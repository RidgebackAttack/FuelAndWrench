package de.spritbuch.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MaintenanceTest {
    private fun day(y: Int, m: Int, d: Int) = Calendar.getInstance().apply { clear(); set(y, m, d, 12, 0) }.timeInMillis

    @Test fun stampMarksChangedPartsAndKeepsNewerFromStaleForm() {
        val old = Vehicle(id = "v", specs = "neu", specsUpdatedAt = 500, manualOdometer = 1000, odoUpdatedAt = 100, updatedAt = 500)
        // Formular mit altem Stand (Steckbrief noch leer), aber neuem Kilometerstand
        val form = Vehicle(id = "v", specs = "", specsUpdatedAt = 0, manualOdometer = 1200, odoUpdatedAt = 100)
        val saved = stampVehicleParts(form, old, 900)
        assertEquals("neu", saved.specs)
        assertEquals(500, saved.specsUpdatedAt)
        assertEquals(1200, saved.manualOdometer)
        assertEquals(900, saved.odoUpdatedAt)
    }

    @Test fun photosAndOdometerMergeSeparately() {
        val a = Vehicle(id = "v", name = "A", photoVersion = 7, photosUpdatedAt = 300, updatedAt = 300)
        val b = Vehicle(id = "v", name = "B", manualOdometer = 5000, odoUpdatedAt = 400, updatedAt = 400)
        val m = mergeVehicleParts(b, a)
        assertEquals("B", m.name)
        assertEquals(7L, m.photoVersion)
        assertEquals(5000, m.manualOdometer)
    }

    @Test fun purgeDropsOnlyOldTombstones() {
        val cutoff = 1_000L
        val d = SyncData(
            refuels = listOf(
                Refuel(id = "old", deleted = true, updatedAt = 10),
                Refuel(id = "recent", deleted = true, updatedAt = 2_000),
                Refuel(id = "live", updatedAt = 10),
            ),
        )
        assertEquals(listOf("recent", "live"), Purge.remote(d, cutoff).refuels.map { it.id })
    }

    @Test fun dataCheckFindsTypos() {
        val v = Vehicle(id = "v", tankCapacity = 50.0)
        val list = listOf(
            Refuel(id = "1", vehicleId = "v", timestamp = day(2026, 0, 1), odometer = 10_000, amount = 40.0, fuelType = "Super E10"),
            Refuel(id = "2", vehicleId = "v", timestamp = day(2026, 0, 10), odometer = 1_050, amount = 40.0, fuelType = "Super E10"),
            Refuel(id = "3", vehicleId = "v", timestamp = day(2026, 0, 20), odometer = 10_600, amount = 75.0, fuelType = "Super E10"),
        )
        val kinds = DataCheck.check(v, list, now = day(2026, 1, 1)).map { it.refuel.id to it.kind }
        assertTrue("2" to DataCheck.Kind.ODO_BACKWARDS in kinds)
        assertTrue("3" to DataCheck.Kind.OVER_TANK in kinds)
        assertFalse(kinds.any { it.first == "3" && it.second == DataCheck.Kind.ODO_BACKWARDS })
        // „passt so“ hängt an der Betankung und wird damit synchronisiert
        val ok = list.map { if (it.id == "3") it.copy(checkOk = "OVER_TANK") else it }
        assertFalse(DataCheck.check(v, ok, now = day(2026, 1, 1)).filter { !DataCheck.isOk(it.refuel, it.kind) }.any { it.refuel.id == "3" })
    }

    @Test fun seasonalRegistration() {
        val v = Vehicle(registration = "03-10")
        assertTrue(Registration.isActive(v, day(2026, Calendar.MARCH, 1)))
        assertTrue(Registration.isActive(v, day(2026, Calendar.OCTOBER, 31)))
        assertFalse(Registration.isActive(v, day(2026, Calendar.NOVEMBER, 1)))
        assertFalse(Registration.isActive(v, day(2026, Calendar.FEBRUARY, 28)))
        val winter = Vehicle(registration = "10-03")
        assertTrue(Registration.isActive(winter, day(2026, Calendar.JANUARY, 5)))
        assertFalse(Registration.isActive(winter, day(2026, Calendar.JULY, 5)))
        assertTrue(Registration.isActive(Vehicle(), day(2026, Calendar.JULY, 5)))
        assertNull(Registration.parse("13-02"))
    }

    @Test fun tireAgeFromDot() {
        val now = day(2027, Calendar.JUNE, 1)
        assertEquals(6.0, TireAge.years("2321", now)!!, 0.1)
        assertNull(TireAge.years("5421", now)) // Woche 54 gibt es nicht
        assertNull(TireAge.years("0130", now)) // Zukunft
        assertNull(TireAge.years("21", now))
        val set = TireSet(dotFront = "0525", dotRear = "1018")
        assertEquals(2, TireAge.level(TireAge.max(set, now)!!))
    }

    @Test fun cropRegionMapsToOriginalRotation() {
        val c = android.graphics.RectF(0.1f, 0.2f, 0.3f, 0.4f)
        fun RectF4(r: android.graphics.RectF) = listOf(r.left, r.top, r.right, r.bottom).map { Math.round(it * 100) }
        assertEquals(listOf(10, 20, 30, 40), RectF4(Ocr.regionInOriginal(c, 0)))
        assertEquals(listOf(20, 70, 40, 90), RectF4(Ocr.regionInOriginal(c, 90)))
        assertEquals(listOf(70, 60, 90, 80), RectF4(Ocr.regionInOriginal(c, 180)))
        assertEquals(listOf(60, 10, 80, 30), RectF4(Ocr.regionInOriginal(c, 270)))
    }
}
