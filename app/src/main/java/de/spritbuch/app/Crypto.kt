package de.spritbuch.app

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Geheimnisse auf dem Gerät (Nextcloud-App-Passwort, Sync-Passwort): verschlüsselt mit einem Schlüssel
 * aus dem Android-Schlüsselspeicher. Der Schlüssel verlässt das Gerät nie und lässt sich nicht auslesen.
 */
object Secrets {
    private const val ALIAS = "fw_secrets"
    private const val PREFIX = "ks1:"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    /** Leerer Text bleibt leer. Klappt der Schlüsselspeicher nicht (sehr selten), bleibt der Wert lesbar gespeichert. */
    fun seal(plain: String): String {
        if (plain.isEmpty()) return ""
        return runCatching {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key())
            PREFIX + Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
        }.getOrElse {
            DebugLog.error("Schlüsselspeicher nicht verfügbar", it)
            plain
        }
    }

    fun open(stored: String): String {
        if (!stored.startsWith(PREFIX)) return stored
        return runCatching {
            val all = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all, 0, 12))
            String(c.doFinal(all, 12, all.size - 12))
        }.getOrElse {
            DebugLog.error("Gespeichertes Passwort nicht lesbar", it)
            ""
        }
    }
}

/**
 * Ende-zu-Ende-Verschlüsselung für den Sync: Alles, was auf den Server geht (Daten, Fotos, Belege),
 * wird auf dem Gerät mit AES-256-GCM verschlüsselt. Der Schlüssel entsteht aus dem Sync-Passwort und
 * einem zufälligen Salz (liegt unverschlüsselt in [KEY_FILE], ist aber ohne Passwort wertlos).
 */
object SyncCrypto {
    const val KEY_FILE = "spritbuch.key"
    private val MAGIC = "FWE1".toByteArray()
    private val KEY_MAGIC = "FWK1".toByteArray()
    private const val ITERATIONS = 210_000

    class WrongPassword : Exception()

    fun isEncrypted(b: ByteArray) = b.size > MAGIC.size + 12 && b.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)

    fun newSalt(): ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }
    fun keyFile(salt: ByteArray): ByteArray = KEY_MAGIC + salt
    fun saltFrom(file: ByteArray): ByteArray? =
        if (file.size == KEY_MAGIC.size + 16 && file.copyOfRange(0, KEY_MAGIC.size).contentEquals(KEY_MAGIC)) file.copyOfRange(KEY_MAGIC.size, file.size) else null

    private var cache: Triple<String, String, SecretKey>? = null

    /** Schlüssel ableiten (bewusst langsam gegen Durchprobieren); bleibt im Speicher, solange sich nichts ändert. */
    @Synchronized
    fun derive(password: String, salt: ByteArray): SecretKey {
        val saltId = salt.joinToString("") { "%02x".format(it) }
        cache?.let { (p, s, k) -> if (p == password && s == saltId) return k }
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256)
        val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(raw, "AES").also { cache = Triple(password, saltId, it) }
    }

    fun encrypt(key: SecretKey, plain: ByteArray): ByteArray {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        return MAGIC + iv + c.doFinal(plain)
    }

    fun decrypt(key: SecretKey, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data, MAGIC.size, 12))
        return try {
            c.doFinal(data, MAGIC.size + 12, data.size - MAGIC.size - 12)
        } catch (e: AEADBadTagException) {
            throw WrongPassword()
        }
    }
}
