package de.spritbuch.app

import android.content.Context
import java.io.File
import java.util.Calendar

// ---------------------------------------------------------------------------------------------
// Fahrzeug in Teilen zusammenführen
// ---------------------------------------------------------------------------------------------

/** Ein Teil des Fahrzeugs mit eigenem Zeitstempel (Steckbrief, Dokumente, Fotos …). */
private class VehiclePart(
    val ts: (Vehicle) -> Long,
    val content: (Vehicle) -> Any,
    /** Inhalt samt Zeitstempel aus `from` übernehmen. */
    val take: (Vehicle, Vehicle) -> Vehicle,
    val stamp: (Vehicle, Long) -> Vehicle,
)

private val vehicleParts = listOf(
    VehiclePart(
        { it.specsUpdatedAt }, { it.specs },
        { v, f -> v.copy(specs = f.specs, specsUpdatedAt = f.specsUpdatedAt) }, { v, t -> v.copy(specsUpdatedAt = t) },
    ),
    VehiclePart(
        { it.docsUpdatedAt }, { it.documents },
        { v, f -> v.copy(documents = f.documents, docsUpdatedAt = f.docsUpdatedAt) }, { v, t -> v.copy(docsUpdatedAt = t) },
    ),
    VehiclePart(
        { it.photosUpdatedAt }, { listOf(it.photoVersion, it.photoThumbPos, it.photoLargePos, it.extraPhotos, it.photoOrder) },
        { v, f ->
            v.copy(
                photoVersion = f.photoVersion, photoThumbPos = f.photoThumbPos, photoLargePos = f.photoLargePos,
                extraPhotos = f.extraPhotos, photoOrder = f.photoOrder, photosUpdatedAt = f.photosUpdatedAt,
            )
        },
        { v, t -> v.copy(photosUpdatedAt = t) },
    ),
    VehiclePart(
        { it.odoUpdatedAt }, { it.manualOdometer },
        { v, f -> v.copy(manualOdometer = f.manualOdometer, odoUpdatedAt = f.odoUpdatedAt) }, { v, t -> v.copy(odoUpdatedAt = t) },
    ),
    VehiclePart(
        { it.tireDoneUpdatedAt }, { it.tireSeasonDone },
        { v, f -> v.copy(tireSeasonDone = f.tireSeasonDone, tireDoneUpdatedAt = f.tireDoneUpdatedAt) }, { v, t -> v.copy(tireDoneUpdatedAt = t) },
    ),
)

/**
 * Teile (Steckbrief, Dokumente, Fotos, Kilometerstand, Reifen erledigt) aus der Fassung mit dem jeweils
 * neueren eigenen Zeitstempel übernehmen, alles andere aus [base]. Bei Gleichstand bleibt [base].
 */
fun mergeVehicleParts(base: Vehicle, other: Vehicle): Vehicle =
    vehicleParts.fold(base) { v, p -> if (p.ts(other) > p.ts(v)) p.take(v, other) else v }

/**
 * Beim Speichern: geänderte Teile bekommen den Zeitstempel [t]. Kommt [v] aus einem Formular mit
 * veraltetem Stand (der Sync hat den Teil inzwischen geändert), bleibt der neuere Stand aus [old].
 */
fun stampVehicleParts(v: Vehicle, old: Vehicle?, t: Long): Vehicle {
    val empty = Vehicle(id = v.id)
    return vehicleParts.fold(v) { x, p ->
        when {
            old == null -> if (p.content(x) != p.content(empty)) p.stamp(x, t) else x
            p.ts(x) < p.ts(old) -> p.take(x, old)
            p.content(x) != p.content(old) -> p.stamp(x, t)
            else -> x
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Gelöschtes endgültig entfernen
// ---------------------------------------------------------------------------------------------

object Purge {
    /** So lange bleiben gelöschte Einträge als Löschvermerk erhalten, damit alle Geräte die Löschung mitbekommen. */
    const val AFTER_MS = 365L * 24 * 60 * 60 * 1000

    fun cutoff(now: Long = now()) = now - AFTER_MS

    suspend fun local(db: AppDatabase, cutoff: Long): Int =
        db.refuels().purgeDeleted(cutoff) + db.services().purgeDeleted(cutoff) + db.reminders().purgeDeleted(cutoff) +
            db.tireSets().purgeDeleted(cutoff) + db.expenses().purgeDeleted(cutoff) + db.vehicles().purgeDeleted(cutoff)

    /** Alte Löschvermerke aus dem Stand vom Server herausnehmen. */
    fun remote(d: SyncData, cutoff: Long): SyncData = d.copy(
        vehicles = d.vehicles.filterNot { it.deleted && it.updatedAt < cutoff },
        refuels = d.refuels.filterNot { it.deleted && it.updatedAt < cutoff },
        reminders = d.reminders.filterNot { it.deleted && it.updatedAt < cutoff },
        services = d.services.filterNot { it.deleted && it.updatedAt < cutoff },
        tireSets = d.tireSets.filterNot { it.deleted && it.updatedAt < cutoff },
        expenses = d.expenses.filterNot { it.deleted && it.updatedAt < cutoff },
    )
}

// ---------------------------------------------------------------------------------------------
// Verwaiste Dateien
// ---------------------------------------------------------------------------------------------

object StorageCleanup {
    /** Gerade erst angelegte Dateien (z. B. Beleg in einem offenen Formular) nicht anfassen. */
    private const val MIN_AGE_MS = 60L * 60 * 1000

    class Found(val local: List<File>, val remote: List<Pair<String, Long>>) {
        val localBytes: Long get() = local.sumOf { it.length() }
        val remoteBytes: Long get() = remote.sumOf { it.second }
        val isEmpty: Boolean get() = local.isEmpty() && remote.isEmpty()
    }

    /** Alle Dateinamen, auf die noch ein (nicht gelöschter) Eintrag verweist – über alle Fahrzeuge, auch ausgeblendete. */
    suspend fun referenced(db: AppDatabase): Pair<Set<String>, Set<String>> {
        val vehicles = db.vehicles().getAllRaw().filter { !it.deleted }
        val attachments = db.services().getAllRaw().filter { !it.deleted }.flatMap { it.attachmentList() } +
            db.expenses().getAllRaw().filter { !it.deleted }.flatMap { it.attachmentList() } +
            vehicles.flatMap { v -> VehicleDoc.parseList(v.documents).flatMap { it.files } }
        val photos = vehicles.flatMap { v -> v.images().map { PhotoStore.name(v.id, it.version) } }
        return attachments.toSet() to photos.toSet()
    }

    fun photoDir(ctx: Context) = File(ctx.filesDir, "photos")

    fun localOrphans(ctx: Context, attachments: Set<String>, photos: Set<String>, now: Long = now()): List<File> {
        fun scan(dir: File, keep: Set<String>) = (dir.listFiles() ?: emptyArray())
            .filter { it.isFile && it.name !in keep && now - it.lastModified() > MIN_AGE_MS }
        return scan(AttachmentStore.dir(ctx), attachments) + scan(photoDir(ctx), photos)
    }

    fun deleteLocal(ctx: Context, files: List<File>) {
        files.forEach { it.delete() }
        val prefs = Prefs(ctx)
        val names = files.map { it.name }.toSet()
        prefs.uploadedAttachments = prefs.uploadedAttachments - names
        prefs.uploadedPhotos = prefs.uploadedPhotos - names
    }
}

// ---------------------------------------------------------------------------------------------
// Plausibilitätsprüfung
// ---------------------------------------------------------------------------------------------

object DataCheck {
    enum class Kind { ODO_BACKWARDS, OVER_TANK, DUPLICATE, FUTURE }

    data class Issue(val vehicleId: String, val refuel: Refuel, val kind: Kind, val text: String) {
        val key: String get() = "${refuel.id}:$kind"
    }

    fun check(v: Vehicle, refuels: List<Refuel>, now: Long = now()): List<Issue> {
        val list = refuels.filter { !it.deleted && it.vehicleId == v.id }.sortedWith(compareBy({ it.timestamp }, { it.odometer }))
        val out = mutableListOf<Issue>()
        val du = v.distanceUnit
        var maxSoFar: Refuel? = null
        list.forEachIndexed { i, r ->
            val prev = maxSoFar
            if (r.odometer > 0 && prev != null && r.odometer < prev.odometer) {
                out += Issue(v.id, r, Kind.ODO_BACKWARDS,
                    str(R.string.check_odo_backwards, r.odometer.dist(du), prev.odometer.dist(du), prev.timestamp.date()))
            }
            if (r.odometer > 0 && (prev == null || r.odometer > prev.odometer)) maxSoFar = r
            val cap = v.tankCapacity
            if (cap != null && cap > 0 && !r.isCharge && Units.isLiquid(r.fuelType) && r.amount > cap * 1.05) {
                val unit = Units.amount(v, r.fuelType)
                out += Issue(v.id, r, Kind.OVER_TANK, str(R.string.check_over_tank, "${r.amount.fmt(2)} $unit", "${cap.fmt(0)} $unit"))
            }
            val before = list.getOrNull(i - 1)
            if (before != null && before.odometer == r.odometer && kotlin.math.abs(before.amount - r.amount) < 0.01 &&
                r.timestamp - before.timestamp < 10 * 60 * 1000
            ) {
                out += Issue(v.id, r, Kind.DUPLICATE, str(R.string.check_duplicate))
            }
            if (r.timestamp > now + 24L * 60 * 60 * 1000) out += Issue(v.id, r, Kind.FUTURE, str(R.string.check_future))
        }
        return out
    }

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("spritbuch", Context.MODE_PRIVATE)
    fun ignored(ctx: Context): Set<String> = sp(ctx).getStringSet("check_ignored", emptySet()) ?: emptySet()
    fun ignore(ctx: Context, issue: Issue) {
        sp(ctx).edit().putStringSet("check_ignored", ignored(ctx) + issue.key).apply()
    }

    /** Offene Auffälligkeiten (ohne die als „passt so“ markierten). */
    fun open(ctx: Context, v: Vehicle, refuels: List<Refuel>): List<Issue> {
        val ign = ignored(ctx)
        return check(v, refuels).filter { it.key !in ign }
    }
}

// ---------------------------------------------------------------------------------------------
// Sync-Protokoll und Absturzberichte
// ---------------------------------------------------------------------------------------------

object SyncLog {
    private const val MAX = 30
    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("spritbuch", Context.MODE_PRIVATE)

    fun entries(ctx: Context): List<String> =
        (sp(ctx).getString("sync_log", "") ?: "").split("\n").filter { it.isNotBlank() }

    fun add(ctx: Context, line: String) {
        val l = (listOf(line.replace("\n", " ")) + entries(ctx)).take(MAX)
        sp(ctx).edit().putString("sync_log", l.joinToString("\n")).apply()
    }

    fun clear(ctx: Context) = sp(ctx).edit().remove("sync_log").apply()
}

/** Abstürze werden immer (auch ohne Fehlerprotokoll) lokal festgehalten – nichts verlässt das Gerät ohne „Teilen“. */
object CrashLog {
    private const val MAX = 5
    private const val SEP = "\n=====\n"

    fun file(ctx: Context) = File(ctx.filesDir, "crashes.log")

    fun entries(ctx: Context): List<String> =
        file(ctx).takeIf { it.exists() }?.readText()?.split(SEP)?.filter { it.isNotBlank() } ?: emptyList()

    fun add(ctx: Context, text: String) {
        val l = (listOf(text) + entries(ctx)).take(MAX)
        file(ctx).writeText(l.joinToString(SEP))
    }

    fun clear(ctx: Context) {
        file(ctx).delete()
    }
}

// ---------------------------------------------------------------------------------------------
// Saisonzulassung
// ---------------------------------------------------------------------------------------------

object Registration {
    /** „03-10“ → (3, 10); leer oder ungültig → null (ganzjährig). */
    fun parse(s: String): Pair<Int, Int>? {
        val p = s.split("-").mapNotNull { it.trim().toIntOrNull() }
        return if (p.size == 2 && p.all { it in 1..12 }) p[0] to p[1] else null
    }

    fun format(from: Int, to: Int) = "%02d-%02d".format(from, to)

    /** Ist das Fahrzeug im Monat von [ts] zugelassen? Zeiträume über den Jahreswechsel (z. B. 10-03) gehen auch. */
    fun isActive(v: Vehicle, ts: Long = now()): Boolean {
        val (from, to) = parse(v.registration) ?: return true
        val m = Calendar.getInstance().apply { timeInMillis = ts }.get(Calendar.MONTH) + 1
        return if (from <= to) m in from..to else m >= from || m <= to
    }

    fun monthName(m: Int): String =
        java.text.DateFormatSymbols.getInstance(appLocale()).months[m - 1]

    /** Kurztext für die Übersicht, z. B. „Saisonkennzeichen März – Oktober · abgemeldet, wieder ab März“. */
    fun describe(v: Vehicle, ts: Long = now()): String? {
        val (from, to) = parse(v.registration) ?: return null
        val range = str(R.string.reg_overview, monthName(from), monthName(to))
        val state = if (isActive(v, ts)) str(R.string.reg_on, monthName(to)) else str(R.string.reg_off, monthName(from))
        return "$range · $state"
    }
}
