package de.spritbuch.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private val DE = Locale.GERMANY

fun Double.fmt(decimals: Int = 2): String = String.format(DE, "%.${decimals}f", this)
fun Int.km(): String = String.format(DE, "%,d km", this)
fun Long.dateTime(): String = SimpleDateFormat("dd.MM.yyyy HH:mm", DE).format(Date(this))
fun Long.date(): String = SimpleDateFormat("dd.MM.yyyy", DE).format(Date(this))
fun String.toDoubleDe(): Double? {
    val s = trim()
    return if (s.contains(',')) s.replace(".", "").replace(',', '.').toDoubleOrNull() else s.toDoubleOrNull()
}
fun String.toIntKm(): Int? = filter { it.isDigit() }.toIntOrNull()

/** Kombiniert ein vom DatePicker geliefertes UTC-Datum mit der Uhrzeit eines lokalen Zeitstempels. */
fun combineDate(utcDateMillis: Long, timeOf: Long): Long {
    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcDateMillis }
    val local = Calendar.getInstance().apply { timeInMillis = timeOf }
    local.set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH))
    return local.timeInMillis
}

/** Für den DatePicker: lokales Datum als UTC-Mitternacht. */
fun toUtcDate(localMillis: Long): Long {
    val local = Calendar.getInstance().apply { timeInMillis = localMillis }
    return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis
}

data class Stats(
    val avg: Double?,
    val last: Double?,
    val totalCost: Double,
    val totalAmount: Double,
    val costPer100: Double?,
    val distance: Int,
    val perRefuel: Map<String, Double>,
)

/**
 * Verbrauch nach der Voll-zu-Voll-Methode: Die Menge aller Tankvorgänge seit der letzten
 * Volltankung wird auf die gefahrene Strecke umgelegt. "Tankvorgang vergessen" startet neu.
 */
fun computeStats(refuels: List<Refuel>): Stats {
    val sorted = refuels.sortedWith(compareBy({ it.odometer }, { it.timestamp }))
    val per = mutableMapOf<String, Double>()
    var lastFull: Refuel? = null
    var acc = 0.0
    var accCost = 0.0
    var spanAmount = 0.0
    var spanDist = 0
    var spanCost = 0.0
    for (r in sorted) {
        if (r.missedPrevious) {
            lastFull = if (r.fullTank) r else null
            acc = 0.0; accCost = 0.0
            continue
        }
        acc += r.amount
        accCost += r.totalPrice
        if (r.fullTank) {
            val prev = lastFull
            if (prev != null) {
                val dist = r.odometer - prev.odometer
                if (dist > 0) {
                    per[r.id] = acc / dist * 100.0
                    spanAmount += acc; spanDist += dist; spanCost += accCost
                }
            }
            lastFull = r
            acc = 0.0; accCost = 0.0
        }
    }
    val lastValue = sorted.asReversed().firstNotNullOfOrNull { per[it.id] }
    val distance = if (sorted.size >= 2) sorted.last().odometer - sorted.first().odometer else 0
    return Stats(
        avg = if (spanDist > 0) spanAmount / spanDist * 100.0 else null,
        last = lastValue,
        totalCost = refuels.sumOf { it.totalPrice },
        totalAmount = refuels.sumOf { it.amount },
        costPer100 = if (spanDist > 0) spanCost / spanDist * 100.0 else null,
        distance = distance,
        perRefuel = per,
    )
}

object PhotoStore {
    fun name(vehicleId: String, version: Long) = "${vehicleId}_$version.jpg"
    fun file(ctx: Context, vehicleId: String, version: Long) =
        File(File(ctx.filesDir, "photos"), name(vehicleId, version))

    /** Liest ein Bild ein, verkleinert es auf max. 1280 px, korrigiert die Drehung und speichert es als JPEG. */
    suspend fun import(ctx: Context, uri: Uri, vehicleId: String): Long = withContext(Dispatchers.IO) {
        val version = now()
        val resolver = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1280) sample *= 2
        val bmp = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("Bild konnte nicht gelesen werden")
        val rotation = runCatching {
            resolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
        }.getOrDefault(0)
        val finalBmp = if (rotation != 0) {
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
        } else bmp
        val out = file(ctx, vehicleId, version)
        out.parentFile?.mkdirs()
        out.outputStream().use { finalBmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        version
    }
}

class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("spritbuch", Context.MODE_PRIVATE)

    var url: String
        get() = sp.getString("url", "") ?: ""
        set(v) = sp.edit().putString("url", v.trim()).apply()
    var user: String
        get() = sp.getString("user", "") ?: ""
        set(v) = sp.edit().putString("user", v.trim()).apply()
    var password: String
        get() = sp.getString("password", "") ?: ""
        set(v) = sp.edit().putString("password", v).apply()
    var lastSync: String
        get() = sp.getString("lastSync", "") ?: ""
        set(v) = sp.edit().putString("lastSync", v).apply()
    var uploadedPhotos: Set<String>
        get() = sp.getStringSet("uploadedPhotos", emptySet())?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet("uploadedPhotos", v).apply()
    var notified: Set<String>
        get() = sp.getStringSet("notified", emptySet())?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet("notified", v).apply()

    val configured: Boolean get() = url.isNotBlank() && user.isNotBlank() && password.isNotBlank()
}
