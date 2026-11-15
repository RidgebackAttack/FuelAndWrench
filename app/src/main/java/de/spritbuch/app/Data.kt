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
        "Super E5", "Super E10", "Super Plus", "Diesel", "Premium-Diesel",
        "HVO100", "LPG", "CNG", "Strom"
    )
    val brands = listOf(
        "Aral", "Shell", "Esso", "TotalEnergies", "JET", "Avia", "Agip/Eni", "OMV",
        "Star", "bft", "HEM", "Westfalen", "Raiffeisen", "Hoyer", "Q1", "Freie Tankstelle"
    )
    val extras = listOf("Standheizung", "Anhänger", "Dachbox/Fahrradträger", "Klimaanlage")
    val drivingStyles = listOf("Sparsam", "Normal", "Zügig", "Sportlich")
    val routeTypes = listOf("Stadt", "Landstraße", "Autobahn", "Gemischt")
    val tireTypes = listOf("Sommerreifen", "Winterreifen", "Ganzjahresreifen")
    val vehicleTypes = listOf("Auto", "Motorrad")
    fun tirePositions(vehicleType: String?) =
        if (vehicleType == "Motorrad") listOf("V" to "Vorne", "H" to "Hinten")
        else listOf("VL" to "Vorne links", "VR" to "Vorne rechts", "HL" to "Hinten links", "HR" to "Hinten rechts")
    val tireBrands = listOf(
        "Michelin", "Continental", "Bridgestone", "Pirelli", "Goodyear", "Dunlop", "Metzeler",
        "Hankook", "Nokian", "Vredestein", "Falken", "Kumho", "Toyo", "Yokohama", "Uniroyal", "Firestone",
    )
    val serviceCategories = listOf("Wartung", "Reparatur", "Reifen", "HU/AU", "Pflege", "Zubehör", "Sonstiges")
    val serviceSuggestions = listOf(
        "Ölwechsel", "Reifenwechsel", "Profiltiefe gemessen", "Inspektion", "HU/AU", "Reifenwechsel", "Bremsen", "Zahnriemen", "Bremsflüssigkeit",
        "Luftfilter", "Innenraumfilter", "Batterie", "Wischerblätter", "Kette/Ritzel",
    )
    val reminderSuggestions = listOf(
        "Ölwechsel", "Inspektion", "Zahnriemen", "Bremsflüssigkeit", "Luftfilter",
        "Reifen tauschen", "Zündkerzen"
    )

    fun unit(fuelType: String): String = when (fuelType) {
        "Strom" -> "kWh"
        "CNG" -> "kg"
        else -> "l"
    }
}

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
)

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
    val updatedAt: Long = now(),
    val deleted: Boolean = false,
) {
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
) {
    val dueKm: Int get() = lastDoneKm + intervalKm
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
    val updatedAt: Long = now(),
    val deleted: Boolean = false,
) {
    fun tireSummary(): String = listOf(
        tireSeason,
        listOf(tireBrand, tireModel).filter { it.isNotBlank() }.joinToString(" "),
        tireSize,
    ).filter { it.isNotBlank() }.joinToString(" · ")
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

    @Query("SELECT * FROM reminders WHERE vehicleId = :vehicleId")
    suspend fun forVehicleRaw(vehicleId: String): List<Reminder>

    @Upsert
    suspend fun upsert(item: Reminder)
}

@Database(entities = [Vehicle::class, Refuel::class, Reminder::class, ServiceEntry::class], version = 5, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun vehicles(): VehicleDao
    abstract fun refuels(): RefuelDao
    abstract fun reminders(): ReminderDao
    abstract fun services(): ServiceDao

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

        fun create(ctx: Context): AppDatabase =
            Room.databaseBuilder(ctx.applicationContext, AppDatabase::class.java, "spritbuch.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()
    }
}
