package de.spritbuch.app

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

val Context.repo: Repository get() = (applicationContext as App).repo

class Repository(private val ctx: Context, val db: AppDatabase) {
    val vehicles: Flow<List<Vehicle>> = db.vehicles().observeAll()
    val allRefuels: Flow<List<Refuel>> = db.refuels().observeAll()
    val allReminders: Flow<List<Reminder>> = db.reminders().observeAll()

    fun vehicle(id: String) = db.vehicles().observe(id)
    fun refuels(vehicleId: String) = db.refuels().observeForVehicle(vehicleId)
    fun reminders(vehicleId: String) = db.reminders().observeForVehicle(vehicleId)
    fun services(vehicleId: String) = db.services().observeForVehicle(vehicleId)
    fun tireSets(vehicleId: String) = db.tireSets().observeForVehicle(vehicleId)
    fun expenses(vehicleId: String) = db.expenses().observeForVehicle(vehicleId)
    suspend fun getExpense(id: String) = db.expenses().get(id)

    suspend fun saveExpense(e: Expense) {
        db.expenses().upsert(e.copy(updatedAt = now()))
        changed()
    }

    suspend fun deleteExpense(id: String) {
        db.expenses().get(id)?.let { db.expenses().upsert(it.copy(deleted = true, updatedAt = now())) }
        changed()
    }
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
                .filter { it.title.equals(s.title.trim(), ignoreCase = true) && (s.odometer > it.lastDoneKm || s.timestamp > it.lastDoneDate) }
                .forEach {
                    db.reminders().upsert(
                        it.copy(
                            lastDoneKm = maxOf(it.lastDoneKm, s.odometer),
                            lastDoneDate = maxOf(it.lastDoneDate, s.timestamp),
                            updatedAt = t,
                        )
                    )
                }
        }
        ReminderChecker.check(ctx, s.vehicleId)
        changed()
    }

    /**
     * Importiert Service-Einträge (Duplikate: gleicher km-Stand und Titel werden übersprungen).
     * Erinnerungen, deren Name im Titel oder in der Notiz vorkommt (z. B. „Ölwechsel“), werden
     * auf den höchsten passenden km-Stand als erledigt gesetzt.
     */
    /** Kosten importieren; gleiche Kategorie, gleicher Tag und gleicher Betrag gilt als Duplikat. */
    suspend fun importExpenses(vehicleId: String, items: List<Expense>): Pair<Int, Int> {
        var added = 0
        var dups = 0
        val t = now()
        db.withTransaction {
            val existing = db.expenses().forVehicle(vehicleId).toMutableList()
            for (e in items) {
                if (existing.any { it.category == e.category && kotlin.math.abs(it.amount - e.amount) < 0.005 &&
                        it.timestamp / 86_400_000 == e.timestamp / 86_400_000 }) { dups++; continue }
                val n = e.copy(vehicleId = vehicleId, updatedAt = t)
                db.expenses().upsert(n)
                existing += n
                added++
            }
        }
        if (added > 0) changed()
        return added to dups
    }

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
                val match = imported.filter { (it.title + " " + it.note).contains(r.title, ignoreCase = true) }
                val km = match.maxOfOrNull { it.odometer } ?: continue
                val date = match.maxOf { it.timestamp }
                if (km > r.lastDoneKm || date > r.lastDoneDate) {
                    db.reminders().upsert(r.copy(lastDoneKm = maxOf(km, r.lastDoneKm), lastDoneDate = maxOf(date, r.lastDoneDate), updatedAt = t))
                }
            }
        }
        if (added > 0) {
            ReminderChecker.check(ctx, vehicleId)
            changed()
        }
        return added to dups
    }

    /**
     * Repariert einmalig Daten aus älteren Spritmonitor-Importen:
     * - Zahlencodes bei Kraftstoff, Reifen, Fahrweise, Strecke → Klartext
     * - als Gesamtbetrag eingetragene Literpreise (z. B. 2,17 € für 65 l) → Gesamtbetrag
     */
    suspend fun repairImportedData() {
        val prefs = ctx.applicationContext.getSharedPreferences("spritbuch", Context.MODE_PRIVATE)
        if (prefs.getBoolean("repair_v1_done", false)) return
        val t = now()
        db.withTransaction {
            val vehicles = db.vehicles().getAllRaw().associateBy { it.id }
            for (r in db.refuels().getAllRaw()) {
                if (r.deleted) continue
                val v = vehicles[r.vehicleId] ?: continue
                fun isCode(s: String) = s.isNotEmpty() && s.all { it.isDigit() }
                val fuel = if (isCode(r.fuelType)) {
                    if (r.fuelType in setOf("1", "4") && v.defaultFuelType.contains("Diesel")) "Diesel" else v.defaultFuelType
                } else r.fuelType
                val tires = if (isCode(r.tireType)) {
                    when (r.tireType) { "1" -> "Sommerreifen"; "2" -> "Winterreifen"; "3" -> "Ganzjahresreifen"; else -> "" }
                } else r.tireType
                val style = if (isCode(r.drivingStyle)) {
                    when (r.drivingStyle) { "1" -> "Sparsam"; "2" -> "Normal"; "3" -> "Sportlich"; else -> "" }
                } else r.drivingStyle
                val route = if (isCode(r.routeType)) CsvImport.routeFromBits(r.routeType.toInt()) else r.routeType
                val total = if (r.totalPrice > 0 && r.amount >= 5 && r.totalPrice / r.amount < 0.3 && r.totalPrice in 0.5..5.0) {
                    r.totalPrice * r.amount
                } else r.totalPrice
                val fixed = r.copy(fuelType = fuel, tireType = tires, drivingStyle = style, routeType = route, totalPrice = total)
                if (fixed != r) db.refuels().upsert(fixed.copy(updatedAt = t))
            }
        }
        prefs.edit().putBoolean("repair_v1_done", true).apply()
        changed()
    }

    suspend fun deleteService(id: String) {
        db.services().get(id)?.let { db.services().upsert(it.copy(deleted = true, updatedAt = now())) }
        changed()
    }

    /** Erinnerung als erledigt markieren und dabei einen Service-Eintrag anlegen. */
    suspend fun completeReminder(r: Reminder, odometer: Int) {
        saveService(ServiceEntry(vehicleId = r.vehicleId, odometer = odometer, title = r.title, category = "Wartung"))
        db.reminders().get(r.id)?.let { db.reminders().upsert(it.copy(lastDoneKm = odometer, lastDoneDate = now(), updatedAt = now())) }
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
                            obcConsumption = r.obcConsumption?.let { if (Units.isLiquid(r.fuelType)) it * vf / df else it / df },
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
        for ((tpl, km, months) in Prefs(ctx).reminderTemplates) {
            val title = templateTitle(tpl)
            val id = "$vehicleId-auto-" + templateIdKey(tpl)
            if (existing.any { it.id == id || it.title.equals(title, ignoreCase = true) }) continue
            val interval = (km / Units.distFactorToKm(v.distanceUnit)).roundToInt()
            db.reminders().upsert(
                Reminder(
                    id = id, vehicleId = vehicleId, title = title, intervalKm = interval, lastDoneKm = odo,
                    intervalMonths = months, lastDoneDate = now(),
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
        var filled = 0
        db.withTransaction {
            val existing = db.refuels().forVehicle(vehicleId)
            val t = now()
            for (r in items) {
                val dup = existing.firstOrNull { it.odometer == r.odometer && kotlin.math.abs(it.amount - r.amount) < 0.01 }
                if (dup != null) {
                    duplicates++
                    // Erneuter Import ergänzt fehlende Bordcomputer-Werte
                    if (dup.obcConsumption == null && r.obcConsumption != null) {
                        db.refuels().upsert(dup.copy(obcConsumption = r.obcConsumption, updatedAt = t))
                        filled++
                    }
                    continue
                }
                db.refuels().upsert(r.copy(vehicleId = vehicleId, updatedAt = t))
                added++
            }
        }
        if (added > 0) {
            ensureDefaultReminders(vehicleId)
            ReminderChecker.check(ctx, vehicleId)
        }
        if (added > 0 || filled > 0) changed()
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

    private fun changed() {
        SyncScheduler.syncSoon(ctx)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { Shortcuts.update(ctx) }
    }
}
