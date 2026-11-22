package de.spritbuch.app

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()
fun now(): Long = System.currentTimeMillis()

/** Auswahllisten für die Eingabemasken. */
object Options {
    val fuelTypes = listOf(
        "Super E5", "Super E10", "Super Plus", "Premium", "Diesel", "Premium-Diesel",
        "HVO100", "LPG", "CNG", "Strom"
    )
    /** „Premium“ = Markenkraftstoffe mit 100+ Oktan wie Shell V-Power oder Aral Ultimate. */
    private val petrol = listOf("Super E5", "Super E10", "Super Plus", "Premium")
    private val diesel = listOf("Diesel", "Premium-Diesel", "HVO100")

    /**
     * Sinnvolle Kraftstoffe für ein Fahrzeug: Benziner nur Benzinsorten, Diesel nur Dieselsorten,
     * Gasfahrzeuge (bivalent) Gas plus Benzin, Elektro nur Strom. Der aktuelle Wert bleibt immer wählbar.
     */
    fun fuelsFor(v: Vehicle?, current: String = ""): List<String> {
        val d = v?.defaultFuelType ?: return fuelTypes
        val list = when {
            v.isElectric -> listOf(ELECTRIC)
            d in diesel -> diesel
            d == "LPG" -> listOf("LPG") + petrol
            d == "CNG" -> listOf("CNG") + petrol
            d in petrol -> petrol
            else -> fuelTypes.filter { it != ELECTRIC }
        }
        return if (current.isNotBlank() && current !in list && current != ELECTRIC) list + current else list
    }
    val brands = listOf(
        "Agip/Eni", "Aral", "Avia", "bft", "BP", "Calpam", "Circle K", "Classic", "ED", "Elan", "Esso",
        "F24", "famila", "Globus", "HEM", "Hoyer", "Ingo", "JET", "Kaufland", "Marktkauf", "Mundorf",
        "Nordoel", "OIL!", "OK", "OMV", "Orlen", "Pinoil", "Q1", "Q8", "Raiffeisen", "Score", "Shell",
        "Sprint", "Star", "Tamoil", "Team", "TotalEnergies", "Uno-X", "Westfalen", "Freie Tankstelle",
    )
    private val brandAliases = mapOf(
        "TotalEnergies" to listOf("total"),
        "OIL!" to listOf("oil"),
        "Star" to listOf("orlen star"),
        "Freie Tankstelle" to listOf("freie"),
    )

    /** Ordnet einen frei geschriebenen Betreibernamen (OSM, CSV) einer bekannten Marke zu – nur ganze Wörter. */
    fun matchBrand(raw: String): String {
        val b = raw.trim().trim('"')
        if (b.isEmpty()) return ""
        val low = b.lowercase()
        fun hit(alias: String) = Regex("(^|[^\\p{L}\\p{N}])" + Regex.escape(alias.lowercase()) + "($|[^\\p{L}\\p{N}])").containsMatchIn(low)
        // Exakte Treffer zuerst, damit z. B. "Orlen" nicht als "Star" erkannt wird
        brands.firstOrNull { it.equals(b, ignoreCase = true) }?.let { return it }
        return brands.firstOrNull { o -> (o.split("/") + brandAliases[o].orEmpty()).any { hit(it) } } ?: b
    }
    val extras = listOf("Standheizung", "Anhänger", "Dachbox/Fahrradträger", "Klimaanlage")
    val drivingStyles = listOf("Sparsam", "Normal", "Zügig", "Sportlich")
    val routeTypes = listOf("Stadt", "Landstraße", "Autobahn", "Gemischt")
    val tireTypes = listOf("Sommerreifen", "Winterreifen", "Ganzjahresreifen")
    val vehicleTypes = listOf("Auto", "Motorrad")
    fun tirePositions(vehicleType: String?) =
        (if (vehicleType == "Motorrad") listOf("V", "H") else listOf("VL", "VR", "HL", "HR")).map { it to lbl(it) }
    val tireBrands = listOf(
        "Michelin", "Continental", "Bridgestone", "Pirelli", "Goodyear", "Dunlop", "Metzeler",
        "Hankook", "Nokian", "Vredestein", "Falken", "Kumho", "Toyo", "Yokohama", "Uniroyal", "Firestone",
    )
    val expenseCategories = listOf("Versicherung", "Steuer", "Parken", "Maut", "Wäsche", "Finanzierung", "Gebühren", "Sonstiges")
    val driveTypes = listOf("Verbrenner", "Hybrid", "Plug-in-Hybrid", "Elektro")
    val chargeLocations = listOf("Zuhause", "AC", "DC")
    val chargeNetworks = listOf(
        "Zuhause", "EnBW", "Ionity", "Tesla Supercharger", "Aral pulse", "Shell Recharge", "EWE Go", "E.ON Drive",
        "Allego", "Fastned", "Maingau", "Clever", "Circle K", "Arbeitgeber",
    )
    val serviceCategories = listOf("Wartung", "Reparatur", "Reifen", "HU/AU", "Pflege", "Zubehör", "Sonstiges")
    val serviceSuggestions get() = strList(R.string.list_service_suggestions)
    val reminderSuggestions get() = strList(R.string.list_reminder_suggestions)

    fun unit(fuelType: String): String = when (fuelType) {
        "Strom" -> "kWh"
        "CNG" -> "kg"
        else -> "l"
    }
}

/** Schlüssel der Energieart „Strom“ (Laden statt Tanken). */
const val ELECTRIC = "Strom"

@Serializable
@Entity(tableName = "vehicles")
data class Vehicle(
    @PrimaryKey val id: String = newId(),
    val name: String = "",
    val plate: String = "",
    val defaultFuelType: String = "Super E10",
    val tankCapacity: Double? = null,
    val initialOdometer: Int = 0,
    val manualOdometer: Int = 0,
    val photoVersion: Long = 0,
    val archived: Boolean = false,
    @ColumnInfo(defaultValue = "km") val distanceUnit: String = "km",
    @ColumnInfo(defaultValue = "l") val volumeUnit: String = "l",
    @ColumnInfo(defaultValue = "per100") val consumptionFormat: String = "per100",
    @ColumnInfo(defaultValue = "€") val currency: String = "€",
    /** Bildausschnitt "x,y,zoom" für Miniatur bzw. große Ansicht. */
    @ColumnInfo(defaultValue = "0,0,1") val photoThumbPos: String = "0,0,1",
    @ColumnInfo(defaultValue = "0,0,1") val photoLargePos: String = "0,0,1",
    /** "Auto" (4 Räder) oder "Motorrad" (2 Räder) – u. a. für die Profiltiefe. */
    @ColumnInfo(defaultValue = "Auto") val vehicleType: String = "Auto",
    val updatedAt: Long = now(),
    val deleted: Boolean = false,
    /** Antrieb: "Verbrenner", "Hybrid" (ohne Stecker), "Plug-in-Hybrid" oder "Elektro". */
    @ColumnInfo(defaultValue = "Verbrenner") val driveType: String = "Verbrenner",
    /** Nutzbare Akkukapazität in kWh (Elektro/Plug-in-Hybrid), für den Verbrauch ab Akku. */
    val batteryCapacity: Double? = null,
    /** Weitere Bilder: „Version|Ausschnitt klein|Ausschnitt groß“, getrennt durch „;“ (das erste Bild steht in photoVersion). */
    val extraPhotos: String = "",
    /** Reihenfolge der Bilder beim Öffnen: „random“ (zufällig) oder „sequence“ (nacheinander). */
    val photoOrder: String = "random",
    /** Fahrzeugdokumente (Fahrzeugschein, Versicherungskarte, TÜV-Berichte …) als JSON, siehe [VehicleDoc]. */
    val documents: String = "",
) {
    /** Alle Bilder mit ihren festgelegten Ausschnitten (erstes = Hauptbild). */
    fun images(): List<VehicleImage> =
        (if (photoVersion > 0) listOf(VehicleImage(photoVersion, photoThumbPos, photoLargePos)) else emptyList()) +
            VehicleImage.parseList(extraPhotos)

    val isElectric: Boolean get() = driveType == "Elektro" || (driveType == "Verbrenner" && defaultFuelType == "Strom")
    val isPlugInHybrid: Boolean get() = driveType == "Plug-in-Hybrid"
    /** Fahrzeug wird (auch) geladen. */
    val charges: Boolean get() = isElectric || isPlugInHybrid
}

@Serializable
@Entity(tableName = "refuels", indices = [Index("vehicleId")])
data class Refuel(
    @PrimaryKey val id: String = newId(),
    val vehicleId: String = "",
    val timestamp: Long = now(),
    val odometer: Int = 0,
    val amount: Double = 0.0,
    val totalPrice: Double = 0.0,
    val fullTank: Boolean = true,
    val missedPrevious: Boolean = false,
    val fuelType: String = "",
    val stationBrand: String = "",
    val stationAddress: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val extras: String = "",
    val drivingStyle: String = "",
    val routeType: String = "",
    val tireType: String = "",
    val note: String = "",
    /** Vom Bordcomputer angezeigter Verbrauch, normiert wie computeStats: Menge je 100 Strecke (Fahrzeugeinheiten). */
    val obcConsumption: Double? = null,
    val updatedAt: Long = now(),
    val deleted: Boolean = false,
    /** Nur beim Laden: "Zuhause", "AC" (öffentlich) oder "DC" (Schnelllader). */
    @ColumnInfo(defaultValue = "") val chargeLocation: String = "",
    /** Akkustand in Prozent vor bzw. nach dem Laden (optional). */
    val socStart: Int? = null,
    val socEnd: Int? = null,
) {
    val isCharge: Boolean get() = fuelType == ELECTRIC

    val pricePerUnit: Double get() = if (amount > 0) totalPrice / amount else 0.0
    fun extrasList(): List<String> = extras.split("|").filter { it.isNotBlank() }
}

@Serializable
@Entity(tableName = "reminders", indices = [Index("vehicleId")])
data class Reminder(
    @PrimaryKey val id: String = newId(),
    val vehicleId: String = "",
    val title: String = "",
    val intervalKm: Int = 15000,
    val lastDoneKm: Int = 0,
    val warnBeforeKm: Int = 500,
    val updatedAt: Long = now(),
    val deleted: Boolean = false,
    /** Zeitintervall in Monaten (0 = nur nach Kilometern). Fällig ist, was zuerst eintritt. */
    @ColumnInfo(defaultValue = "0") val intervalMonths: Int = 0,
    /** Datum der letzten Erledigung (0 = unbekannt). */
    @ColumnInfo(defaultValue = "0") val lastDoneDate: Long = 0,
) {
    val dueKm: Int get() = lastDoneKm + intervalKm
    val byKm: Boolean get() = intervalKm > 0
    val byDate: Boolean get() = intervalMonths > 0 && lastDoneDate > 0

    /** Fälligkeitsdatum (Mitternacht des Tages) oder null. */
    val dueDate: Long?
        get() = if (!byDate) null else java.util.Calendar.getInstance().apply {
            timeInMillis = lastDoneDate
            add(java.util.Calendar.MONTH, intervalMonths)
        }.timeInMillis

    /** Zustand bezogen auf Kilometerstand und Datum. */
    fun state(odometer: Int, at: Long = now()): ReminderState {
        val remKm = if (byKm) dueKm - odometer else null
        val due = dueDate
        val remDays = due?.let { Math.floorDiv(it - at, 86_400_000L).toInt() }
        val overdue = (remKm != null && remKm <= 0) || (remDays != null && remDays < 0)
        val warn = !overdue && ((remKm != null && remKm <= warnBeforeKm) || (remDays != null && remDays <= WARN_DAYS))
        return ReminderState(remKm, remDays, overdue, warn)
    }

    companion object { const val WARN_DAYS = 30 }
}

data class ReminderState(val remainingKm: Int?, val remainingDays: Int?, val overdue: Boolean, val warn: Boolean) {
    /** Sortierwert: je kleiner, desto dringender (km und Tage grob vergleichbar gemacht). */
    val urgency: Double get() = minOf(remainingKm?.toDouble() ?: Double.MAX_VALUE, remainingDays?.let { it * 50.0 } ?: Double.MAX_VALUE)
}

@Serializable
@Entity(tableName = "services", indices = [Index("vehicleId")])
data class ServiceEntry(
    @PrimaryKey val id: String = newId(),
    val vehicleId: String = "",
    val timestamp: Long = now(),
    val odometer: Int = 0,
    val title: String = "",
    val category: String = "Wartung",
    val cost: Double = 0.0,
    val workshop: String = "",
    val note: String = "",
    /** Profiltiefe in mm je Position, Format "VL:alt:neu;VR:alt:neu;…" (leere Werte erlaubt). */
    @ColumnInfo(defaultValue = "") val treadDepth: String = "",
    // Reifen (nur bei Kategorie „Reifen“)
    @ColumnInfo(defaultValue = "") val tireSeason: String = "",
    @ColumnInfo(defaultValue = "") val tireBrand: String = "",
    @ColumnInfo(defaultValue = "") val tireModel: String = "",
    @ColumnInfo(defaultValue = "") val tireSize: String = "",
    /** Radwechsel: montierter bzw. abmontierter (eingelagerter) Radsatz. */
    @ColumnInfo(defaultValue = "") val tireSetId: String = "",
    @ColumnInfo(defaultValue = "") val removedTireSetId: String = "",
    val updatedAt: Long = now(),
    val deleted: Boolean = false,
    /** Belege: Dateinamen im Ordner „attachments“, mit „|“ getrennt. */
    @ColumnInfo(defaultValue = "") val attachments: String = "",
) {
    fun attachmentList(): List<String> = attachments.split("|").filter { it.isNotBlank() }

    fun tireSummary(): String = listOf(
        lbl(tireSeason),
        listOf(tireBrand, tireModel).filter { it.isNotBlank() }.joinToString(" "),
        tireSize,
    ).filter { it.isNotBlank() }.joinToString(" · ")
}

/** Ein Radsatz (z. B. Sommer- oder Winterräder), der im Wechsel montiert und eingelagert wird. */
@Serializable
@Entity(tableName = "tire_sets", indices = [Index("vehicleId")])
data class TireSet(
    @PrimaryKey val id: String = newId(),
    val vehicleId: String = "",
    val season: String = "",
    /** Angaben je Achse – Hersteller, Reifenname und Größe können vorne und hinten verschieden sein. */
    val brandFront: String = "",
    val modelFront: String = "",
    val sizeFront: String = "",
    val brandRear: String = "",
    val modelRear: String = "",
    val sizeRear: String = "",
    val note: String = "",
    val updatedAt: Long = now(),
    val deleted: Boolean = false,
) {
    private fun tire(b: String, m: String) = listOf(b, m).filter { it.isNotBlank() }.joinToString(" ")
    val frontTire: String get() = tire(brandFront, modelFront)
    val rearTire: String get() = tire(brandRear, modelRear)
    val sameAxles: Boolean get() = frontTire == rearTire && sizeFront == sizeRear

    /** Kurzbezeichnung, z. B. „Winterreifen – Michelin Pilot Alpin 5“. */
    fun label(): String {
        val tires = if (rearTire.isBlank() || rearTire == frontTire) frontTire else "$frontTire / $rearTire"
        return listOf(lbl(season), tires).filter { it.isNotBlank() }.joinToString(" – ").ifBlank { str(R.string.tire_set) }
    }

    /** Größen, z. B. „245/40 R19“ oder „v 245/40 R19 · h 275/35 R19“. */
    fun sizes(): String = when {
        sizeFront.isBlank() && sizeRear.isBlank() -> ""
        sizeRear.isBlank() || sizeRear == sizeFront -> sizeFront
        else -> str(R.string.sizes_front_rear, sizeFront, sizeRear)
    }
}

/** Sonstige Kosten (Versicherung, Steuer, Parken …). */
@Serializable
@Entity(tableName = "expenses", indices = [Index("vehicleId")])
data class Expense(
    @PrimaryKey val id: String = newId(),
    val vehicleId: String = "",
    val timestamp: Long = now(),
    val category: String = "Sonstiges",
    val title: String = "",
    val amount: Double = 0.0,
    val note: String = "",
    val attachments: String = "",
    val updatedAt: Long = now(),
    val deleted: Boolean = false,
) {
    fun attachmentList(): List<String> = attachments.split("|").filter { it.isNotBlank() }
}

@Dao
interface ExpenseDao {
    @Query("SELECT * FROM expenses WHERE deleted = 0 AND vehicleId = :vehicleId ORDER BY timestamp DESC")
    fun observeForVehicle(vehicleId: String): Flow<List<Expense>>

    @Query("SELECT * FROM expenses WHERE id = :id")
    suspend fun get(id: String): Expense?

    @Query("SELECT * FROM expenses WHERE deleted = 0 AND vehicleId = :vehicleId")
    suspend fun forVehicle(vehicleId: String): List<Expense>

    @Query("SELECT * FROM expenses")
    suspend fun getAllRaw(): List<Expense>

    @Upsert
    suspend fun upsert(item: Expense)
}

@Dao
interface TireSetDao {
    @Query("SELECT * FROM tire_sets WHERE deleted = 0 AND vehicleId = :vehicleId ORDER BY season, brandFront")
    fun observeForVehicle(vehicleId: String): Flow<List<TireSet>>

    @Query("SELECT * FROM tire_sets WHERE deleted = 0 AND vehicleId = :vehicleId ORDER BY season, brandFront")
    suspend fun forVehicle(vehicleId: String): List<TireSet>

    @Query("SELECT * FROM tire_sets WHERE id = :id")
    suspend fun get(id: String): TireSet?

    @Query("SELECT * FROM tire_sets")
    suspend fun getAllRaw(): List<TireSet>

    @Upsert
    suspend fun upsert(item: TireSet)
}

@Dao
interface ServiceDao {
    @Query("SELECT * FROM services WHERE deleted = 0 AND vehicleId = :vehicleId ORDER BY timestamp DESC, odometer DESC")
    fun observeForVehicle(vehicleId: String): Flow<List<ServiceEntry>>

    @Query("SELECT * FROM services WHERE id = :id")
    suspend fun get(id: String): ServiceEntry?

    @Query("SELECT * FROM services WHERE deleted = 0 AND vehicleId = :vehicleId")
    suspend fun forVehicle(vehicleId: String): List<ServiceEntry>

    @Query("SELECT * FROM services")
    suspend fun getAllRaw(): List<ServiceEntry>

    @Query("SELECT * FROM services WHERE deleted = 0 AND vehicleId = :vehicleId AND tireSeason != '' ORDER BY timestamp DESC LIMIT 1")
    suspend fun lastTireChange(vehicleId: String): ServiceEntry?

    @Query("SELECT * FROM services WHERE deleted = 0 AND vehicleId = :vehicleId AND category = 'Reifen' ORDER BY timestamp DESC")
    fun observeTireHistory(vehicleId: String): Flow<List<ServiceEntry>>

    @Query("SELECT * FROM services WHERE deleted = 0 AND vehicleId = :vehicleId AND tireSetId != '' ORDER BY timestamp DESC LIMIT 1")
    suspend fun lastWheelChange(vehicleId: String): ServiceEntry?

    @Upsert
    suspend fun upsert(item: ServiceEntry)
}

@Dao
interface VehicleDao {
    @Query("SELECT * FROM vehicles WHERE deleted = 0 ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<Vehicle>>

    @Query("SELECT * FROM vehicles WHERE id = :id")
    fun observe(id: String): Flow<Vehicle?>

    @Query("SELECT * FROM vehicles WHERE id = :id")
    suspend fun get(id: String): Vehicle?

    @Query("SELECT * FROM vehicles WHERE deleted = 0")
    suspend fun getAll(): List<Vehicle>

    @Query("SELECT * FROM vehicles")
    suspend fun getAllRaw(): List<Vehicle>

    @Upsert
    suspend fun upsert(item: Vehicle)
}

@Dao
interface RefuelDao {
    @Query("SELECT * FROM refuels WHERE deleted = 0 ORDER BY odometer DESC, timestamp DESC")
    fun observeAll(): Flow<List<Refuel>>

    @Query("SELECT * FROM refuels WHERE deleted = 0 AND vehicleId = :vehicleId ORDER BY odometer DESC, timestamp DESC")
    fun observeForVehicle(vehicleId: String): Flow<List<Refuel>>

    @Query("SELECT * FROM refuels WHERE id = :id")
    suspend fun get(id: String): Refuel?

    @Query("SELECT * FROM refuels WHERE deleted = 0 AND vehicleId = :vehicleId ORDER BY odometer DESC, timestamp DESC LIMIT 1")
    suspend fun last(vehicleId: String): Refuel?

    @Query("SELECT MAX(odometer) FROM refuels WHERE deleted = 0 AND vehicleId = :vehicleId")
    suspend fun maxOdometer(vehicleId: String): Int?

    @Query("SELECT * FROM refuels WHERE deleted = 0 AND vehicleId = :vehicleId")
    suspend fun forVehicle(vehicleId: String): List<Refuel>

    @Query("SELECT * FROM refuels")
    suspend fun getAllRaw(): List<Refuel>

    /** Eigene Betreiber, häufigste zuerst – ohne alle Betankungen zu laden. */
    @Query("SELECT stationBrand FROM refuels WHERE deleted = 0 AND stationBrand != '' GROUP BY stationBrand ORDER BY COUNT(*) DESC")
    suspend fun usedBrands(): List<String>

    /** Kraftstoffsorten eines Fahrzeugs mit Anzahl (z. B. um unbekannte Import-Codes zu finden). */
    @Query("SELECT fuelType AS name, COUNT(*) AS count FROM refuels WHERE deleted = 0 AND vehicleId = :vehicleId GROUP BY fuelType")
    suspend fun fuelTypeCounts(vehicleId: String): List<NameCount>

    @Query("UPDATE refuels SET fuelType = :to, updatedAt = :now WHERE vehicleId = :vehicleId AND fuelType = :from")
    suspend fun replaceFuelType(vehicleId: String, from: String, to: String, now: Long)

    @Query("SELECT vehicleId, MAX(timestamp) AS lastTime FROM refuels WHERE deleted = 0 GROUP BY vehicleId")
    suspend fun lastUse(): List<VehicleLastUse>

    @Upsert
    suspend fun upsert(item: Refuel)
}

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminders WHERE deleted = 0 AND vehicleId = :vehicleId ORDER BY title COLLATE NOCASE")
    fun observeForVehicle(vehicleId: String): Flow<List<Reminder>>

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun get(id: String): Reminder?

    @Query("SELECT * FROM reminders WHERE deleted = 0 AND vehicleId = :vehicleId")
    suspend fun forVehicle(vehicleId: String): List<Reminder>

    @Query("SELECT * FROM reminders")
    suspend fun getAllRaw(): List<Reminder>

    @Query("SELECT * FROM reminders WHERE deleted = 0")
    fun observeAll(): Flow<List<Reminder>>

    @Query("SELECT * FROM reminders WHERE vehicleId = :vehicleId")
    suspend fun forVehicleRaw(vehicleId: String): List<Reminder>

    @Upsert
    suspend fun upsert(item: Reminder)
}

data class NameCount(val name: String, val count: Int)

/** Letzte Betankung je Fahrzeug (für die Sortierung im App-Icon-Menü). */
data class VehicleLastUse(val vehicleId: String, val lastTime: Long)

/** Ein Fahrzeugbild mit festem Ausschnitt für Miniatur und große Ansicht. */
data class VehicleImage(val version: Long, val thumbPos: String = "0,0,1", val largePos: String = "0,0,1") {
    fun encode() = "$version|$thumbPos|$largePos"

    companion object {
        fun parse(s: String): VehicleImage? {
            val p = s.split('|')
            val v = p.getOrNull(0)?.toLongOrNull()?.takeIf { it > 0 } ?: return null
            return VehicleImage(v, p.getOrNull(1) ?: "0,0,1", p.getOrNull(2) ?: "0,0,1")
        }
        fun parseList(s: String): List<VehicleImage> = s.split(';').filter { it.isNotBlank() }.mapNotNull { parse(it) }
        fun encodeList(l: List<VehicleImage>) = l.joinToString(";") { it.encode() }
    }
}

@Database(entities = [Vehicle::class, Refuel::class, Reminder::class, ServiceEntry::class, TireSet::class, Expense::class], version = 11, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun vehicles(): VehicleDao
    abstract fun refuels(): RefuelDao
    abstract fun reminders(): ReminderDao
    abstract fun services(): ServiceDao
    abstract fun tireSets(): TireSetDao
    abstract fun expenses(): ExpenseDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE vehicles ADD COLUMN distanceUnit TEXT NOT NULL DEFAULT 'km'")
                db.execSQL("ALTER TABLE vehicles ADD COLUMN volumeUnit TEXT NOT NULL DEFAULT 'l'")
                db.execSQL("ALTER TABLE vehicles ADD COLUMN consumptionFormat TEXT NOT NULL DEFAULT 'per100'")
                db.execSQL("ALTER TABLE vehicles ADD COLUMN currency TEXT NOT NULL DEFAULT '€'")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE vehicles ADD COLUMN photoThumbPos TEXT NOT NULL DEFAULT '0,0,1'")
                db.execSQL("ALTER TABLE vehicles ADD COLUMN photoLargePos TEXT NOT NULL DEFAULT '0,0,1'")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `services` (`id` TEXT NOT NULL, `vehicleId` TEXT NOT NULL, " +
                        "`timestamp` INTEGER NOT NULL, `odometer` INTEGER NOT NULL, `title` TEXT NOT NULL, " +
                        "`category` TEXT NOT NULL, `cost` REAL NOT NULL, `workshop` TEXT NOT NULL, `note` TEXT NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_services_vehicleId` ON `services` (`vehicleId`)")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE vehicles ADD COLUMN vehicleType TEXT NOT NULL DEFAULT 'Auto'")
                db.execSQL("ALTER TABLE services ADD COLUMN treadDepth TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (c in listOf("tireSeason", "tireBrand", "tireModel", "tireSize")) {
                    db.execSQL("ALTER TABLE services ADD COLUMN $c TEXT NOT NULL DEFAULT ''")
                }
            }
        }

        /** 1.11: Fahrzeugdokumente. */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE vehicles ADD COLUMN documents TEXT NOT NULL DEFAULT ''")
            }
        }

        /** 1.9: mehrere Bilder pro Fahrzeug. */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE vehicles ADD COLUMN extraPhotos TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE vehicles ADD COLUMN photoOrder TEXT NOT NULL DEFAULT 'random'")
            }
        }
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE vehicles ADD COLUMN driveType TEXT NOT NULL DEFAULT 'Verbrenner'")
                db.execSQL("ALTER TABLE vehicles ADD COLUMN batteryCapacity REAL")
                db.execSQL("ALTER TABLE refuels ADD COLUMN chargeLocation TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE refuels ADD COLUMN socStart INTEGER")
                db.execSQL("ALTER TABLE refuels ADD COLUMN socEnd INTEGER")
            }
        }
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE reminders ADD COLUMN intervalMonths INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE reminders ADD COLUMN lastDoneDate INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE services ADD COLUMN attachments TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `expenses` (`id` TEXT NOT NULL, `vehicleId` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, " +
                        "`category` TEXT NOT NULL, `title` TEXT NOT NULL, `amount` REAL NOT NULL, `note` TEXT NOT NULL, " +
                        "`attachments` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_expenses_vehicleId` ON `expenses` (`vehicleId`)")
            }
        }
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE refuels ADD COLUMN obcConsumption REAL")
            }
        }
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE services ADD COLUMN tireSetId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE services ADD COLUMN removedTireSetId TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `tire_sets` (`id` TEXT NOT NULL, `vehicleId` TEXT NOT NULL, " +
                        "`season` TEXT NOT NULL, `brandFront` TEXT NOT NULL, `modelFront` TEXT NOT NULL, `sizeFront` TEXT NOT NULL, " +
                        "`brandRear` TEXT NOT NULL, `modelRear` TEXT NOT NULL, `sizeRear` TEXT NOT NULL, " +
                        "`note` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tire_sets_vehicleId` ON `tire_sets` (`vehicleId`)")
            }
        }

        /** Alle Datenbank-Updates; werden in MigrationTest mit den echten alten Schemas geprüft. */
        val MIGRATIONS: Array<Migration>
            get() = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11)

        fun create(ctx: Context, name: String = "spritbuch.db"): AppDatabase =
            Room.databaseBuilder(ctx.applicationContext, AppDatabase::class.java, name)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
