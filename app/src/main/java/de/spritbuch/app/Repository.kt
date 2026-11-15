package de.spritbuch.app

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlin.math.roundToInt

val Context.repo: Repository get() = (applicationContext as App).repo

class Repository(private val ctx: Context, val db: AppDatabase) {
    val vehicles: Flow<List<Vehicle>> = db.vehicles().observeAll()
    val allRefuels: Flow<List<Refuel>> = db.refuels().observeAll()

    fun vehicle(id: String) = db.vehicles().observe(id)
    fun refuels(vehicleId: String) = db.refuels().observeForVehicle(vehicleId)
    fun reminders(vehicleId: String) = db.reminders().observeForVehicle(vehicleId)
    fun services(vehicleId: String) = db.services().observeForVehicle(vehicleId)
    fun tireSets(vehicleId: String) = db.tireSets().observeForVehicle(vehicleId)
    suspend fun getTireSet(id: String) = db.tireSets().get(id)

    suspend fun saveTireSet(t: TireSet) {
        db.tireSets().upsert(t.copy(updatedAt = now()))
        changed()
    }

    suspend fun deleteTireSet(id: String) {
        db.tireSets().get(id)?.let { db.tireSets().upsert(it.copy(deleted = true, updatedAt = now())) }
        changed()
    }

    /** Aktuell montierter Radsatz = montierter Satz des letzten Radwechsels. */
    suspend fun mountedTireSetId(vehicleId: String): String? = db.services().lastWheelChange(vehicleId)?.tireSetId
    suspend fun getService(id: String) = db.services().get(id)

    /**
     * Speichert einen Service-Eintrag. Gibt es eine Erinnerung mit gleichem Namen, wird deren
     * "zuletzt erledigt" auf den Kilometerstand des Eintrags gesetzt.
     */
    suspend fun saveService(s: ServiceEntry) {
        val t = now()
        db.withTransaction {
            db.services().upsert(s.copy(updatedAt = t))
            db.reminders().forVehicle(s.vehicleId)
                .filter { it.title.equals(s.title.trim(), ignoreCase = true) && s.odometer > it.lastDoneKm }
                .forEach { db.reminders().upsert(it.copy(lastDoneKm = s.odometer, updatedAt = t)) }
        }
        ReminderChecker.check(ctx, s.vehicleId)
        changed()
    }

    /**
     * Importiert Service-Einträge (Duplikate: gleicher km-Stand und Titel werden übersprungen).
     * Erinnerungen, deren Name im Titel oder in der Notiz vorkommt (z. B. „Ölwechsel“), werden
     * auf den höchsten passenden km-Stand als erledigt gesetzt.
     */
    suspend fun importServices(vehicleId: String, items: List<ServiceEntry>): Pair<Int, Int> {
        var added = 0
        var dups = 0
        val t = now()
        db.withTransaction {
            val existing = db.services().forVehicle(vehicleId)
            val imported = mutableListOf<ServiceEntry>()
            for (s in items) {
                if (existing.any { it.odometer == s.odometer && it.title.equals(s.title, true) && it.timestamp / 86_400_000 == s.timestamp / 86_400_000 }) {
                    dups++; continue
                }
                val e = s.copy(vehicleId = vehicleId, updatedAt = t)
                db.services().upsert(e)
                imported += e
                added++
            }
            for (r in db.reminders().forVehicle(vehicleId)) {
                val km = imported.filter { (it.title + " " + it.note).contains(r.title, ignoreCase = true) }
                    .maxOfOrNull { it.odometer } ?: continue
                if (km > r.lastDoneKm) db.reminders().upsert(r.copy(lastDoneKm = km, updatedAt = t))
            }
        }
        if (added > 0) {
            ReminderChecker.check(ctx, vehicleId)
            changed()
        }
        return added to dups
    }

    suspend fun deleteService(id: String) {
        db.services().get(id)?.let { db.services().upsert(it.copy(deleted = true, updatedAt = now())) }
        changed()
    }

    /** Erinnerung als erledigt markieren und dabei einen Service-Eintrag anlegen. */
    suspend fun completeReminder(r: Reminder, odometer: Int) {
        saveService(ServiceEntry(vehicleId = r.vehicleId, odometer = odometer, title = r.title, category = "Wartung"))
        db.reminders().get(r.id)?.let { db.reminders().upsert(it.copy(lastDoneKm = odometer, updatedAt = now())) }
        changed()
    }

    suspend fun getVehicle(id: String) = db.vehicles().get(id)
    suspend fun getRefuel(id: String) = db.refuels().get(id)
    suspend fun getReminder(id: String) = db.reminders().get(id)
    suspend fun lastRefuel(vehicleId: String) = db.refuels().last(vehicleId)

    suspend fun currentOdometer(vehicleId: String): Int {
        val v = db.vehicles().get(vehicleId) ?: return 0
        return maxOf(v.initialOdometer, v.manualOdometer, db.refuels().maxOdometer(vehicleId) ?: 0)
    }

    /**
     * Speichert ein Fahrzeug. Wurden Entfernungs- oder Volumeneinheit geändert, werden alle
     * vorhandenen Tankvorgänge, Erinnerungen und Kilometerstände umgerechnet.
     */
    suspend fun saveVehicle(v: Vehicle) {
        val t = now()
        db.withTransaction {
            val old = db.vehicles().get(v.id)
            var toSave = v
            if (old != null && (old.distanceUnit != v.distanceUnit || old.volumeUnit != v.volumeUnit)) {
                val df = Units.distFactorToKm(old.distanceUnit) / Units.distFactorToKm(v.distanceUnit)
                val vf = Units.volFactorToL(old.volumeUnit) / Units.volFactorToL(v.volumeUnit)
                fun d(x: Int) = (x * df).roundToInt()
                db.refuels().forVehicle(v.id).forEach { r ->
                    db.refuels().upsert(
                        r.copy(
                            odometer = d(r.odometer),
                            amount = if (Units.isLiquid(r.fuelType)) r.amount * vf else r.amount,
                            updatedAt = t,
                        )
                    )
                }
                db.services().forVehicle(v.id).forEach { s ->
                    db.services().upsert(s.copy(odometer = d(s.odometer), updatedAt = t))
                }
                db.reminders().forVehicle(v.id).forEach { r ->
                    db.reminders().upsert(
                        r.copy(intervalKm = d(r.intervalKm), lastDoneKm = d(r.lastDoneKm), warnBeforeKm = d(r.warnBeforeKm), updatedAt = t)
                    )
                }
                toSave = v.copy(
                    initialOdometer = d(v.initialOdometer),
                    manualOdometer = d(v.manualOdometer),
                    tankCapacity = if (Units.isLiquid(v.defaultFuelType)) v.tankCapacity?.let { it * vf } else v.tankCapacity,
                )
            }
            db.vehicles().upsert(toSave.copy(updatedAt = t))
        }
        ensureDefaultReminders(v.id)
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
            db.services().forVehicle(id).forEach { db.services().upsert(it.copy(deleted = true, updatedAt = t)) }
            db.tireSets().forVehicle(id).forEach { db.tireSets().upsert(it.copy(deleted = true, updatedAt = t)) }
        }
        changed()
    }

    /**
     * Legt die Standard-Erinnerungen (Einstellungen) für ein Fahrzeug an, sobald ein Kilometerstand bekannt ist.
     * Erledigt-Stand = aktueller Kilometerstand. Bereits vorhandene oder vom Nutzer gelöschte werden nicht erneut angelegt.
     * Feste IDs verhindern Doppelte, wenn zwei Geräte gleichzeitig anlegen.
     */
    suspend fun ensureDefaultReminders(vehicleId: String) {
        val v = db.vehicles().get(vehicleId) ?: return
        if (v.deleted || v.archived) return
        val odo = currentOdometer(vehicleId)
        if (odo <= 0) return
        val existing = db.reminders().forVehicleRaw(vehicleId)
        var created = false
        for ((title, km) in Prefs(ctx).reminderTemplates) {
            val id = "$vehicleId-auto-" + title.lowercase().filter { it.isLetterOrDigit() }
            if (existing.any { it.id == id || it.title.equals(title, ignoreCase = true) }) continue
            val interval = (km / Units.distFactorToKm(v.distanceUnit)).roundToInt()
            db.reminders().upsert(
                Reminder(
                    id = id, vehicleId = vehicleId, title = title, intervalKm = interval, lastDoneKm = odo,
                    warnBeforeKm = (interval * 0.05).roundToInt().coerceAtLeast(100), updatedAt = now(),
                )
            )
            created = true
        }
        if (created) changed()
    }

    suspend fun saveRefuel(r: Refuel) {
        db.refuels().upsert(r.copy(updatedAt = now()))
        ensureDefaultReminders(r.vehicleId)
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
            ensureDefaultReminders(vehicleId)
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
