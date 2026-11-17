package de.spritbuch.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReceiptParserTest {
    private fun close(expected: Double, actual: Double?) = assertEquals(expected, actual ?: Double.NaN, 0.005)

    @Test
    fun germanReceipt() {
        val text = """
            TEAM Tankstelle
            Husumer Str. 30
            24941 Flensburg
            12.11.2026  17:42
            Säule 3  Diesel
            45,32 l   x  1,689 EUR/l
            Diesel                    76,55 EUR
            SUMME EUR                 76,55
            girocard                  76,55
            MwSt 19% 12,22
        """.trimIndent()
        val r = ReceiptParser.parse(text, nowYear = 2026)
        close(45.32, r.amount)
        close(1.689, r.pricePerUnit)
        close(76.55, r.total)
        assertEquals(Triple(2026, 11, 12), r.date)
        assertEquals("Diesel", r.fuelType)
    }

    @Test
    fun totalAndAmountOnly() {
        val text = """
            ARAL
            Super E10
            Menge: 38,12
            Betrag  69,95
        """.trimIndent()
        val r = ReceiptParser.parse(text, nowYear = 2026)
        close(38.12, r.amount)
        close(69.95, r.total)
        close(1.835, r.pricePerUnit)
        assertEquals("Super E10", r.fuelType)
    }

    @Test
    fun danishReceipt() {
        val text = """
            Q8
            Blyfri 95 E10
            32,50 L  13,49 kr/L
            I ALT  438,43
            Dankort
            03-11-26
        """.trimIndent()
        val r = ReceiptParser.parse(text, nowYear = 2026)
        close(32.5, r.amount)
        close(13.49, r.pricePerUnit)
        close(438.43, r.total)
        assertEquals(Triple(2026, 11, 3), r.date)
        assertEquals("Super E10", r.fuelType)
    }

    @Test
    fun ocrConfusions() {
        val r = ReceiptParser.parse("4O,OO l 1,7S9 EUR/l", nowYear = 2026)
        close(40.0, r.amount)
        close(1.759, r.pricePerUnit)
    }

    @Test
    fun odometerPrefersPlausibleValue() {
        assertEquals(123456, ReceiptParser.odometer("TRIP 345.6\n123456 km\n21:30", previous = 123100))
        assertEquals(98765, ReceiptParser.odometer("98 765 km", previous = null))
        assertNull(ReceiptParser.odometer("12:30", previous = null))
    }

    @Test
    fun numbers() {
        close(1234.56, ReceiptParser.num("1.234,56"))
        close(1234.56, ReceiptParser.num("1,234.56"))
        close(1.689, ReceiptParser.num("1,689"))
    }

    @Test
    fun chargingReceipt() {
        val text = """
            EnBW mobility+
            Ladevorgang 12.11.2026
            Energie: 42,35 kWh
            0,59 EUR/kWh
            Gesamtbetrag 24,99 EUR
        """.trimIndent()
        val r = ReceiptParser.parse(text, nowYear = 2026)
        close(42.35, r.amount)
        close(0.59, r.pricePerUnit)
        close(24.99, r.total)
        assertEquals(ELECTRIC, r.fuelType)
    }
}
