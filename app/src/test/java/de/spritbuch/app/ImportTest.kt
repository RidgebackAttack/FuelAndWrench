package de.spritbuch.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.Calendar

class ImportTest {
    @Test
    fun parseNumbers() {
        assertEquals(1234.56, CsvImport.parseNum("1.234,56", true)!!, 0.0001)
        assertEquals(53.76, CsvImport.parseNum("53,76", true)!!, 0.0001)
        assertEquals(413158.0, CsvImport.parseNum("413158,00", true)!!, 0.0001)
        assertEquals(1500.0, CsvImport.parseNum("1.500", true)!!, 0.0001)
        assertEquals(1.5, CsvImport.parseNum("1.500", false)!!, 0.0001)
    }

    @Test
    fun parseDates() {
        val ts = CsvImport.parseDate("08.11.2026", "")
        assertNotNull(ts)
        val c = Calendar.getInstance().apply { timeInMillis = ts!! }
        assertEquals(2026, c.get(Calendar.YEAR))
        assertEquals(Calendar.NOVEMBER, c.get(Calendar.MONTH))
        assertEquals(8, c.get(Calendar.DAY_OF_MONTH))
        assertNotNull(CsvImport.parseDate("2026-11-08 17:42", ""))
    }

    @Test
    fun spritmonitorSample() {
        // Ausgedachte Beispielzeilen im Spritmonitor-Format (keine echten Daten)
        val csv = """
            Datum;Km-Stand;Teil-Km;Spritmenge;Kosten;Währung;Tankart;Reifen;Strecken;Fahrweise;Kraftstoff;Bemerkung;Verbrauch;BC-Verbrauch;BC-Spritmenge;BC-Geschwindigkeit;Tankstelle;Land;Großraum;Ort;Breitengrad;Längengrad
            01.10.2026;100000,00;0,00;50,00;85,00;"EUR";1;1;1;1;1;"";0,00;0,00;0,00;0,00;"Aral";"D";"";"Teststraße 1, 12345 Musterstadt";53,5;10,0
            15.10.2026;100700,00;700,00;52,50;89,25;"EUR";1;1;1;1;1;"Test";7,50;7,20;0,00;0,00;"Team";"D";"";"";0;0
        """.trimIndent()
        val res = CsvImport.parse(csv, Vehicle(id = "v"))
        assertEquals(2, res.refuels.size)
        val second = res.refuels.maxBy { it.odometer }
        assertEquals(100700, second.odometer)
        assertEquals(52.5, second.amount, 0.001)
        assertEquals(89.25, second.totalPrice, 0.001)
        assertEquals(7.2, second.obcConsumption!!, 0.001)
        assertEquals(7.5, computeStats(res.refuels).perRefuel[second.id]!!, 0.001)
    }
}
