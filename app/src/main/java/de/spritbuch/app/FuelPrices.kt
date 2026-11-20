package de.spritbuch.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/** Tankstelle mit aktuellem Preis (Daten der Markttransparenzstelle über Tankerkönig). */
data class PriceStation(
    val id: String,
    val name: String,
    val brand: String,
    val street: String,
    val place: String,
    val lat: Double,
    val lng: Double,
    /** Entfernung in km (vom Suchpunkt). */
    val dist: Double,
    val price: Double?,
    val isOpen: Boolean,
) {
    val label: String get() = brand.ifBlank { name }
}

/**
 * Aktuelle Spritpreise in Deutschland über die Tankerkönig-Schnittstelle (eigener, kostenloser Schlüssel nötig).
 * Abgefragt wird nur bei Bedarf; Ergebnisse bleiben 5 Minuten im Speicher. Der Standort wird dafür
 * auf etwa 1 km gerundet übertragen.
 */
object FuelPrices {
    private const val BASE = "https://creativecommons.tankerkoenig.de/json/list.php"
    private const val CACHE_MS = 5 * 60_000L
    const val REGISTER_URL = "https://onboarding.tankerkoenig.de"
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }

    private data class Entry(val at: Long, val stations: List<PriceStation>)
    private val cache = mutableMapOf<String, Entry>()

    class ApiError(message: String) : IOException(message)

    /** Tankerkönig-Sorte zur Kraftstoffart (nur Benzin E5/E10 und Diesel werden gemeldet). */
    fun typeFor(fuelType: String): String? = when (fuelType) {
        "Super E5" -> "e5"
        "Super E10" -> "e10"
        "Diesel" -> "diesel"
        else -> null
    }

    fun enabled(ctx: Context) = Prefs(ctx).tankerkoenigKey.isNotBlank()

    /** Auf ~1 km runden (Datenschutz und gemeinsame Zwischenspeicherung). */
    fun round(v: Double) = (v * 100).roundToInt() / 100.0

    /** Tankstellen im Umkreis, günstigste zuerst; geschlossene und solche ohne Preis am Ende. */
    suspend fun nearby(ctx: Context, lat: Double, lng: Double, type: String, radiusKm: Int = Prefs(ctx).priceRadius): List<PriceStation> =
        withContext(Dispatchers.IO) {
            val key = Prefs(ctx).tankerkoenigKey
            if (key.isBlank()) return@withContext emptyList()
            val rLat = round(lat)
            val rLng = round(lng)
            val cacheKey = "$rLat,$rLng,$type,$radiusKm"
            synchronized(cache) { cache[cacheKey]?.takeIf { now() - it.at < CACHE_MS }?.let { return@withContext it.stations } }
            val url = BASE.toHttpUrl().newBuilder()
                .addQueryParameter("lat", String.format(Locale.ROOT, "%.2f", rLat))
                .addQueryParameter("lng", String.format(Locale.ROOT, "%.2f", rLng))
                .addQueryParameter("rad", radiusKm.coerceIn(1, 25).toString())
                .addQueryParameter("sort", "price")
                .addQueryParameter("type", type)
                .addQueryParameter("apikey", key)
                .build()
            val body = http.newCall(Request.Builder().url(url).build()).execute().use {
                if (!it.isSuccessful) throw ApiError(httpMessage(it.code))
                it.body.string()
            }
            val list = parse(body)
            synchronized(cache) { cache[cacheKey] = Entry(now(), list) }
            list
        }

    /** Antwort von list.php lesen; bei Fehlern (z. B. ungültiger Schlüssel) die Meldung von Tankerkönig weitergeben. */
    fun parse(body: String): List<PriceStation> {
        val root = json.parseToJsonElement(body).jsonObject
        if (root["ok"]?.jsonPrimitive?.booleanOrNull != true) {
            throw ApiError(root["message"]?.jsonPrimitive?.content ?: str(R.string.prices_error))
        }
        val stations = root["stations"]?.jsonArray ?: return emptyList()
        return stations.map { it.jsonObject.toStation() }
            .sortedWith(compareBy({ !it.isOpen || it.price == null }, { it.price ?: Double.MAX_VALUE }, { it.dist }))
    }

    private fun JsonObject.text(k: String) = this[k]?.jsonPrimitive?.takeIf { it.isString }?.content?.trim() ?: ""
    private fun JsonObject.num(k: String) = this[k]?.jsonPrimitive?.doubleOrNull

    private fun JsonObject.toStation(): PriceStation {
        val nr = text("houseNumber")
        return PriceStation(
            id = text("id"),
            name = text("name").titleCase(),
            brand = text("brand").titleCase(),
            street = (text("street").titleCase() + if (nr.isNotBlank()) " $nr" else "").trim(),
            place = text("place").titleCase(),
            lat = num("lat") ?: 0.0,
            lng = num("lng") ?: 0.0,
            dist = num("dist") ?: 0.0,
            price = num("price")?.takeIf { it > 0 },
            isOpen = this["isOpen"]?.jsonPrimitive?.booleanOrNull ?: false,
        )
    }

    /** Tankerkönig liefert vieles in GROSSBUCHSTABEN; „ARAL“ → „Aral“, Kürzel wie „BP“/„HEM“ bleiben. */
    private fun String.titleCase(): String =
        if (this != uppercase(Locale.GERMAN)) this
        else split(' ').joinToString(" ") { w ->
            if (w.length <= 3) w else w.lowercase(Locale.GERMAN).replaceFirstChar { it.titlecase(Locale.GERMAN) }
        }

    /** Tankstelle an genau diesem Ort (innerhalb von ~150 m), z. B. für die Preisprüfung beim Eintragen. */
    fun at(stations: List<PriceStation>, lat: Double, lng: Double): PriceStation? =
        stations.minByOrNull { distKm(lat, lng, it.lat, it.lng) }?.takeIf { distKm(lat, lng, it.lat, it.lng) < 0.15 }

    /** Weicht der eingegebene Preis deutlich vom gemeldeten ab? (mehr als 5 % bzw. 8 ct) */
    fun deviates(entered: Double, reported: Double) = abs(entered - reported) > maxOf(0.08, reported * 0.05)

    fun distKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = Math.sin(dLat / 2).let { it * it } +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2).let { it * it }
        return 2 * r * Math.asin(Math.sqrt(a))
    }

    /**
     * Standort für die Preisanzeige: zuletzt bekannte Position des Geräts, sonst der Ort der letzten
     * Betankung dieses Fahrzeugs (z. B. auf Tablet oder Navi ohne eigene Ortung).
     */
    suspend fun position(ctx: Context, vehicleId: String): Pair<Double, Double>? {
        LocationHelper.lastKnown(ctx)?.let { return it.latitude to it.longitude }
        return ctx.repo.db.refuels().forVehicle(vehicleId)
            .filter { it.latitude != null && it.longitude != null }
            .maxByOrNull { it.timestamp }
            ?.let { it.latitude!! to it.longitude!! }
    }

    /** „1,659 €“ */
    fun price(p: Double) = p.fmt(3) + " €"

    /** Kurzer Text für Fahrzeugkarte und Widget, z. B. „Diesel 1,629 € · Aral, 2,1 km“. */
    fun summary(fuelType: String, s: PriceStation): String =
        str(R.string.prices_cheapest, lbl(fuelType), price(s.price ?: 0.0), s.label, s.dist.fmt(1))
}
