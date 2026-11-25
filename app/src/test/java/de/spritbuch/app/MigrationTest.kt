package de.spritbuch.app

import android.app.Application
import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Prüft jedes Datenbank-Update: Die Tabellen werden exakt so angelegt, wie sie in der jeweils
 * veröffentlichten Version aussahen (aus den Release-APKs entnommen, test/resources/schemas),
 * mit Beispieldaten gefüllt und dann mit der aktuellen App geöffnet. Room bricht ab, wenn das
 * Ergebnis nicht zum aktuellen Datenmodell passt; die Beispieldaten müssen erhalten bleiben.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MigrationTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val current = 16

    private fun schema(version: Int): List<String> =
        javaClass.classLoader!!.getResource("schemas/db_$version.sql")!!.readText()
            .split(";\n").map { it.trim() }.filter { it.isNotEmpty() }

    /** Füllt alle Pflichtspalten passend zum Typ. */
    private fun insertRow(db: SupportSQLiteDatabase, table: String, values: Map<String, Any>) {
        val cols = mutableListOf<Pair<String, Any>>()
        db.query("PRAGMA table_info(`$table`)").use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1)
                val type = c.getString(2)
                val notNull = c.getInt(3) == 1
                val v: Any? = values[name] ?: if (!notNull) null else when (type) {
                    "INTEGER" -> 1
                    "REAL" -> 1.5
                    else -> "x"
                }
                if (v != null) cols += name to v
            }
        }
        val sql = "INSERT INTO `$table` (${cols.joinToString { "`${it.first}`" }}) VALUES (${cols.joinToString { "?" }})"
        db.execSQL(sql, cols.map { it.second }.toTypedArray())
    }

    private fun migrateFrom(version: Int) {
        val name = "migration_$version.db"
        ctx.deleteDatabase(name)
        val tables = schema(version).filter { it.startsWith("CREATE TABLE") }.map { it.split("`")[1] }
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(ctx).name(name).callback(
                object : SupportSQLiteOpenHelper.Callback(version) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        schema(version).forEach { db.execSQL(it) }
                        insertRow(db, "vehicles", mapOf("id" to "v1", "name" to "BMW", "deleted" to 0, "archived" to 0))
                        insertRow(db, "refuels", mapOf("id" to "r1", "vehicleId" to "v1", "odometer" to 1000, "amount" to 50.0, "deleted" to 0))
                        insertRow(db, "reminders", mapOf("id" to "m1", "vehicleId" to "v1", "deleted" to 0))
                        if ("services" in tables) insertRow(db, "services", mapOf("id" to "s1", "vehicleId" to "v1", "deleted" to 0))
                        if ("tire_sets" in tables) insertRow(db, "tire_sets", mapOf("id" to "t1", "vehicleId" to "v1", "deleted" to 0))
                        if ("expenses" in tables) insertRow(db, "expenses", mapOf("id" to "e1", "vehicleId" to "v1", "deleted" to 0))
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                },
            ).build(),
        )
        helper.writableDatabase.close()
        helper.close()

        val db = AppDatabase.create(ctx, name)
        try {
            runBlocking {
                assertEquals("Fahrzeug nach Update von v$version", listOf("BMW"), db.vehicles().getAllRaw().map { it.name })
                val r = db.refuels().getAllRaw().single()
                assertEquals(1000, r.odometer)
                assertEquals(50.0, r.amount, 0.001)
                assertEquals(1, db.reminders().getAllRaw().size)
                if ("services" in tables) assertEquals(1, db.services().getAllRaw().size)
                if ("tire_sets" in tables) assertEquals(1, db.tireSets().getAllRaw().size)
                if ("expenses" in tables) assertEquals(1, db.expenses().getAllRaw().size)
            }
        } finally {
            db.close()
            ctx.deleteDatabase(name)
        }
    }

    @Test fun from1() = migrateFrom(1)
    @Test fun from2() = migrateFrom(2)
    @Test fun from3() = migrateFrom(3)
    @Test fun from4() = migrateFrom(4)
    @Test fun from5() = migrateFrom(5)
    @Test fun from6() = migrateFrom(6)
    @Test fun from7() = migrateFrom(7)
    @Test fun from8() = migrateFrom(8)
    @Test fun from9() = migrateFrom(9)
    @Test fun from10() = migrateFrom(10)
    @Test fun from11() = migrateFrom(11)
    @Test fun from12() = migrateFrom(12)
    @Test fun from13() = migrateFrom(13)
    @Test fun from14() = migrateFrom(14)
    @Test fun from15() = migrateFrom(15)

    /** Das aktuelle Schema muss exakt zum Datenmodell passen (Grundlage für das nächste Update). */
    @Test fun currentSchemaMatches() = migrateFrom(current)
}
