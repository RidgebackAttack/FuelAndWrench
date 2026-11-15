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

@Database(entities = [Vehicle::class, Refuel::class, Reminder::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun vehicles(): VehicleDao
    abstract fun refuels(): RefuelDao
    abstract fun reminders(): ReminderDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE vehicles ADD COLUMN distanceUnit TEXT NOT NULL DEFAULT 'km'")
                db.execSQL("ALTER TABLE vehicles ADD COLUMN volumeUnit TEXT NOT NULL DEFAULT 'l'")
                db.execSQL("ALTER TABLE vehicles ADD COLUMN consumptionFormat TEXT NOT NULL DEFAULT 'per100'")
                db.execSQL("ALTER TABLE vehicles ADD COLUMN currency TEXT NOT NULL DEFAULT '€'")
            }
        }

        fun create(ctx: Context): AppDatabase =
            Room.databaseBuilder(ctx.applicationContext, AppDatabase::class.java, "spritbuch.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
