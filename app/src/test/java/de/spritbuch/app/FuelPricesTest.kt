package de.spritbuch.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FuelPricesTest {
    // Aufbau wie die Beispielantwort von list.php (gekürzt, ausgedachte Tankstellen)
    private val sample = """
        {"ok":true,"license":"CC BY 4.0 -  https:\/\/creativecommons.tankerkoenig.de","data":"MTS-K","status":"ok","stations":[
          {"id":"a","name":"TEAM TANKSTELLE","brand":"TEAM","street":"HAUPTSTRASSE","place":"MUSTERSTADT","lat":54.10,"lng":9.10,"dist":2.1,"price":1.659,"isOpen":true,"houseNumber":"12","postCode":24000},
          {"id":"b","name":"Aral Tankstelle","brand":"ARAL","street":"Ringstraße","place":"Musterstadt","lat":54.11,"lng":9.12,"dist":0.8,"price":1.629,"isOpen":true,"houseNumber":"","postCode":24000},
          {"id":"c","name":"Nachts zu","brand":"","street":"Weg","place":"Musterstadt","lat":54.12,"lng":9.13,"dist":0.5,"price":1.599,"isOpen":false,"houseNumber":"1","postCode":24000},
          {"id":"d","name":"Ohne Preis","brand":"HEM","street":"Allee","place":"Musterstadt","lat":54.13,"lng":9.14,"dist":0.3,"price":null,"isOpen":true,"houseNumber":"","postCode":24000}
        ]}
    """.trimIndent()

    @Test fun parseSortsOpenCheapestFirst() {
        val list = FuelPrices.parse(sample)
        assertEquals(listOf("b", "a", "c", "d"), list.map { it.id })
        assertEquals(1.629, list[0].price!!, 0.0001)
    }

    @Test fun upperCaseNamesAreTidied() {
        val team = FuelPrices.parse(sample).first { it.id == "a" }
        assertEquals("Team", team.label)
        assertEquals("Hauptstrasse 12", team.street)
        assertEquals("HEM", FuelPrices.parse(sample).first { it.id == "d" }.label)
    }

    @Test(expected = FuelPrices.ApiError::class)
    fun invalidKeyIsReported() {
        FuelPrices.parse("""{"ok":false,"message":"apikey nicht gefunden"}""")
    }

    @Test fun fuelTypes() {
        assertEquals("e5", FuelPrices.typeFor("Super E5"))
        assertEquals("e10", FuelPrices.typeFor("Super E10"))
        assertEquals("diesel", FuelPrices.typeFor("Diesel"))
        assertNull(FuelPrices.typeFor("Super Plus"))
        assertNull(FuelPrices.typeFor(ELECTRIC))
    }

    @Test fun stationAtLocation() {
        val list = FuelPrices.parse(sample)
        assertEquals("b", FuelPrices.at(list, 54.1103, 9.1201)?.id)
        assertNull(FuelPrices.at(list, 54.20, 9.30))
    }

    @Test fun deviation() {
        assertFalse(FuelPrices.deviates(1.659, 1.629))
        assertTrue(FuelPrices.deviates(1.859, 1.629)) // typischer Lesefehler 8 ↔ 6
    }

    @Test fun locationIsRounded() {
        assertEquals(54.12, FuelPrices.round(54.1234), 0.0)
        assertEquals(9.99, FuelPrices.round(9.987), 0.0)
    }
}
