package de.spritbuch.app

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.text.SimpleDateFormat
import java.util.Locale

data class ImportResult(
    val refuels: List<Refuel>,
    val skipped: Int,
    val missingColumns: List<String>,
)

/**
 * Import von CSV-Exporten (Spritmonitor.de, aber auch andere Apps).
 * Die Spalten werden anhand der Kopfzeile erkannt (deutsch und englisch), Trennzeichen,
 * Zeichensatz, Zahlen- und Datumsformat automatisch bestimmt.
 */
object CsvImport {

    fun decode(bytes: ByteArray): String {
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            String(bytes, Charset.forName("windows-1252"))
        }
        return text.removePrefix("﻿")
    }

    fun parse(text: String, vehicle: Vehicle): ImportResult {
        val lines = text.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return ImportResult(emptyList(), 0, listOf("Datei ist leer"))
        val headerIdx = lines.indexOfFirst { l ->
            val n = l.lowercase()
            (n.contains("datum") || n.contains("date")) &&
                (n.contains("menge") || n.contains("quantity") || n.contains("liter") || n.contains("volume"))
        }.coerceAtLeast(0)
        val headerLine = lines[headerIdx]
        val sep = listOf(';', '\t', ',').maxBy { c -> headerLine.count { it == c } }
        val header = splitCsv(headerLine, sep).map { it.trim().lowercase() }

        fun col(vararg cands: String, exclude: List<String> = emptyList()): Int = header.indexOfFirst { h ->
            exclude.none { h.contains(it) } && cands.any { c -> h == c || (c.length >= 5 && h.contains(c)) }
        }

        val cDate = col("datum", "date")
        val cTime = col("uhrzeit", "time", exclude = listOf("date"))
        val cOdo = col("kilometerstand", "tachostand", "km-stand", "kmstand", "odometer", "mileage", "tacho")
        val cTrip = col("distanz", "tageskilometer", "trip", "distance", "gefahrene km", "km")
        val cAmount = col("menge", "quantity", "liter", "litres", "liters", "volume", "kwh",
            exclude = listOf("bc-", "bordcomputer", "/", "pro "))
        val cPpu = col("preis/l", "eur/l", "€/l", "preis pro", "price per", "literpreis", "price/l", "einzelpreis")
        val cTotal = col("gesamtpreis", "gesamtbetrag", "gesamtkosten", "kosten", "betrag", "eur", "€", "cost", "total", "price",
            exclude = listOf("/", "pro ", "per ", "währung", "currency"))
        val cType = col("tankart", "betankung", "tankung", "type", "fueling type", "voll", "partial", "teilbetankung")
        val typeIsPartialFlag = cType >= 0 && (header[cType].contains("partial") || header[cType].contains("teilbetankung"))
        val cFuel = col("kraftstoff", "sorte", "fuel", "fuel type", "fuelsort",
            exclude = listOf("verbrauch", "consumption", "art der", "tankart"))
        val cTires = col("reifen", "tires", "tyres", "tire", "tyre")
        val cRoute = col("strecken", "streckenart", "fahrstrecke", "route", "routes", "roads", "straßen")
        val cStyle = col("fahrweise", "fahrstil", "driving style", "drivingstyle", "style")
        val cNote = col("bemerkung", "notiz", "note", "notes", "comment", "kommentar")
        val cStation = col("tankstelle", "station", "company", "marke", "betreiber", "brand", "anbieter")
        val cLocation = col("ort", "location", "adresse", "address", "standort")
        val cLat = col("latitude", "breitengrad", "lat")
        val cLon = col("longitude", "längengrad", "lon", "lng")
        val extraCols = mapOf(
            "Klimaanlage" to col("klimaanlage", "klima", "ac", "air condition"),
            "Anhänger" to col("anhänger", "trailer"),
            "Standheizung" to col("standheizung", "heater", "aux heater"),
            "Dachbox/Fahrradträger" to col("dachbox", "fahrradträger", "roof box"),
        ).filterValues { it >= 0 }
        val cExtras = col("extras", "verbraucher", "zusatz", "attributes")

        val missing = buildList {
            if (cDate < 0) add("Datum")
            if (cOdo < 0 && cTrip < 0) add("Kilometerstand")
            if (cAmount < 0) add("Menge")
        }
        if (missing.isNotEmpty()) return ImportResult(emptyList(), lines.size - headerIdx - 1, missing)

        val result = mutableListOf<Refuel>()
        var skipped = 0
        var runningOdo = vehicle.initialOdometer.toDouble()
        for (line in lines.drop(headerIdx + 1)) {
            val f = splitCsv(line, sep)
            fun v(i: Int) = if (i in f.indices) f[i].trim() else ""

            val date = parseDate(v(cDate), v(cTime))
            val amount = parseNum(v(cAmount), false)
            val trip = if (cTrip >= 0) parseNum(v(cTrip), false) else null
            val odo = if (cOdo >= 0) parseNum(v(cOdo), true) else trip?.let { runningOdo + it }
            if (date == null || amount == null || amount <= 0 || odo == null || odo <= 0) {
                skipped++
                continue
            }
            runningOdo = odo
            val ppu = if (cPpu >= 0) parseNum(v(cPpu), false) else null
            val total = (if (cTotal >= 0) parseNum(v(cTotal), false) else null) ?: ppu?.let { it * amount } ?: 0.0

            val typeRaw = v(cType).lowercase()
            val partial = if (typeIsPartialFlag) isTruthy(typeRaw)
            else typeRaw.contains("teil") || typeRaw.contains("partial")
            val missed = typeRaw.contains("erst") || typeRaw.contains("first") || typeRaw.contains("ungültig") ||
                typeRaw.contains("invalid") || typeRaw.contains("missed")

            val extras = mutableListOf<String>()
            extraCols.forEach { (name, i) -> if (isTruthy(v(i))) extras += name }
            val extrasText = v(cExtras).lowercase() + " " + v(cNote).lowercase()
            if (extrasText.contains("klima") || extrasText.contains("air con")) extras += "Klimaanlage"
            if (extrasText.contains("anhänger") || extrasText.contains("trailer")) extras += "Anhänger"
            if (extrasText.contains("standheizung")) extras += "Standheizung"
            if (extrasText.contains("dachbox") || extrasText.contains("fahrradträger")) extras += "Dachbox/Fahrradträger"

            result += Refuel(
                vehicleId = vehicle.id,
                timestamp = date,
                odometer = odo.toInt(),
                amount = amount,
                totalPrice = total,
                fullTank = !partial,
                missedPrevious = missed,
                fuelType = mapFuel(v(cFuel)) ?: vehicle.defaultFuelType,
                stationBrand = mapBrand(v(cStation)),
                stationAddress = v(cLocation),
                latitude = parseNum(v(cLat), false)?.takeIf { it != 0.0 },
                longitude = parseNum(v(cLon), false)?.takeIf { it != 0.0 },
                extras = Options.extras.filter { it in extras }.joinToString("|"),
                drivingStyle = mapStyle(v(cStyle)),
                routeType = mapRoute(v(cRoute)),
                tireType = mapTires(v(cTires)),
                note = v(cNote),
            )
        }
        return ImportResult(result, skipped, emptyList())
    }

    private fun splitCsv(line: String, sep: Char): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                ch == '"' -> quoted = !quoted
                ch == sep && !quoted -> { out += sb.toString(); sb.clear() }
                else -> sb.append(ch)
            }
            i++
        }
        out += sb.toString()
        return out
    }

    /** Erkennt "1.234,56", "1234.56", "1234,5" sowie (bei km) "212.511" als Tausenderpunkt. */
    fun parseNum(raw: String, dotIsThousands: Boolean): Double? {
        var s = raw.replace("€", "").replace("EUR", "", ignoreCase = true)
            .replace("km", "", ignoreCase = true).replace("l", "").replace(" ", "").replace(" ", "").trim()
        if (s.isEmpty()) return null
        val lastComma = s.lastIndexOf(',')
        val lastDot = s.lastIndexOf('.')
        s = when {
            lastComma >= 0 && lastDot >= 0 ->
                if (lastComma > lastDot) s.replace(".", "").replace(',', '.') else s.replace(",", "")
            lastComma >= 0 -> s.replace(',', '.')
            lastDot >= 0 && dotIsThousands && Regex("""-?\d{1,3}(\.\d{3})+""").matches(s) -> s.replace(".", "")
            else -> s
        }
        return s.toDoubleOrNull()
    }

    private val dateFormats = listOf(
        "dd.MM.yyyy HH:mm:ss", "dd.MM.yyyy HH:mm", "dd.MM.yyyy", "dd.MM.yy",
        "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd",
        "MM/dd/yyyy", "dd/MM/yyyy",
    )

    private fun parseDate(date: String, time: String): Long? {
        val s = listOf(date, time).filter { it.isNotBlank() }.joinToString(" ").trim()
        if (s.isEmpty()) return null
        for (p in dateFormats) {
            val fmt = SimpleDateFormat(p, Locale.GERMANY).apply { isLenient = false }
            val pos = java.text.ParsePosition(0)
            val d = fmt.parse(s, pos)
            if (d != null && pos.index > 0) return d.time
        }
        return null
    }

    private fun isTruthy(v: String): Boolean {
        val s = v.trim().lowercase()
        return s.isNotEmpty() && s !in listOf("0", "nein", "no", "false", "-", "n")
    }

    fun mapBrand(raw: String): String {
        val b = raw.trim()
        if (b.isEmpty()) return ""
        return Options.brands.firstOrNull { o -> o.split("/").any { b.contains(it, ignoreCase = true) } } ?: b
    }

    private fun mapFuel(raw: String): String? {
        val s = raw.lowercase()
        return when {
            s.isBlank() -> null
            s.contains("hvo") -> "HVO100"
            s.contains("diesel") && (s.contains("premium") || s.contains("ultimate") || s.contains("v-power") || s.contains("excellium")) -> "Premium-Diesel"
            s.contains("diesel") -> "Diesel"
            s.contains("e10") -> "Super E10"
            s.contains("plus") || s.contains("98") || s.contains("100") || s.contains("ultimate") || s.contains("v-power") -> "Super Plus"
            s.contains("super") || s.contains("e5") || s.contains("95") || s.contains("benzin") || s.contains("gasoline") || s.contains("petrol") -> "Super E5"
            s.contains("lpg") || s.contains("autogas") -> "LPG"
            s.contains("cng") || s.contains("erdgas") -> "CNG"
            s.contains("strom") || s.contains("elect") || s.contains("kwh") -> "Strom"
            else -> raw.trim()
        }
    }

    private fun mapTires(raw: String): String {
        val s = raw.lowercase()
        return when {
            s.contains("winter") -> "Winterreifen"
            s.contains("ganz") || s.contains("all") -> "Ganzjahresreifen"
            s.contains("sommer") || s.contains("summer") -> "Sommerreifen"
            else -> ""
        }
    }

    private fun mapStyle(raw: String): String {
        val s = raw.lowercase()
        return when {
            s.isBlank() -> ""
            s.contains("spar") || s.contains("eco") || s.contains("moderat") || s.contains("ruhig") -> "Sparsam"
            s.contains("zügig") -> "Zügig"
            s.contains("schnell") || s.contains("fast") || s.contains("sport") || s.contains("dynam") -> "Sportlich"
            else -> "Normal"
        }
    }

    private fun mapRoute(raw: String): String {
        val s = raw.lowercase()
        val city = s.contains("stadt") || s.contains("city") || s.contains("urban")
        val land = s.contains("land") || s.contains("country") || s.contains("überland")
        val motorway = s.contains("autobahn") || s.contains("motorway") || s.contains("highway") || s.contains("freeway")
        return when (listOf(city, land, motorway).count { it }) {
            0 -> ""
            1 -> if (city) "Stadt" else if (land) "Landstraße" else "Autobahn"
            else -> "Gemischt"
        }
    }
}
