package de.spritbuch.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatsTest {
    private fun r(odo: Int, amount: Double, full: Boolean = true, missed: Boolean = false, fuel: String = "Diesel",
                  price: Double = amount * 1.7, obc: Double? = null, socStart: Int? = null, socEnd: Int? = null) =
        Refuel(id = "$fuel-$odo", vehicleId = "v", timestamp = odo * 1000L, odometer = odo, amount = amount, totalPrice = price,
            fullTank = full, missedPrevious = missed, fuelType = fuel, obcConsumption = obc, socStart = socStart, socEnd = socEnd)

    private fun close(expected: Double, actual: Double?) = assertEquals(expected, actual ?: Double.NaN, 0.001)

    @Test
    fun fullToFull() {
        val s = computeStats(listOf(r(1000, 40.0), r(1600, 45.0)))
        close(7.5, s.perRefuel["Diesel-1600"])
        close(7.5, s.avg)
        close(7.5, s.last)
        assertEquals(600, s.distance)
    }

    @Test
    fun partialRefuelIsAddedToNextFull() {
        val s = computeStats(listOf(r(1000, 40.0), r(1300, 20.0, full = false), r(1600, 25.0)))
        assertNull(s.perRefuel["Diesel-1300"])
        close(7.5, s.perRefuel["Diesel-1600"])
    }

    @Test
    fun missedPreviousStartsNewChain() {
        val s = computeStats(listOf(r(1000, 40.0), r(1500, 99.0, missed = true), r(2000, 40.0)))
        assertNull(s.perRefuel["Diesel-1500"])
        close(8.0, s.perRefuel["Diesel-2000"])
        close(8.0, s.avg)
    }

    @Test
    fun orderOfInputDoesNotMatter() {
        val a = computeStats(listOf(r(1000, 40.0), r(1600, 45.0), r(2200, 42.0)))
        val b = computeStats(listOf(r(2200, 42.0), r(1000, 40.0), r(1600, 45.0)))
        assertEquals(a.perRefuel, b.perRefuel)
    }

    @Test
    fun onBoardComputerDeviation() {
        val s = computeStats(listOf(r(1000, 40.0), r(1600, 45.0, obc = 7.2)))
        close(-0.04, s.obcDeviation)
        assertEquals(1, s.obcCount)
    }

    @Test
    fun plugInHybridSeparatesFuelAndElectricity() {
        val s = computeStats(listOf(
            r(1000, 30.0, fuel = "Super E10"), r(2000, 30.0, fuel = "Super E10"),
            r(1000, 10.0, fuel = ELECTRIC, price = 3.0), r(1500, 8.0, fuel = ELECTRIC, price = 2.4), r(2000, 9.0, fuel = ELECTRIC, price = 2.7),
        ))
        close(3.0, s.avg)
        close(1.7, s.avgElectric)
        close(1.8, s.lastElectric)
        close(27.0, s.totalElectric)
        close(60.0, s.totalAmount)
        // Kosten pro 100 km: Sprit 30·1,7/1000·100 = 5,10 plus Strom 5,10/1000·100 = 0,51
        close(5.61, s.costPer100)
    }

    @Test
    fun electricVehicleUsesChargesAsPrimary() {
        val s = computeStats(listOf(r(1000, 10.0, fuel = ELECTRIC), r(1100, 16.0, fuel = ELECTRIC)))
        close(16.0, s.avg)
        assertNull(s.avgElectric)
    }

    @Test
    fun batteryConsumptionFromStateOfCharge() {
        val s = computeStats(
            listOf(r(1000, 30.0, fuel = ELECTRIC, socEnd = 80), r(1200, 25.0, fuel = ELECTRIC, socStart = 40, socEnd = 90)),
            batteryCapacity = 50.0,
        )
        // 40 % von 50 kWh = 20 kWh auf 200 km
        close(10.0, s.batteryAvg)
    }
}
