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

    override fun onCreate() {
        super.onCreate()
        repo = Repository(this, AppDatabase.create(this))
        Notifications.createChannel(this)
        SyncScheduler.schedulePeriodic(this)
    }
}

object Notifications {
    private const val CHANNEL = "reminders"

    fun createChannel(ctx: Context) {
        val ch = NotificationChannel(CHANNEL, "Wartungserinnerungen", NotificationManager.IMPORTANCE_DEFAULT)
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
    suspend fun checkAll(ctx: Context) {
        ctx.repo.db.vehicles().getAll().forEach { check(ctx, it.id) }
    }

    /** Meldet jede Erinnerung einmal als "bald fällig" und einmal als "fällig". */
    suspend fun check(ctx: Context, vehicleId: String) {
        val repo = ctx.repo
        val v = repo.getVehicle(vehicleId) ?: return
        if (v.deleted || v.archived) return
        val odo = repo.currentOdometer(vehicleId)
        val prefs = Prefs(ctx)
        val notified = prefs.notified.toMutableSet()
        var changed = false
        for (r in repo.db.reminders().forVehicle(vehicleId)) {
            if (r.intervalKm <= 0) continue
            val remaining = r.dueKm - odo
            val level = when {
                remaining <= 0 -> "due"
                remaining <= r.warnBeforeKm -> "warn"
                else -> continue
            }
            val key = "${r.id}@${r.lastDoneKm}@${r.intervalKm}@$level"
            if (key in notified) continue
            notified += key
            changed = true
            val text = if (level == "due") {
                "Fällig seit ${(-remaining).km()} (fällig bei ${r.dueKm.km()})"
            } else {
                "Fällig in ${remaining.km()} (bei ${r.dueKm.km()})"
            }
            Notifications.show(ctx, (r.id + level).hashCode(), "${v.name}: ${r.title}", text)
        }
        if (changed) prefs.notified = notified
    }
}

data class Station(val brand: String?, val address: String?)

object LocationHelper {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun hasPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun lastKnown(lm: LocationManager): Location? =
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }

    /** Aktuelle Position ohne Google-Dienste (funktioniert auch auf Geräten ohne Play Services). */
    @SuppressLint("MissingPermission")
    suspend fun current(ctx: Context): Location? {
        if (!hasPermission(ctx)) return null
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        val provider = when {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> return lastKnown(lm)
        }
        val fresh = withTimeoutOrNull(20_000) {
            suspendCancellableCoroutine<Location?> { cont ->
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                LocationManagerCompat.getCurrentLocation(lm, provider, signal, ContextCompat.getMainExecutor(ctx)) { loc ->
                    if (cont.isActive) cont.resume(loc)
                }
            }
        }
        return fresh ?: lastKnown(lm)
    }

    @Suppress("DEPRECATION")
    suspend fun address(ctx: Context, lat: Double, lon: Double): String? = withContext(Dispatchers.IO) {
        runCatching {
            if (!Geocoder.isPresent()) return@runCatching null
            val a = Geocoder(ctx, Locale.GERMANY).getFromLocation(lat, lon, 1)?.firstOrNull() ?: return@runCatching null
            val street = listOfNotNull(a.thoroughfare, a.subThoroughfare).joinToString(" ")
            val city = listOfNotNull(a.postalCode, a.locality).joinToString(" ")
            listOf(street, city).filter { it.isNotBlank() }.joinToString(", ").ifBlank { a.getAddressLine(0) }
        }.getOrNull()
    }

    /** Sucht über OpenStreetMap (Overpass) die nächste Tankstelle im Umkreis von 250 m. */
    suspend fun nearbyStation(lat: Double, lon: Double): Station? = withContext(Dispatchers.IO) {
        runCatching {
            val q = "[out:json][timeout:10];nwr(around:250,$lat,$lon)[amenity=fuel];out center tags;"
            val req = Request.Builder()
                .url("https://overpass-api.de/api/interpreter")
                .header("User-Agent", "Spritbuch-Android/1.0")
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
            val rawBrand = tag("brand") ?: tag("operator") ?: tag("name")
            val brand = rawBrand?.let { b ->
                Options.brands.firstOrNull { o -> o.split("/").any { b.contains(it, ignoreCase = true) } } ?: b
            }
            val street = listOfNotNull(tag("addr:street"), tag("addr:housenumber")).joinToString(" ")
            val city = listOfNotNull(tag("addr:postcode"), tag("addr:city")).joinToString(" ")
            val address = listOf(street, city).filter { it.isNotBlank() }.joinToString(", ").ifBlank { null }
            Station(brand, address)
        }.getOrNull()
    }

    private fun coords(e: JsonObject): Pair<Double, Double>? {
        val c = e["center"]?.jsonObject ?: e
        val la = c["lat"]?.jsonPrimitive?.double ?: return null
        val lo = c["lon"]?.jsonPrimitive?.double ?: return null
        return la to lo
    }
}
