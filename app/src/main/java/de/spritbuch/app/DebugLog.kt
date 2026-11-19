package de.spritbuch.app

import android.content.Context
import android.content.Intent
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lokales Fehlerprotokoll – nur wenn in den Einstellungen eingeschaltet (Standard: aus).
 * Bleibt auf dem Gerät, bis es bewusst geteilt wird. Server-Adresse und Benutzername
 * werden vor dem Schreiben unkenntlich gemacht.
 */
object DebugLog {
    private const val MAX_BYTES = 256 * 1024
    @Volatile private var enabled = false
    private lateinit var app: Context

    fun file(ctx: Context) = File(ctx.filesDir, "debug.log")

    fun install(ctx: Context) {
        app = ctx.applicationContext
        enabled = Prefs(app).debugLog
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { error("Absturz (Thread ${t.name})", e) }
            previous?.uncaughtException(t, e)
        }
    }

    fun setEnabled(ctx: Context, on: Boolean) {
        Prefs(ctx).debugLog = on
        enabled = on
        if (on) info("Protokoll eingeschaltet – ${header()}") else clear(ctx)
    }

    fun info(msg: String) = write("I", msg, null)
    fun error(msg: String, e: Throwable? = null) = write("E", msg, e)

    @Synchronized
    private fun write(level: String, msg: String, e: Throwable?) {
        if (!enabled || !::app.isInitialized) return
        runCatching {
            val f = file(app)
            if (f.length() > MAX_BYTES) {
                // Ältere Hälfte verwerfen, damit die Datei klein bleibt
                val keep = f.readText().takeLast(MAX_BYTES / 2)
                f.writeText(keep.substringAfter('\n'))
            }
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())
            val trace = e?.let { StringWriter().also { w -> it.printStackTrace(PrintWriter(w)) }.toString() } ?: ""
            f.appendText(redact("$time $level $msg\n$trace"))
        }
    }

    private fun redact(s: String): String {
        val p = Prefs(app)
        var out = s
        listOf(p.url.substringAfter("://").substringBefore('/'), p.user).filter { it.length >= 3 }.forEach {
            out = out.replace(it, "<…>", ignoreCase = true)
        }
        return out
    }

    private fun header() =
        "App ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}"

    fun exists(ctx: Context) = file(ctx).let { it.exists() && it.length() > 0 }

    fun clear(ctx: Context) {
        file(ctx).delete()
    }

    /** Kopie in den Export-Ordner legen und über das Teilen-Menü anbieten (z. B. per Mail). */
    fun share(ctx: Context) {
        val dir = File(ctx.cacheDir, "export").apply { mkdirs() }
        val copy = File(dir, "fuelandwrench-log.txt")
        copy.writeText(header() + "\n\n" + file(ctx).readText())
        val uri = AttachmentStore.uri(ctx, copy)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "Fuel & Wrench – Log")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
