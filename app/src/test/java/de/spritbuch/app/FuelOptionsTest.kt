package de.spritbuch.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FuelOptionsTest {
    @Test
    fun petrolCarShowsOnlyPetrol() {
        val l = Options.fuelsFor(Vehicle(defaultFuelType = "Super E10"))
        assertEquals(listOf("Super E5", "Super E10", "Super Plus"), l)
    }

    @Test
    fun dieselCarShowsOnlyDiesel() {
        val l = Options.fuelsFor(Vehicle(defaultFuelType = "Diesel"))
        assertTrue("HVO100" in l)
        assertFalse("Super E10" in l || ELECTRIC in l || "LPG" in l)
    }

    @Test
    fun gasCarIsBivalent() {
        assertEquals(listOf("LPG", "Super E5", "Super E10", "Super Plus"), Options.fuelsFor(Vehicle(defaultFuelType = "LPG")))
    }

    @Test
    fun electricOnlyElectricity() {
        assertEquals(listOf(ELECTRIC), Options.fuelsFor(Vehicle(driveType = "Elektro", defaultFuelType = ELECTRIC)))
    }

    @Test
    fun currentValueStaysSelectable() {
        assertTrue("Diesel" in Options.fuelsFor(Vehicle(defaultFuelType = "Super E10"), current = "Diesel"))
    }
}
