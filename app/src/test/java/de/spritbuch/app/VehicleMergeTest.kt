package de.spritbuch.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** Steckbrief und Dokumente werden beim Sync getrennt vom übrigen Fahrzeug zusammengeführt. */
class VehicleMergeTest {
    private val specs = """[{"k":"oil_spec","v":"5W-30"}]"""

    @Test fun specsFromOlderVehicleSurvive() = runBlocking {
        // Tablet: Steckbrief eingetragen (älter); Telefon: Fahrzeug danach anderweitig gespeichert (neuer, ohne Steckbrief)
        val tablet = Vehicle(id = "v", name = "Auto", specs = specs, specsUpdatedAt = 100, updatedAt = 100)
        val phone = Vehicle(id = "v", name = "Auto neu", docsUpdatedAt = 150, documents = "[]", updatedAt = 200)

        val written = mutableListOf<Vehicle>()
        val onPhone = mergeVehicles(listOf(phone), listOf(tablet)) { written += it }.single()
        assertEquals("Auto neu", onPhone.name)
        assertEquals(specs, onPhone.specs)
        assertEquals(1, written.size)

        val onTablet = mergeVehicles(listOf(tablet), listOf(onPhone)) { }.single()
        assertEquals(onPhone, onTablet)
        // danach stabil: nichts mehr zu schreiben
        val again = mutableListOf<Vehicle>()
        mergeVehicles(listOf(onTablet), listOf(onPhone)) { again += it }
        assertEquals(0, again.size)
    }

    @Test fun newerSpecsWin() = runBlocking {
        val a = Vehicle(id = "v", specs = specs, specsUpdatedAt = 300, updatedAt = 300)
        val b = Vehicle(id = "v", specs = "", specsUpdatedAt = 400, updatedAt = 100)
        assertEquals("", mergeVehicles(listOf(a), listOf(b)) { }.single().specs)
    }
}
