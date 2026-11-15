package de.spritbuch.app

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow

val Context.repo: Repository get() = (applicationContext as App).repo

class Repository(private val ctx: Context, val db: AppDatabase) {
    val vehicles: Flow<List<Vehicle>> = db.vehicles().observeAll()
    val allRefuels: Flow<List<Refuel>> = db.refuels().observeAll()

    fun vehicle(id: String) = db.vehicles().observe(id)
    fun refuels(vehicleId: String) = db.refuels().observeForVehicle(vehicleId)
    fun reminders(vehicleId: String) = db.reminders().observeForVehicle(vehicleId)

    suspend fun getVehicle(id: String) = db.vehicles().get(id)
    suspend fun getRefuel(id: String) = db.refuels().get(id)
    suspend fun getReminder(id: String) = db.reminders().get(id)
    suspend fun lastRefuel(vehicleId: String) = db.refuels().last(vehicleId)

    suspend fun currentOdometer(vehicleId: String): Int {
        val v = db.vehicles().get(vehicleId) ?: return 0
        return maxOf(v.initialOdometer, v.manualOdometer, db.refuels().maxOdometer(vehicleId) ?: 0)
    }

    suspend fun saveVehicle(v: Vehicle) {
        db.vehicles().upsert(v.copy(updatedAt = now()))
        ReminderChecker.check(ctx, v.id)
        changed()
    }

    suspend fun setManualOdometer(vehicleId: String, km: Int) {
        val v = db.vehicles().get(vehicleId) ?: return
        saveVehicle(v.copy(manualOdometer = km))
    }

    suspend fun deleteVehicle(id: String) {
        val t = now()
        db.withTransaction {
            db.vehicles().get(id)?.let { db.vehicles().upsert(it.copy(deleted = true, updatedAt = t)) }
            db.refuels().forVehicle(id).forEach { db.refuels().upsert(it.copy(deleted = true, updatedAt = t)) }
            db.reminders().forVehicle(id).forEach { db.reminders().upsert(it.copy(deleted = true, updatedAt = t)) }
        }
        changed()
    }

    suspend fun saveRefuel(r: Refuel) {
        db.refuels().upsert(r.copy(updatedAt = now()))
        ReminderChecker.check(ctx, r.vehicleId)
        changed()
    }

    /** Importiert Tankvorgänge; Einträge mit gleichem Kilometerstand und gleicher Menge werden übersprungen. */
    suspend fun importRefuels(vehicleId: String, items: List<Refuel>): Pair<Int, Int> {
        var added = 0
        var duplicates = 0
        db.withTransaction {
            val existing = db.refuels().forVehicle(vehicleId)
            val t = now()
            for (r in items) {
                val dup = existing.any { it.odometer == r.odometer && kotlin.math.abs(it.amount - r.amount) < 0.01 }
                if (dup) { duplicates++; continue }
                db.refuels().upsert(r.copy(vehicleId = vehicleId, updatedAt = t))
                added++
            }
        }
        if (added > 0) {
            ReminderChecker.check(ctx, vehicleId)
            changed()
        }
        return added to duplicates
    }

    suspend fun deleteRefuel(id: String) {
        db.refuels().get(id)?.let { db.refuels().upsert(it.copy(deleted = true, updatedAt = now())) }
        changed()
    }

    suspend fun saveReminder(r: Reminder) {
        db.reminders().upsert(r.copy(updatedAt = now()))
        ReminderChecker.check(ctx, r.vehicleId)
        changed()
    }

    suspend fun deleteReminder(id: String) {
        db.reminders().get(id)?.let { db.reminders().upsert(it.copy(deleted = true, updatedAt = now())) }
        changed()
    }

    private fun changed() = SyncScheduler.syncSoon(ctx)
}
