package de.spritbuch.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** WebDAV-Server im Speicher: hält Dateien und ETags wie eine Nextcloud. */
class MemoryDav : Dav {
    val files = mutableMapOf<String, ByteArray>()
    private val etags = mutableMapOf<String, String>()
    private var counter = 0

    override fun get(path: String) =
        files[path]?.let { WebDavClient.Result(200, it, etags[path]) } ?: WebDavClient.Result(404, null, null)

    override fun head(path: String) = if (path in files) 200 else 404

    override fun delete(path: String): Int = if (files.remove(path) != null) 204 else 404

    override fun put(path: String, data: ByteArray, type: String, ifMatch: String?, ifNoneMatch: Boolean): Int {
        if (ifNoneMatch && path in files) return 412
        if (ifMatch != null && etags[path] != ifMatch) return 412
        files[path] = data
        etags[path] = "\"${++counter}\""
        return 201
    }

    override fun ensureDir(path: String) = Unit

    fun text(path: String) = files[path]?.toString(Charsets.ISO_8859_1) ?: ""
}

/**
 * Ende-zu-Ende-Verschlüsselung im Zusammenspiel: einschalten, auf einem „zweiten Gerät“ mit demselben
 * Passwort wiederherstellen, falsches Passwort, ausschalten.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncEncryptionTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val server = MemoryDav()
    private val prefs = Prefs(ctx)
    private val db get() = ctx.repo.db

    @Before fun setUp() {
        SyncManager.connect = { server }
        prefs.url = "https://cloud.example/dav"
        prefs.user = "test"
        prefs.password = "app-passwort"
        runBlocking {
            db.vehicles().upsert(Vehicle(id = "v1", name = "BMW", initialOdometer = 1000))
            db.refuels().upsert(Refuel(id = "r1", vehicleId = "v1", odometer = 1500, amount = 40.0, totalPrice = 70.0))
        }
    }

    @After fun tearDown() {
        SyncManager.connect = { WebDavClient(it.url, it.user, it.password) }
    }

    private fun sync() = runBlocking { SyncManager.sync(ctx) }

    /** Wie ein frisch eingerichtetes zweites Gerät: keine Daten, keine Einstellungen zur Verschlüsselung. */
    private fun newDevice(passphrase: String) {
        kotlin.concurrent.thread { db.clearAllTables() }.join()
        prefs.syncEncrypt = false
        prefs.syncEncryptChanged = false
        prefs.syncPassphrase = passphrase
    }

    @Test fun plainSyncStaysReadable() {
        assertTrue(sync().ok)
        assertTrue(server.text("spritbuch.json").contains("BMW"))
        assertNull(server.files[SyncCrypto.KEY_FILE])
    }

    @Test fun encryptedOnServerAndRestoredOnSecondDevice() {
        prefs.syncPassphrase = "geheim-geheim"
        prefs.syncEncrypt = true
        prefs.syncEncryptChanged = true
        val first = sync()
        assertTrue(first.message, first.ok)
        assertNotNull(server.files[SyncCrypto.KEY_FILE])
        assertTrue(SyncCrypto.isEncrypted(server.files["spritbuch.json"]!!))
        assertFalse("Klartext auf dem Server", server.text("spritbuch.json").contains("BMW"))

        newDevice("geheim-geheim")
        val second = sync()
        assertTrue(second.message, second.ok)
        runBlocking {
            assertEquals(listOf("BMW"), db.vehicles().getAllRaw().map { it.name })
            assertEquals(1500, db.refuels().getAllRaw().single().odometer)
        }
        assertTrue("zweites Gerät übernimmt die Verschlüsselung", prefs.syncEncrypt)
    }

    @Test fun wrongPasswordChangesNothing() {
        prefs.syncPassphrase = "geheim-geheim"
        prefs.syncEncrypt = true
        prefs.syncEncryptChanged = true
        assertTrue(sync().ok)
        val before = server.files["spritbuch.json"]!!.copyOf()

        newDevice("falsches-passwort")
        val r = sync()
        assertFalse(r.ok)
        assertEquals(str(R.string.e2e_wrong_password), r.message)
        assertTrue("Serverdaten unverändert", before.contentEquals(server.files["spritbuch.json"]!!))
    }

    @Test fun missingPasswordIsReported() {
        prefs.syncPassphrase = "geheim-geheim"
        prefs.syncEncrypt = true
        prefs.syncEncryptChanged = true
        assertTrue(sync().ok)

        newDevice("")
        val r = sync()
        assertFalse(r.ok)
        assertTrue(r.message, r.message.contains(str(R.string.e2e_need_password)))
    }

    @Test fun switchingOffWritesPlainTextAndRemovesKey() {
        prefs.syncPassphrase = "geheim-geheim"
        prefs.syncEncrypt = true
        prefs.syncEncryptChanged = true
        assertTrue(sync().ok)

        prefs.syncEncrypt = false
        prefs.syncEncryptChanged = true
        val r = sync()
        assertTrue(r.message, r.ok)
        assertTrue(server.text("spritbuch.json").contains("BMW"))
        assertNull(server.files[SyncCrypto.KEY_FILE])
        assertFalse(prefs.syncEncryptChanged)
    }
}
