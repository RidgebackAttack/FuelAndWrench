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

fun Double.fmt(decimals: Int = 2): String = String.format(appLocale(), "%,.${decimals}f", this)
fun Int.km(): String = String.format(appLocale(), "%,d km", this)
fun Int.dist(unit: String): String = String.format(appLocale(), "%,d %s", this, unit)

/** Einheiten pro Fahrzeug. Werte werden in den Einheiten des Fahrzeugs gespeichert. */
object Units {
    val distanceUnits get() = linkedMapOf("km" to str(R.string.unit_km), "mi" to str(R.string.unit_mi))
    val volumeUnits get() = linkedMapOf("l" to str(R.string.unit_l), "gal_us" to str(R.string.unit_gal_us), "gal_uk" to str(R.string.unit_gal_uk))
    val formats get() = linkedMapOf(
        "per100" to str(R.string.format_per100),
        "perUnit" to str(R.string.format_perunit),
    )
    val currencies = listOf("€", "DKK", "£", "\$", "CHF", "SEK", "NOK", "PLN", "CZK")

    fun distFactorToKm(u: String) = if (u == "mi") 1.609344 else 1.0
    fun volFactorToL(u: String) = when (u) { "gal_us" -> 3.785411784; "gal_uk" -> 4.54609; else -> 1.0 }
    fun isLiquid(fuel: String) = fuel != "Strom" && fuel != "CNG"
    fun amount(v: Vehicle?, fuel: String): String = when (fuel) {
        "Strom" -> "kWh"
        "CNG" -> "kg"
        else -> if (v?.volumeUnit == "gal_us" || v?.volumeUnit == "gal_uk") "gal" else "l"
    }
    fun dist(v: Vehicle?) = v?.distanceUnit ?: "km"
    fun cur(v: Vehicle?) = v?.currency ?: "€"

    /** per100 = Menge je 100 Strecke in Fahrzeugeinheiten (aus computeStats). */
    fun consumption(v: Vehicle?, fuel: String, per100: Double?): String {
        if (per100 == null || per100 <= 0) return "–"
        val amt = amount(v, fuel)
        val d = dist(v)
        return if (v?.consumptionFormat == "perUnit") {
            val label = if (d == "mi" && amt == "gal") {
                if (v.volumeUnit == "gal_uk") "mpg (UK)" else "mpg (US)"
            } else "$d/$amt"
            "${(100.0 / per100).fmt(1)} $label"
        } else {
            "${per100.fmt()} $amt/100 $d"
        }
    }
}
private fun pattern(skeleton: String) = android.text.format.DateFormat.getBestDateTimePattern(appLocale(), skeleton)
fun Long.time(): String = SimpleDateFormat(pattern("HHmm"), appLocale()).format(Date(this))
fun Long.dateTime(): String = SimpleDateFormat(pattern("ddMMyyyyHHmm"), appLocale()).format(Date(this))
fun Long.date(): String = SimpleDateFormat(pattern("ddMMyyyy"), appLocale()).format(Date(this))

/** Liest Zahlen mit Komma oder Punkt als Dezimaltrenner (auch mit Tausendertrennern). */
fun String.toDoubleDe(): Double? {
    val s = trim().replace(" ", "").replace("\u00A0", "").replace("\u202F", "")
    val lc = s.lastIndexOf(',')
    val ld = s.lastIndexOf('.')
    val norm = when {
        lc >= 0 && ld >= 0 -> if (lc > ld) s.replace(".", "").replace(',', '.') else s.replace(",", "")
        lc >= 0 -> if (Regex("""\d{1,3}(,\d{3})+""").matches(s) &&
            java.text.DecimalFormatSymbols.getInstance(appLocale()).decimalSeparator == '.') s.replace(",", "")
        else s.replace(',', '.')
        else -> s
    }
    return norm.toDoubleOrNull()
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
    /** Mittlere Abweichung Bordcomputer gegenüber berechnetem Verbrauch (0,05 = BC zeigt 5 % mehr). */
    val obcDeviation: Double? = null,
    val obcCount: Int = 0,
    /** Plug-in-Hybrid: Stromverbrauch zusätzlich zum Kraftstoff (kWh je 100 Strecke). */
    val avgElectric: Double? = null,
    val lastElectric: Double? = null,
    /** Geladene Energie gesamt (kWh). */
    val totalElectric: Double = 0.0,
    /** Verbrauch ab Akku (aus Akkustand und Kapazität), ohne Ladeverluste. */
    val batteryAvg: Double? = null,
)

/**
 * Verbrauch nach der Voll-zu-Voll-Methode: Die Menge aller Tankvorgänge seit der letzten
 * Volltankung wird auf die gefahrene Strecke umgelegt. "Tankvorgang vergessen" startet neu.
 */
/**
 * Kennzahlen eines Fahrzeugs. Kraftstoff und Strom werden getrennt nach der Voll-zu-voll-Methode
 * berechnet (beim Laden: „bis zum Ladeziel geladen“); bei Plug-in-Hybriden ergeben sich so
 * z. B. 3,1 l/100 km plus 9,8 kWh/100 km bezogen auf die gesamte Strecke.
 */
fun computeStats(refuels: List<Refuel>, batteryCapacity: Double? = null): Stats {
    val fuel = refuels.filter { !it.isCharge }
    val elec = refuels.filter { it.isCharge }
    val f = computeGroup(fuel)
    val e = computeGroup(elec)
    val primary = if (fuel.isEmpty()) e else f
    val sorted = refuels.sortedWith(compareBy({ it.odometer }, { it.timestamp }))
    val costParts = listOfNotNull(f.costPer100, e.costPer100)
    // Verbrauch ab Akku: aus Akkustand nach dem letzten und vor diesem Laden
    val battery = if (batteryCapacity != null && batteryCapacity > 0) {
        val s = elec.sortedWith(compareBy({ it.odometer }, { it.timestamp }))
        var used = 0.0
        var dist = 0
        for (i in 1 until s.size) {
            val prev = s[i - 1]
            val cur = s[i]
            val a = prev.socEnd ?: continue
            val b = cur.socStart ?: continue
            val d = cur.odometer - prev.odometer
            if (d > 0 && a >= b && !cur.missedPrevious) {
                used += (a - b) / 100.0 * batteryCapacity
                dist += d
            }
        }
        if (dist > 0) used / dist * 100.0 else null
    } else null
    return primary.copy(
        totalCost = refuels.sumOf { it.totalPrice },
        costPer100 = if (costParts.isEmpty()) null else costParts.sum(),
        distance = if (sorted.size >= 2) sorted.last().odometer - sorted.first().odometer else 0,
        perRefuel = f.perRefuel + e.perRefuel,
        avgElectric = if (fuel.isNotEmpty()) e.avg else null,
        lastElectric = if (fuel.isNotEmpty()) e.last else null,
        totalElectric = elec.sumOf { it.amount },
        batteryAvg = battery,
    )
}

private fun computeGroup(refuels: List<Refuel>): Stats {
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
    val dev = sorted.mapNotNull { r ->
        val real = per[r.id]
        val obc = r.obcConsumption
        if (real != null && real > 0 && obc != null && obc > 0) obc / real - 1.0 else null
    }
    val distance = if (sorted.size >= 2) sorted.last().odometer - sorted.first().odometer else 0
    return Stats(
        avg = if (spanDist > 0) spanAmount / spanDist * 100.0 else null,
        last = lastValue,
        totalCost = refuels.sumOf { it.totalPrice },
        totalAmount = refuels.sumOf { it.amount },
        costPer100 = if (spanDist > 0) spanCost / spanDist * 100.0 else null,
        distance = distance,
        perRefuel = per,
        obcDeviation = dev.takeIf { it.isNotEmpty() }?.average(),
        obcCount = dev.size,
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
        } ?: error(str(R.string.err_image_read))
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

/** Platzhalter für die Standardvorlage „Ölwechsel“, damit sie in jeder Sprache dieselbe Erinnerung bleibt. */
const val OIL_TEMPLATE = "@oil"

data class ReminderTemplate(val title: String, val km: Int, val months: Int = 0)
fun templateTitle(t: String): String = if (t == OIL_TEMPLATE) str(R.string.default_oil_change) else t
/** Schlüssel für die feste Erinnerungs-ID (Ölwechsel behält die bisherige ID). */
fun templateIdKey(t: String): String = (if (t == OIL_TEMPLATE) "ölwechsel" else t).lowercase().filter { it.isLetterOrDigit() }

class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("spritbuch", Context.MODE_PRIVATE)

    var url: String
        get() = sp.getString("url", "") ?: ""
        set(v) = sp.edit().putString("url", v.trim()).apply()
    var user: String
        get() = sp.getString("user", "") ?: ""
        set(v) = sp.edit().putString("user", v.trim()).apply()
    /** App-Passwort – verschlüsselt mit dem Android-Schlüsselspeicher; ältere Klartext-Werte werden beim Lesen umgestellt. */
    var password: String
        get() {
            val stored = sp.getString("password", "") ?: ""
            if (stored.isNotEmpty() && !stored.startsWith("ks1:")) password = stored
            return Secrets.open(stored)
        }
        set(v) = sp.edit().putString("password", Secrets.seal(v)).apply()
    /** Persönlicher Tankerkönig-Schlüssel für aktuelle Spritpreise (verschlüsselt gespeichert). */
    var tankerkoenigKey: String
        get() = Secrets.open(sp.getString("tankerkoenigKey", "") ?: "")
        set(v) = sp.edit().putString("tankerkoenigKey", Secrets.seal(v.trim())).apply()
    /** Suchradius für Spritpreise in km. */
    var priceRadius: Int
        get() = sp.getInt("priceRadius", 5)
        set(v) = sp.edit().putInt("priceRadius", v).apply()
    /** Ende-zu-Ende-Verschlüsselung für den Sync. */
    var syncEncrypt: Boolean
        get() = sp.getBoolean("syncEncrypt", false)
        set(v) = sp.edit().putBoolean("syncEncrypt", v).apply()
    /** Sync-Passwort (nur auf den Geräten, nie auf dem Server). Bleibt auch nach dem Ausschalten, um alte Daten zu lesen. */
    var syncPassphrase: String
        get() = Secrets.open(sp.getString("syncPassphrase", "") ?: "")
        set(v) = sp.edit().putString("syncPassphrase", Secrets.seal(v)).apply()
    /** Auf diesem Gerät umgeschaltet, beim nächsten Sync auf dem Server umsetzen. */
    var syncEncryptChanged: Boolean
        get() = sp.getBoolean("syncEncryptChanged", false)
        set(v) = sp.edit().putBoolean("syncEncryptChanged", v).apply()
    /** Beim nächsten Sync Fotos und Belege neu hochladen (nach Ein-/Ausschalten der Verschlüsselung). */
    var reuploadFiles: Boolean
        get() = sp.getBoolean("reuploadFiles", false)
        set(v) = sp.edit().putBoolean("reuploadFiles", v).apply()
    /** Basisadresse des Servers (z. B. https://cloud.example.de). */
    var server: String
        get() = sp.getString("server", "") ?: ""
        set(v) = sp.edit().putString("server", v.trim()).apply()
    /** Gewählter Ordner relativ zum WebDAV-Stamm (unkodiert). */
    var folder: String
        get() = sp.getString("folder", "") ?: ""
        set(v) = sp.edit().putString("folder", v.trim('/')).apply()
    var lastSync: String
        get() = sp.getString("lastSync", "") ?: ""
        set(v) = sp.edit().putString("lastSync", v).apply()
    var uploadedPhotos: Set<String>
        get() = sp.getStringSet("uploadedPhotos", emptySet())?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet("uploadedPhotos", v).apply()
    var uploadedAttachments: Set<String>
        get() = sp.getStringSet("uploadedAttachments", emptySet())?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet("uploadedAttachments", v).apply()
    var notified: Set<String>
        get() = sp.getStringSet("notified", emptySet())?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet("notified", v).apply()

    /** Vorlagen für automatisch angelegte Erinnerungen (Intervall in km und/oder Monaten). */
    var reminderTemplates: List<ReminderTemplate>
        get() = (sp.getString("reminderTemplates", null) ?: "$OIL_TEMPLATE|10000")
            .split("\n").mapNotNull { line ->
                val p = line.split("|")
                val km = p.getOrNull(1)?.toIntOrNull() ?: 0
                val months = p.getOrNull(2)?.toIntOrNull() ?: 0
                if (p[0].isBlank() || (km <= 0 && months <= 0)) null else ReminderTemplate(p[0], km, months)
            }
        set(v) = sp.edit()
            .putString("reminderTemplates", encodeTemplates(v))
            .putLong("templatesUpdatedAt", now())
            .apply()

    private fun encodeTemplates(v: List<ReminderTemplate>) = v.joinToString("\n") { "${it.title}|${it.km}|${it.months}" }

    val templatesUpdatedAt: Long get() = sp.getLong("templatesUpdatedAt", 0L)

    /** Übernahme vom Sync, ohne den Zeitstempel neu zu setzen. */
    fun setTemplatesFromSync(v: List<ReminderTemplate>, ts: Long) {
        sp.edit()
            .putString("reminderTemplates", encodeTemplates(v))
            .putLong("templatesUpdatedAt", ts)
            .apply()
    }

    /** Datenschutz: Tankstellensuche über OpenStreetMap (Overpass-API). */
    var useOverpass: Boolean
        get() = sp.getBoolean("useOverpass", true)
        set(v) = sp.edit().putBoolean("useOverpass", v).apply()
    /** Datenschutz: Adresse über den Android-Geocoder (bei Google-Android: Google-Server). */
    var useGeocoder: Boolean
        get() = sp.getBoolean("useGeocoder", false)
        set(v) = sp.edit().putBoolean("useGeocoder", v).apply()


    /** Datenschutz: Netzwerk-Ortung (WLAN/Mobilfunk – bei Google-Android über Google). Standard: aus = nur GPS. */
    var useNetworkLocation: Boolean
        get() = sp.getBoolean("useNetworkLocation", false)
        set(v) = sp.edit().putBoolean("useNetworkLocation", v).apply()

    val configured: Boolean get() = url.isNotBlank() && user.isNotBlank() && password.isNotBlank()

    /**
     * Fahrzeuge, die dieses Gerät anzeigt (z. B. Navi im Auto nur das Auto). null = alle.
     * Synchronisiert werden trotzdem alle Daten, damit auf dem Server nichts verloren geht.
     */
    var visibleVehicles: Set<String>?
        get() = if (sp.getBoolean("allVehicles", true)) null else sp.getStringSet("visibleVehicles", emptySet())?.toSet()
        set(v) = sp.edit().putBoolean("allVehicles", v == null).putStringSet("visibleVehicles", v ?: emptySet()).apply()

    fun shows(vehicleId: String): Boolean = visibleVehicles?.contains(vehicleId) ?: true

    /** Kontextmenü am App-Icon: "generic" (mit Fahrzeugauswahl) oder "perVehicle". */
    var shortcutMode: String
        get() = sp.getString("shortcutMode", "generic") ?: "generic"
        set(v) = sp.edit().putString("shortcutMode", v).apply()

    /** Update-Hinweis (nur bei direkt installierter APK): fragt GitHub nach der neuesten Version. */
    var debugLog: Boolean
        get() = sp.getBoolean("debugLog", false)
        set(v) = sp.edit().putBoolean("debugLog", v).apply()
    var updateCheck: Boolean
        get() = sp.getBoolean("updateCheck", true)
        set(v) = sp.edit().putBoolean("updateCheck", v).apply()
    /** Zeitraum der Verlaufsdiagramme (siehe ChartRange). */
    var chartRange: String
        get() = sp.getString("chartRange", ChartRange.LAST30) ?: ChartRange.LAST30
        set(v) = sp.edit().putString("chartRange", v).apply()
    var chartFrom: Long
        get() = sp.getLong("chartFrom", 0)
        set(v) = sp.edit().putLong("chartFrom", v).apply()
    var chartTo: Long
        get() = sp.getLong("chartTo", 0)
        set(v) = sp.edit().putLong("chartTo", v).apply()
    var lastUpdateCheck: Long
        get() = sp.getLong("lastUpdateCheck", 0L)
        set(v) = sp.edit().putLong("lastUpdateCheck", v).apply()
    var latestRelease: UpdateInfo?
        get() = sp.getString("latestRelease", null)?.split("|")?.takeIf { it.size >= 2 }
            ?.let { UpdateInfo(it[0], it[1], it.getOrNull(2)?.ifBlank { null }) }
        set(v) = sp.edit().putString("latestRelease", v?.let { "${it.version}|${it.pageUrl}|${it.apkUrl ?: ""}" }).apply()
    var notifiedUpdate: String
        get() = sp.getString("notifiedUpdate", "") ?: ""
        set(v) = sp.edit().putString("notifiedUpdate", v).apply()
    var dismissedUpdate: String
        get() = sp.getString("dismissedUpdate", "") ?: ""
        set(v) = sp.edit().putString("dismissedUpdate", v).apply()
}


/** Profiltiefe je Position: alt (ausgebaut/vorher) und neu (eingebaut/nachher), in mm. */
data class Tread(val old: Double?, val new: Double?)

object TreadCodec {
    fun parse(s: String): Map<String, Tread> = s.split(";").mapNotNull { part ->
        val p = part.split(":")
        if (p.size < 3 || p[0].isBlank()) null
        else p[0] to Tread(p[1].toDoubleOrNull(), p[2].toDoubleOrNull())
    }.toMap()

    fun encode(m: Map<String, Tread>): String = m.filterValues { it.old != null || it.new != null }
        .entries.joinToString(";") { (k, t) -> "$k:${t.old ?: ""}:${t.new ?: ""}" }

    fun summary(s: String): String = parse(s).entries.joinToString(" · ") { (k, t) ->
        val o = t.old?.fmt(1)
        val n = t.new?.fmt(1)
        lbl(k) + " " + when {
            o != null && n != null -> "$o → $n mm"
            n != null -> "$n mm"
            else -> str(R.string.tread_old_only, o ?: "")
        }
    }
}

/** Speichert eine Compose-Map im Bundle, damit Eingaben das Neuerstellen der Activity überstehen. */
val StringMapSaver = androidx.compose.runtime.saveable.Saver<androidx.compose.runtime.snapshots.SnapshotStateMap<String, String>, HashMap<String, String>>(
    save = { HashMap(it) },
    restore = { androidx.compose.runtime.mutableStateMapOf<String, String>().apply { putAll(it) } },
)

/**
 * Gefahrene Strecke seit der vorherigen Betankung bzw. Ladung (nach Kilometerstand sortiert).
 * Die erste Betankung hat keinen Vorgänger und fehlt in der Liste.
 */
fun distancesSincePrevious(refuels: List<Refuel>): Map<String, Int> {
    val sorted = refuels.filter { !it.deleted }.sortedWith(compareBy({ it.odometer }, { it.timestamp }))
    return sorted.zipWithNext().mapNotNull { (a, b) -> (b.odometer - a.odometer).takeIf { it > 0 }?.let { b.id to it } }.toMap()
}

/**
 * Statistik je Fahrzeug zwischenspeichern: Ändert sich nur ein Fahrzeug, rechnet die Liste nicht alle neu.
 * Der Fingerabdruck ändert sich, sobald eine Betankung dazukommt, geändert oder gelöscht wird.
 */
object StatsCache {
    private val cache = HashMap<String, Pair<Long, Stats>>()

    private fun fingerprint(refuels: List<Refuel>, battery: Double?): Long {
        var h = refuels.size.toLong() * 31 + (battery?.hashCode() ?: 0)
        for (r in refuels) h = h * 31 + r.id.hashCode() + r.updatedAt + if (r.deleted) 1 else 0
        return h
    }

    fun get(vehicleId: String, refuels: List<Refuel>, battery: Double?): Stats {
        val fp = fingerprint(refuels, battery)
        synchronized(cache) { cache[vehicleId]?.let { if (it.first == fp) return it.second } }
        val s = computeStats(refuels, battery)
        synchronized(cache) { cache[vehicleId] = fp to s }
        return s
    }
}
