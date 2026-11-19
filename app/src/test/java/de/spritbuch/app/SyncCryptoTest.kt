package de.spritbuch.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncCryptoTest {
    private val salt = SyncCrypto.newSalt()

    @Test fun roundTrip() {
        val key = SyncCrypto.derive("geheimes-passwort", salt)
        val plain = """{"vehicles":[{"name":"BMW"}]}""".toByteArray()
        val enc = SyncCrypto.encrypt(key, plain)
        assertTrue(SyncCrypto.isEncrypted(enc))
        assertFalse(String(enc, Charsets.ISO_8859_1).contains("BMW"))
        assertArrayEquals(plain, SyncCrypto.decrypt(key, enc))
    }

    @Test fun plainJsonIsNotEncrypted() {
        assertFalse(SyncCrypto.isEncrypted("""{"vehicles":[]}""".toByteArray()))
    }

    @Test(expected = SyncCrypto.WrongPassword::class)
    fun wrongPasswordIsDetected() {
        val enc = SyncCrypto.encrypt(SyncCrypto.derive("richtig-richtig", salt), "x".toByteArray())
        SyncCrypto.decrypt(SyncCrypto.derive("falsch-falsch", salt), enc)
    }

    @Test(expected = SyncCrypto.WrongPassword::class)
    fun tamperingIsDetected() {
        val key = SyncCrypto.derive("geheimes-passwort", salt)
        val enc = SyncCrypto.encrypt(key, "Kilometerstand 123456".toByteArray())
        enc[enc.size - 1] = (enc[enc.size - 1].toInt() xor 1).toByte()
        SyncCrypto.decrypt(key, enc)
    }

    @Test fun keyFileRoundTrip() {
        val back = SyncCrypto.saltFrom(SyncCrypto.keyFile(salt))
        assertNotNull(back)
        assertArrayEquals(salt, back)
    }
}
