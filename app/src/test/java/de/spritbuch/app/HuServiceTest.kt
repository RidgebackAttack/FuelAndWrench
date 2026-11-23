package de.spritbuch.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar

/** HU/TÜV als Service-Eintrag: Ergebnis setzt die Fälligkeit, alte TÜV-Dokumente werden übernommen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HuServiceTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private fun day(y: Int, m: Int, d: Int) = Calendar.getInstance().apply { clear(); set(y, m, d) }.timeInMillis
    private fun Long.ym() = Calendar.getInstance().apply { timeInMillis = this@ym }.let { it.get(Calendar.YEAR) to it.get(Calendar.MONTH) }

    @Test fun passedSetsNextDueMajorDoesNot() = runBlocking {
        val repo = ctx.repo
        repo.db.vehicles().upsert(Vehicle(id = "h1", name = "Test"))
        val due = repo.saveService(ServiceEntry(vehicleId = "h1", timestamp = day(2026, Calendar.MARCH, 10), category = HU_CATEGORY, title = "HU", huResult = HU_OK))
        assertEquals(2028 to Calendar.MARCH, due!!.ym())
        assertEquals(2028 to Calendar.MARCH, repo.huReminder("h1")!!.dueDate!!.ym())

        // Erhebliche Mängel: keine neue Plakette, Fälligkeit bleibt
        val none = repo.saveService(ServiceEntry(vehicleId = "h1", timestamp = day(2028, Calendar.MARCH, 1), category = HU_CATEGORY, title = "HU", huResult = HU_MAJOR))
        assertNull(none)
        assertEquals(2028 to Calendar.MARCH, repo.huReminder("h1")!!.dueDate!!.ym())

        // Nachgetragener alter Bericht setzt die Fälligkeit nicht zurück
        assertNull(repo.saveService(ServiceEntry(vehicleId = "h1", timestamp = day(2024, Calendar.MARCH, 1), category = HU_CATEGORY, title = "HU", huResult = HU_OK)))
        assertEquals(2028 to Calendar.MARCH, repo.huReminder("h1")!!.dueDate!!.ym())
    }

    @Test fun inspectionDocsBecomeServiceEntriesOnce() = runBlocking {
        val repo = ctx.repo
        val docs = listOf(
            VehicleDoc(id = "reg", type = VehicleDoc.TYPE_REGISTRATION, files = listOf("p1.jpg")),
            VehicleDoc(id = "t1", type = VehicleDoc.TYPE_INSPECTION, title = "Nachprüfung", date = day(2025, Calendar.MAY, 2), files = listOf("b.pdf")),
        )
        repo.db.vehicles().upsert(Vehicle(id = "h2", name = "Test", documents = VehicleDoc.encodeList(docs)))
        repo.convertInspectionDocs()
        repo.convertInspectionDocs()
        val hu = repo.db.services().forVehicle("h2").filter { it.category == HU_CATEGORY }
        assertEquals(1, hu.size)
        assertEquals("doc-t1", hu[0].id)
        assertEquals(listOf("b.pdf"), hu[0].attachmentList())
        assertEquals("Nachprüfung", hu[0].note)
        assertEquals(listOf("reg"), repo.getVehicle("h2")!!.documentList().map { it.id })
    }

    @Test fun huReminderRecognised() {
        assertTrue(Reminder(id = "x-hu", vehicleId = "x").isHu)
        assertTrue(Reminder(vehicleId = "x", title = "TÜV", intervalMonths = 24).isHu)
        assertTrue(!Reminder(vehicleId = "x", title = "Ölwechsel", intervalMonths = 12).isHu)
    }
}
