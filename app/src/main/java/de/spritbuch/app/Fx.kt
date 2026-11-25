package de.spritbuch.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Wechselkurse für Betankungen im Ausland: Referenzkurse der Europäischen Zentralbank (kostenlos, ohne
 * Anmeldung). Abgefragt wird nur, wenn eine Fremdwährung gewählt ist – übertragen werden nur Währung
 * und Datum, keine persönlichen Daten. Der Kurs lässt sich jederzeit von Hand ändern.
 */
object Fx {
    /** Europäische Währungen mit EZB-Referenzkurs (Euro-Länder zahlen in EUR). */
    val european = listOf("EUR", "DKK", "SEK", "NOK", "CHF", "GBP", "PLN", "CZK", "HUF", "RON", "ISK", "TRY")

    private const val API = "https://data-api.ecb.europa.eu/service/data/EXR/D.%s.EUR.SP00.A?startPeriod=%s&endPeriod=%s&format=csvdata"
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()

    /** Währung des Fahrzeugs („€“, „£“ …) als ISO-Code. */
    fun code(cur: String): String = when (cur.trim()) {
        "€", "" -> "EUR"
        "£" -> "GBP"
        "$" -> "USD"
        else -> cur.trim().uppercase()
    }

    /** Auswahl für eine Betankung: Fahrzeugwährung zuerst, dann die übrigen europäischen. */
    fun choices(v: Vehicle?): List<String> {
        val own = code(Units.cur(v))
        return listOf(own) + european.filter { it != own }
    }

    private fun day(ts: Long) = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ts))

    /** Letzter Kurs aus einer EZB-CSV-Antwort: (Einheiten je 1 EUR, Datum „yyyy-MM-dd“). */
    fun parse(csv: String): Pair<Double, String>? {
        val lines = csv.lines().filter { it.isNotBlank() }
        if (lines.size < 2) return null
        val head = lines[0].split(',')
        val iDate = head.indexOf("TIME_PERIOD")
        val iVal = head.indexOf("OBS_VALUE")
        if (iDate < 0 || iVal < 0) return null
        return lines.drop(1).mapNotNull { l ->
            val c = l.split(',')
            val v = c.getOrNull(iVal)?.toDoubleOrNull() ?: return@mapNotNull null
            (c.getOrNull(iDate) ?: return@mapNotNull null) to v
        }.maxByOrNull { it.first }?.let { it.second to it.first }
    }

    /** Kurs „Einheiten je 1 EUR“ für den Tag (bzw. den letzten Bankarbeitstag davor). */
    private suspend fun perEuro(ctx: Context, code: String, ts: Long): Pair<Double, String>? {
        if (code == "EUR") return 1.0 to day(ts)
        val sp = ctx.applicationContext.getSharedPreferences("spritbuch", Context.MODE_PRIVATE)
        val key = "fx_${code}_${day(ts)}"
        sp.getString(key, null)?.split('|')?.let { p -> p[0].toDoubleOrNull()?.let { return it to p.getOrElse(1) { day(ts) } } }
        val from = day(ts - 10L * 24 * 3600 * 1000)
        val url = API.format(code, from, day(ts))
        val res = withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url(url).header("Accept", "text/csv").build()).execute().use { r ->
                if (!r.isSuccessful) null else r.body.string()
            }
        } ?: return null
        val found = parse(res) ?: return null
        sp.edit().putString(key, "${found.first}|${found.second}").apply()
        return found
    }

    /**
     * Kurs „Fremdwährung je 1 Fahrzeugwährung“ (z. B. 7,46 DKK je 1 €) und das Datum des Referenzkurses.
     * Über den Euro umgerechnet, falls das Fahrzeug nicht in Euro geführt wird.
     */
    suspend fun rate(ctx: Context, foreign: String, vehicleCur: String, ts: Long): Pair<Double, String>? {
        val f = perEuro(ctx, foreign, ts) ?: return null
        val v = perEuro(ctx, code(vehicleCur), ts) ?: return null
        return f.first / v.first to f.second
    }
}
