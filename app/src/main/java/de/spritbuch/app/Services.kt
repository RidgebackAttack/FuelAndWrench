package de.spritbuch.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class App : Application() {
    lateinit var repo: Repository
        private set

    companion object {
        lateinit var instance: App
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        DebugLog.install(this)
        repo = Repository(this, AppDatabase.create(this))
        // Alles, was nicht für das erste Bild nötig ist, im Hintergrund (schnellerer Start)
        repo.launch {
            runCatching { Notifications.createChannel(this@App) }
            runCatching { SyncScheduler.schedulePeriodic(this@App) }
            runCatching { DailyWorker.schedule(this@App) }
            Shortcuts.update(this@App)
            runCatching { repo.repairImportedData() }
        }
    }
}

object Notifications {
    private const val CHANNEL = "reminders"

    fun createChannel(ctx: Context) {
        val ch = NotificationChannel(CHANNEL, ctx.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_DEFAULT)
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    fun show(ctx: Context, id: Int, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val pi = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(id, n)
        } catch (_: SecurityException) {
        }
    }
}

object ReminderChecker {
    /** Text wie „Noch 1.200 km (fällig bei 120.000 km) · noch 40 Tage (01.03.2027)“. */
    fun describe(r: Reminder, st: ReminderState, unit: String): String = listOfNotNull(
        st.remainingKm?.let { km ->
            if (km <= 0) str(R.string.rem_km_over, (-km).dist(unit), r.dueKm.dist(unit))
            else str(R.string.rem_km_left, km.dist(unit), r.dueKm.dist(unit))
        },
        st.remainingDays?.let { d ->
            val date = r.dueDate?.date() ?: ""
            if (d < 0) str(R.string.rem_date_over, date) else str(R.string.rem_date_left, d, date)
        },
    ).joinToString(" · ")

    suspend fun checkAll(ctx: Context) {
        ctx.repo.db.vehicles().getAll().forEach { check(ctx, it.id) }
    }

    /** Meldet jede Erinnerung einmal als "bald fällig" und einmal als "fällig". */
    suspend fun check(ctx: Context, vehicleId: String) {
        val repo = ctx.repo
        val v = repo.getVehicle(vehicleId) ?: return
        if (v.deleted || v.archived || !Prefs(ctx).shows(v.id)) return
        val odo = repo.currentOdometer(vehicleId)
        val prefs = Prefs(ctx)
        val notified = prefs.notified.toMutableSet()
        var changed = false
        for (r in repo.db.reminders().forVehicle(vehicleId)) {
            if (!r.byKm && !r.byDate) continue
            val st = r.state(odo)
            val level = when {
                st.overdue -> "due"
                st.warn -> "warn"
                else -> continue
            }
            val key = "${r.id}@${r.lastDoneKm}@${r.intervalKm}@${r.lastDoneDate}@${r.intervalMonths}@$level"
            if (key in notified) continue
            notified += key
            changed = true
            Notifications.show(ctx, (r.id + level).hashCode(), "${v.name}: ${r.title}", describe(r, st, v.distanceUnit))
        }
        if (changed) prefs.notified = notified
    }
}

data class Station(val brand: String?, val address: String?, val lat: Double? = null, val lon: Double? = null)

object LocationHelper {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** GPS braucht die genaue Standortberechtigung. */
    fun hasPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun lastKnown(lm: LocationManager): Location? =
        runCatching { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull()

    @SuppressLint("MissingPermission")
    private fun lastKnownAny(lm: LocationManager, allowNetwork: Boolean): Location? =
        listOfNotNull(
            lastKnown(lm),
            if (allowNetwork) runCatching { lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull() else null,
        ).maxByOrNull { it.time }

    /** Aktuelle Position ohne Google-Dienste (funktioniert auch auf Geräten ohne Play Services). */
    @SuppressLint("MissingPermission")
    suspend fun current(ctx: Context): Location? {
        if (!hasPermission(ctx)) return null
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        // Standard: nur GPS. Netzwerk-Ortung (WLAN/Mobilfunk) nur, wenn in den Einstellungen eingeschaltet.
        val allowNetwork = Prefs(ctx).useNetworkLocation
        val provider = when {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            allowNetwork && lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> return lastKnownAny(lm, allowNetwork)
        }
        val fresh = withTimeoutOrNull(30_000) {
            suspendCancellableCoroutine<Location?> { cont ->
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                LocationManagerCompat.getCurrentLocation(lm, provider, signal, ContextCompat.getMainExecutor(ctx)) { loc ->
                    if (cont.isActive) cont.resume(loc)
                }
            }
        }
        if (fresh != null) return fresh
        // GPS ohne Fix (z. B. unter dem Tankstellendach): bei erlaubter Netzwerk-Ortung darauf ausweichen
        if (provider == LocationManager.GPS_PROVIDER && allowNetwork && lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            val net = withTimeoutOrNull(15_000) {
                suspendCancellableCoroutine<Location?> { cont ->
                    val signal = CancellationSignal()
                    cont.invokeOnCancellation { signal.cancel() }
                    LocationManagerCompat.getCurrentLocation(
                        lm, LocationManager.NETWORK_PROVIDER, signal, ContextCompat.getMainExecutor(ctx)
                    ) { loc -> if (cont.isActive) cont.resume(loc) }
                }
            }
            if (net != null) return net
        }
        return lastKnownAny(lm, allowNetwork)
    }

    @Suppress("DEPRECATION")
    suspend fun address(ctx: Context, lat: Double, lon: Double): String? = withContext(Dispatchers.IO) {
        runCatching {
            if (!Geocoder.isPresent()) return@runCatching null
            val a = Geocoder(ctx, appLocale()).getFromLocation(lat, lon, 1)?.firstOrNull() ?: return@runCatching null
            val street = listOfNotNull(a.thoroughfare, a.subThoroughfare).joinToString(" ")
            val city = listOfNotNull(a.postalCode, a.locality).joinToString(" ")
            listOf(street, city).filter { it.isNotBlank() }.joinToString(", ").ifBlank { a.getAddressLine(0) }
        }.getOrNull()
    }

    /** Sucht über OpenStreetMap (Overpass) die nächste Tankstelle im Umkreis von 250 m. */
    suspend fun nearbyStation(lat: Double, lon: Double, charging: Boolean = false): Station? = withContext(Dispatchers.IO) {
        runCatching {
            val amenity = if (charging) "charging_station" else "fuel"
            val radius = if (charging) 150 else 250
            val q = "[out:json][timeout:10];nwr(around:$radius,$lat,$lon)[amenity=$amenity];out center tags;"
            val req = Request.Builder()
                .url("https://overpass-api.de/api/interpreter")
                .header("User-Agent", "FuelAndWrench-Android/1.0")
                .post(FormBody.Builder().add("data", q).build())
                .build()
            val body = http.newCall(req).execute().use { if (it.isSuccessful) it.body?.string() else null }
                ?: return@runCatching null
            val elements = Json.parseToJsonElement(body).jsonObject["elements"]?.jsonArray ?: return@runCatching null
            val best = elements.map { it.jsonObject }.minByOrNull { e ->
                val (eLat, eLon) = coords(e) ?: return@minByOrNull Double.MAX_VALUE
                val r = FloatArray(1)
                Location.distanceBetween(lat, lon, eLat, eLon, r)
                r[0].toDouble()
            } ?: return@runCatching null
            val tags = best["tags"]?.jsonObject
            fun tag(k: String) = tags?.get(k)?.jsonPrimitive?.contentOrNull
            val rawBrand = if (charging) tag("network") ?: tag("brand") ?: tag("operator") ?: tag("name")
            else tag("brand") ?: tag("operator") ?: tag("name")
            val brand = rawBrand?.let { if (charging) it.trim() else Options.matchBrand(it) }
            val street = listOfNotNull(tag("addr:street"), tag("addr:housenumber")).joinToString(" ")
            val city = listOfNotNull(tag("addr:postcode"), tag("addr:city")).joinToString(" ")
            val (sLat, sLon) = coords(best) ?: (lat to lon)
            // Viele Tankstellen sind in OSM ohne (vollständige) Adresse eingetragen → über Nominatim ergänzen
            val address = if (street.isNotBlank() && city.isNotBlank()) "$street, $city"
            else osmAddress(sLat, sLon) ?: listOf(street, city).filter { it.isNotBlank() }.joinToString(", ").ifBlank { null }
            Station(brand, address, sLat, sLon)
        }.getOrNull()
    }

    /** Adresse zu Koordinaten über OpenStreetMap Nominatim (Rückwärtssuche). */
    suspend fun osmAddress(lat: Double, lon: Double): String? = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=18&addressdetails=1" +
                "&accept-language=${appLocale().language}&lat=${"%.6f".format(Locale.US, lat)}&lon=${"%.6f".format(Locale.US, lon)}"
            val req = Request.Builder().url(url)
                .header("User-Agent", "FuelAndWrench-Android (https://github.com/RidgebackAttack/FuelAndWrench)")
                .build()
            val body = http.newCall(req).execute().use { if (it.isSuccessful) it.body?.string() else null }
                ?: return@runCatching null
            val a = Json.parseToJsonElement(body).jsonObject["address"]?.jsonObject ?: return@runCatching null
            fun f(vararg k: String) = k.firstNotNullOfOrNull { a[it]?.jsonPrimitive?.contentOrNull?.takeIf { s -> s.isNotBlank() } }
            val street = listOfNotNull(f("road", "pedestrian", "footway", "path"), f("house_number")).joinToString(" ")
            val city = listOfNotNull(f("postcode"), f("city", "town", "village", "municipality", "hamlet")).joinToString(" ")
            listOf(street, city).filter { it.isNotBlank() }.joinToString(", ").ifBlank { null }
        }.getOrNull()
    }

    private fun coords(e: JsonObject): Pair<Double, Double>? {
        val c = e["center"]?.jsonObject ?: e
        val la = c["lat"]?.jsonPrimitive?.double ?: return null
        val lo = c["lon"]?.jsonPrimitive?.double ?: return null
        return la to lo
    }
}
