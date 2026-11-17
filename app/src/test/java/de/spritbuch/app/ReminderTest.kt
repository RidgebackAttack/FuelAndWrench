package de.spritbuch.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class ReminderTest {
    private fun day(y: Int, m: Int, d: Int) = Calendar.getInstance().apply { clear(); set(y, m - 1, d, 12, 0) }.timeInMillis

    @Test
    fun byDistance() {
        val r = Reminder(intervalKm = 10000, lastDoneKm = 100000, warnBeforeKm = 500)
        assertEquals(110000, r.dueKm)
        r.state(105000).let { assertFalse(it.warn); assertFalse(it.overdue); assertEquals(5000, it.remainingKm); assertNull(it.remainingDays) }
        r.state(109600).let { assertTrue(it.warn); assertFalse(it.overdue) }
        r.state(110100).let { assertTrue(it.overdue); assertEquals(-100, it.remainingKm) }
    }

    @Test
    fun byDate() {
        val r = Reminder(intervalKm = 0, intervalMonths = 24, lastDoneDate = day(2026, 1, 15))
        assertFalse(r.byKm)
        assertTrue(r.byDate)
        val due = Calendar.getInstance().apply { timeInMillis = r.dueDate!! }
        assertEquals(2028, due.get(Calendar.YEAR))
        assertEquals(Calendar.JANUARY, due.get(Calendar.MONTH))
        assertEquals(15, due.get(Calendar.DAY_OF_MONTH))
        r.state(0, at = day(2027, 6, 1)).let { assertFalse(it.warn); assertFalse(it.overdue) }
        r.state(0, at = day(2027, 12, 26)).let { assertTrue(it.warn); assertFalse(it.overdue) }
        r.state(0, at = day(2028, 2, 1)).let { assertTrue(it.overdue) }
    }

    @Test
    fun whicheverComesFirst() {
        val r = Reminder(intervalKm = 30000, lastDoneKm = 0, warnBeforeKm = 1000, intervalMonths = 12, lastDoneDate = day(2026, 1, 1))
        // Strecke noch weit weg, Datum überschritten → fällig
        assertTrue(r.state(5000, at = day(2027, 2, 1)).overdue)
        // Datum weit weg, Strecke überschritten → fällig
        assertTrue(r.state(31000, at = day(2026, 3, 1)).overdue)
    }

    @Test
    fun withoutDateNoDateCheck() {
        val r = Reminder(intervalKm = 10000, lastDoneKm = 0, intervalMonths = 12, lastDoneDate = 0)
        assertFalse(r.byDate)
        assertNull(r.dueDate)
    }
}
