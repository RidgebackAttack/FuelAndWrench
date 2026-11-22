package de.spritbuch.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentsTest {
    @Test fun roundTripAndOrder() {
        val docs = listOf(
            VehicleDoc(id = "t1", type = VehicleDoc.TYPE_INSPECTION, date = 1_000L, files = listOf("a.jpg")),
            VehicleDoc(id = "f", type = VehicleDoc.TYPE_REGISTRATION, files = listOf("p1.jpg", "p2.jpg")),
            VehicleDoc(id = "t2", type = VehicleDoc.TYPE_INSPECTION, date = 5_000L, files = listOf("b.pdf")),
            VehicleDoc(id = "v", type = VehicleDoc.TYPE_INSURANCE, files = listOf("c.jpg")),
        )
        val v = Vehicle(documents = VehicleDoc.encodeList(docs))
        // Fahrzeugschein, Versicherungskarte, dann TÜV-Berichte (neueste zuerst)
        assertEquals(listOf("f", "v", "t2", "t1"), v.documentList().map { it.id })
        assertEquals(listOf("p1.jpg", "p2.jpg"), v.documentList().first().files)
    }

    @Test fun emptyOrBrokenIsEmpty() {
        assertTrue(VehicleDoc.parseList("").isEmpty())
        assertTrue(VehicleDoc.parseList("{kaputt").isEmpty())
    }
}
