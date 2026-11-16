package de.spritbuch.app

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt

/** Spalten, die Spritmonitor als Zahlencode exportiert und die der Nutzer beim Import zuordnet. */
enum class CodeField(private val labelRes: Int, val options: List<String>) {
    FUEL(R.string.fuel_type, Options.fuelTypes),
    TIRES(R.string.tires, listOf("") + Options.tireTypes),
    STYLE(R.string.driving_style, listOf("") + Options.drivingStyles),
    ROUTE(R.string.route, listOf("") + Options.routeTypes);

    val label: String get() = str(labelRes)
}

data class CodeInfo(val raw: String, val count: Int, val suggestion: String)

data class ImportResult(
    val refuels: List<Refuel>,
    val skipped: Int,
    val missingColumns: List<String>,
    val codes: Map<CodeField, List<CodeInfo>> = emptyMap(),
    /** Einträge, deren Betrag in einer anderen Währung als der des Fahrzeugs vorliegt. */
    val rowCurrency: Map<String, String> = emptyMap(),
    val correctedPrices: Int = 0,
    val missingPrices: Int = 0,
    val derivedOdometers: Int = 0,
    val fileDuplicates: Int = 0,
) {
    val foreignCurrencies: Map<String, Int> get() = rowCurrency.values.groupingBy { it }.eachCount()

    /** Ersetzt Zahlencodes, rechnet Fremdwährungen um und setzt optional Verbraucher für alle Einträge. */
    fun finalize(
        mapping: Map<CodeField, Map<String, String>>,
        rates: Map<String, Double?>,
        extrasForAll: Set<String>,
    ): List<Refuel> = refuels.map { r ->
        fun m(f: CodeField, raw: String) = if (raw.isNotEmpty() && raw.all { it.isDigit() }) mapping[f]?.get(raw) ?: raw else raw
        var total = r.totalPrice
        var note = r.note
        val cur = rowCurrency[r.id]
        if (cur != null) {
            val rate = rates[cur]
            val original = str(R.string.original_amount, r.totalPrice.fmt(2) + " " + cur)
            if (rate != null && rate > 0) total = r.totalPrice / rate
            note = listOf(note, original).filter { it.isNotBlank() }.joinToString(" · ")
        }
        val extras = (r.extrasList() + extrasForAll).toSet()
        r.copy(
            fuelType = m(CodeField.FUEL, r.fuelType),
            tireType = m(CodeField.TIRES, r.tireType),
            drivingStyle = m(CodeField.STYLE, r.drivingStyle),
            routeType = m(CodeField.ROUTE, r.routeType),
            totalPrice = total,
            note = note,
            extras = Options.extras.filter { it in extras }.joinToString("|"),
        )
    }
}

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
        val cTrip = col("teil-km", "distanz", "tageskilometer", "trip", "distance", "gefahrene km", "km")
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
        val cObc = col("bc-verbrauch", "bordcomputer-verbrauch", "bordcomputer verbrauch", "obc consumption", "bc consumption")
        val cExtras = col("extras", "verbraucher", "zusatz", "attributes")

        val missing = buildList {
            if (cDate < 0) add(str(R.string.col_date))
            if (cOdo < 0 && cTrip < 0) add(str(R.string.odometer))
            if (cAmount < 0) add(str(R.string.amount))
        }
        if (missing.isNotEmpty()) return ImportResult(emptyList(), lines.size - headerIdx - 1, missing)

        val cCurrency = col("währung", "currency", "waehrung")
        val vehicleCur = normCurrency(vehicle.currency)

        val result = mutableListOf<Refuel>()
        val pendingTrip = mutableMapOf<String, Double>()
        val rowCurrency = mutableMapOf<String, String>()
        var skipped = 0
        var correctedPrices = 0
        var missingPrices = 0
        for (line in lines.drop(headerIdx + 1)) {
            val f = splitCsv(line, sep)
            fun v(i: Int) = if (i in f.indices) f[i].trim() else ""

            val date = parseDate(v(cDate), v(cTime))
            val amount = parseNum(v(cAmount), false)
            val trip = if (cTrip >= 0) parseNum(v(cTrip), false)?.takeIf { it > 0 } else null
            val odo = if (cOdo >= 0) parseNum(v(cOdo), true)?.takeIf { it > 0 } else null
            if (date == null || amount == null || amount <= 0 || (odo == null && trip == null)) {
                skipped++
                continue
            }
            val ppu = if (cPpu >= 0) parseNum(v(cPpu), false) else null
            val noteNum = v(cNote).takeIf { Regex("""\d+([.,]\d{1,2})?""").matches(it) }?.let { parseNum(it, false) }
            var total = (if (cTotal >= 0) parseNum(v(cTotal), false)?.takeIf { it > 0 } else null)
                ?: ppu?.let { it * amount } ?: noteNum ?: 0.0
            // Plausibilität: Literpreis versehentlich als Gesamtbetrag eingetragen (z. B. 2,17 für 65 l)
            if (total > 0 && amount >= 5 && total / amount < 0.3 && total in 0.5..5.0) {
                total *= amount
                correctedPrices++
            }
            if (total <= 0.0) missingPrices++

            val typeRaw = v(cType).lowercase()
            // Spritmonitor: 1 = Volltankung, 2 = Teilbetankung, 3 = Erstbetankung, 4 = ungültig
            val partial = if (typeIsPartialFlag) isTruthy(typeRaw)
            else typeRaw == "2" || typeRaw.contains("teil") || typeRaw.contains("partial") || typeRaw.contains("notfull")
            val missed = typeRaw == "3" || typeRaw == "4" || typeRaw.contains("erst") || typeRaw.contains("first") ||
                typeRaw.contains("ungültig") || typeRaw.contains("invalid") || typeRaw.contains("missed")

            val extras = mutableListOf<String>()
            extraCols.forEach { (name, i) -> if (isTruthy(v(i))) extras += name }
            val extrasText = v(cExtras).lowercase() + " " + v(cNote).lowercase()
            if (extrasText.contains("klima") || extrasText.contains("air con")) extras += "Klimaanlage"
            if (extrasText.contains("anhänger") || extrasText.contains("trailer")) extras += "Anhänger"
            if (extrasText.contains("standheizung")) extras += "Standheizung"
            if (extrasText.contains("dachbox") || extrasText.contains("fahrradträger")) extras += "Dachbox/Fahrradträger"

            val r = Refuel(
                vehicleId = vehicle.id,
                timestamp = date,
                odometer = odo?.roundToInt() ?: 0,
                amount = amount,
                totalPrice = total,
                fullTank = !partial,
                missedPrevious = missed,
                fuelType = v(cFuel).let { if (isCode(it)) it else mapFuel(it) ?: vehicle.defaultFuelType },
                stationBrand = mapBrand(v(cStation)),
                stationAddress = v(cLocation),
                latitude = parseNum(v(cLat), false)?.takeIf { it != 0.0 },
                longitude = parseNum(v(cLon), false)?.takeIf { it != 0.0 },
                extras = Options.extras.filter { it in extras }.joinToString("|"),
                drivingStyle = v(cStyle).let { if (isCode(it)) it else mapStyle(it) },
                routeType = v(cRoute).let { if (isCode(it)) it else mapRoute(it) },
                tireType = v(cTires).let { if (isCode(it)) it else mapTires(it) },
                note = if (noteNum != null && total == noteNum) "" else v(cNote),
                obcConsumption = if (cObc >= 0) parseNum(v(cObc), false)?.takeIf { it > 0 }?.let {
                    if (vehicle.consumptionFormat == "perUnit") 100.0 / it else it
                } else null,
            )
            if (odo == null && trip != null) pendingTrip[r.id] = trip
            val cur = normCurrency(v(cCurrency))
            if (cur.isNotEmpty() && cur != vehicleCur) rowCurrency[r.id] = cur
            result += r
        }

        // Fehlende Kilometerstände aus der Teilstrecke ableiten (chronologisch)
        var prevOdo = vehicle.initialOdometer
        val ordered = result.sortedWith(compareBy({ it.timestamp }, { if (it.odometer > 0) it.odometer else Int.MAX_VALUE }))
            .map { r ->
                val t = pendingTrip[r.id]
                val nr = if (t != null) r.copy(odometer = prevOdo + t.roundToInt()) else r
                prevOdo = maxOf(prevOdo, nr.odometer)
                nr
            }
        // Doppelte Zeilen in der Datei entfernen
        val unique = ordered.distinctBy { it.odometer to (it.amount * 100).roundToInt() }
        val fileDuplicates = ordered.size - unique.size

        fun codes(get: (Refuel) -> String, suggest: (String) -> String): List<CodeInfo> =
            unique.map(get).filter { isCode(it) }.groupingBy { it }.eachCount()
                .toSortedMap(compareBy { it.toIntOrNull() ?: 0 })
                .map { (raw, n) -> CodeInfo(raw, n, suggest(raw)) }

        // Bedeutungen abgeglichen mit der Spritmonitor-Detailansicht (Symbole je Eintrag)
        val codeMap = mapOf(
            CodeField.FUEL to codes({ it.fuelType }) {
                when (it) { "1", "4" -> "Diesel"; else -> vehicle.defaultFuelType }
            },
            CodeField.TIRES to codes({ it.tireType }) {
                when (it) { "1" -> "Sommerreifen"; "2" -> "Winterreifen"; "3" -> "Ganzjahresreifen"; else -> "" }
            },
            CodeField.STYLE to codes({ it.drivingStyle }) {
                when (it) { "1" -> "Sparsam"; "2" -> "Normal"; "3" -> "Sportlich"; else -> "" }
            },
            CodeField.ROUTE to codes({ it.routeType }) { routeFromBits(it.toIntOrNull() ?: 0) },
        ).filterValues { it.isNotEmpty() }

        return ImportResult(
            refuels = unique,
            skipped = skipped,
            missingColumns = emptyList(),
            codes = codeMap,
            rowCurrency = rowCurrency.filterKeys { id -> unique.any { it.id == id } },
            correctedPrices = correctedPrices,
            missingPrices = missingPrices,
            derivedOdometers = pendingTrip.size,
            fileDuplicates = fileDuplicates,
        )
    }

    /** Spritmonitor-Strecken als Bitmaske: 2 = Autobahn, 4 = Stadt, 8 = Landstraße (Reihenfolge der Symbole). */
    fun routeFromBits(bits: Int): String {
        val parts = buildList {
            if (bits and 2 != 0) add("Autobahn")
            if (bits and 4 != 0) add("Stadt")
            if (bits and 8 != 0) add("Landstraße")
        }
        return when (parts.size) { 0 -> ""; 1 -> parts[0]; else -> "Gemischt" }
    }

    fun normCurrency(raw: String): String = when (raw.trim().uppercase()) {
        "", "-" -> ""
        "EUR", "€", "EURO" -> "€"
        "GBP", "£" -> "£"
        "USD", "\$" -> "\$"
        else -> raw.trim().uppercase()
    }

    /** Vorschlag für Umrechnungskurse (1 Fahrzeugwährung = x Fremdwährung). DKK ist fest an den Euro gekoppelt. */
    fun suggestedRate(vehicleCur: String, foreign: String): String =
        if (normCurrency(vehicleCur) == "€" && foreign == "DKK") "7,46" else ""

    private fun isCode(s: String) = s.isNotEmpty() && s.all { it.isDigit() }

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
        var s = raw.filter { it.isDigit() || it == '.' || it == ',' || it == '-' }
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

    fun parseDate(date: String, time: String): Long? {
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
        return Options.matchBrand(raw)
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

/**
 * Import von Service-Einträgen aus CSV.
 * Spalten (Kopfzeile, Reihenfolge egal): Datum; Km-Stand; Titel; Kategorie; Kosten; Werkstatt; Notiz.
 * Mehrzeilige Notizen in Anführungszeichen werden unterstützt.
 */
object ServiceImport {

    /** CSV nach RFC 4180 (Zeilenumbrüche in Anführungszeichen erlaubt). */
    fun parseCsv(text: String, sep: Char): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                quoted && ch == '"' && i + 1 < text.length && text[i + 1] == '"' -> { sb.append('"'); i++ }
                ch == '"' -> quoted = !quoted
                !quoted && ch == sep -> { row += sb.toString(); sb.clear() }
                !quoted && (ch == '\n' || ch == '\r') -> {
                    if (ch == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    row += sb.toString(); sb.clear()
                    if (row.any { it.isNotBlank() }) rows += row
                    row = mutableListOf()
                }
                else -> sb.append(ch)
            }
            i++
        }
        row += sb.toString()
        if (row.any { it.isNotBlank() }) rows += row
        return rows
    }

    data class Result(val entries: List<ServiceEntry>, val skipped: Int, val error: String?)

    fun parse(text: String, vehicle: Vehicle): Result {
        val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() } ?: return Result(emptyList(), 0, "Datei ist leer")
        val sep = listOf(';', '\t', ',').maxBy { c -> firstLine.count { it == c } }
        val rows = parseCsv(text, sep)
        if (rows.size < 2) return Result(emptyList(), 0, "Keine Einträge gefunden")
        val header = rows[0].map { it.trim().lowercase() }
        fun col(vararg c: String) = header.indexOfFirst { h -> c.any { h == it || h.contains(it) } }
        val cDate = col("datum", "date")
        val cOdo = col("km-stand", "kilometerstand", "km", "odometer")
        val cTitle = col("titel", "bezeichnung", "title", "arbeit")
        val cCat = col("kategorie", "category")
        val cCost = col("kosten", "betrag", "cost", "preis")
        val cShop = col("werkstatt", "workshop", "garage")
        val cNote = col("notiz", "bemerkung", "note", "beschreibung")
        if (cDate < 0 || cTitle < 0) return Result(emptyList(), rows.size - 1, "Spalten „Datum“ und „Titel“ werden benötigt")

        var skipped = 0
        val list = rows.drop(1).mapNotNull { f ->
            fun v(i: Int) = if (i in f.indices) f[i].trim() else ""
            val date = CsvImport.parseDate(v(cDate), "")
            val title = v(cTitle)
            if (date == null || title.isBlank()) { skipped++; return@mapNotNull null }
            val cat = v(cCat).let { c -> Options.serviceCategories.firstOrNull { it.equals(c, true) } ?: if (c.isBlank()) "Sonstiges" else c }
            ServiceEntry(
                vehicleId = vehicle.id,
                timestamp = date,
                odometer = CsvImport.parseNum(v(cOdo), true)?.toInt() ?: 0,
                title = title,
                category = cat,
                cost = CsvImport.parseNum(v(cCost), false) ?: 0.0,
                workshop = v(cShop),
                note = v(cNote),
            )
        }
        return Result(list, skipped, null)
    }
}
