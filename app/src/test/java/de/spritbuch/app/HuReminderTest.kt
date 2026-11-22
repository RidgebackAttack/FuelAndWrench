package de.spritbuch.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HuReminderTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test fun setDueCreatesDateReminderEvery24Months() = runBlocking {
        val repo = ctx.repo
        repo.db.vehicles().upsert(Vehicle(id = "v1", name = "Testwagen"))
        val due = Calendar.getInstance().apply { clear(); set(2027, Calendar.OCTOBER, 15) }.timeInMillis
        repo.setHuDue("v1", due)
        val r = repo.huReminder("v1")
        assertNotNull(r)
        assertEquals(24, r!!.intervalMonths)
        assertEquals(0, r.intervalKm)
        val d = Calendar.getInstance().apply { timeInMillis = r.dueDate!! }
        assertEquals(2027, d.get(Calendar.YEAR))
        assertEquals(Calendar.OCTOBER, d.get(Calendar.MONTH))
        // Erneut setzen ändert dieselbe Erinnerung, legt keine zweite an
        repo.setHuDue("v1", Calendar.getInstance().apply { clear(); set(2029, Calendar.OCTOBER, 15) }.timeInMillis)
        assertEquals(1, repo.db.reminders().forVehicle("v1").count { it.title == repo.huReminder("v1")!!.title })
    }
}
