package de.spritbuch.app

import java.util.Calendar

/** Ergebnis der Kassenzettel-Erkennung; nicht erkannte Werte bleiben null. */
data class ReceiptData(
    val amount: Double? = null,
    val pricePerUnit: Double? = null,
    val total: Double? = null,
    /** Datum als (Jahr, Monat 1–12, Tag). */
    val date: Triple<Int, Int, Int>? = null,
    /** Schlüssel aus Options.fuelTypes. */
    val fuelType: String? = null,
    /** Erkannte Marke der Tankstelle bzw. des Ladeanbieters (Kopf des Belegs). */
    val station: String? = null,
    /** Adresse aus dem Kopf des Belegs, z. B. „Husumer Str. 30, 24941 Flensburg“. */
    val address: String? = null,
)

/**
 * Liest Menge, Preis pro Liter, Gesamtbetrag, Datum und Sorte aus dem erkannten Text eines Tankbelegs.
 * Reines Kotlin ohne Android-Abhängigkeiten, damit es sich testen lässt.
 */
object ReceiptParser {
    /**
     * Währung auf dem Bon (ISO-Code), soweit eindeutig erkennbar: ausgeschriebene Codes und typische
     * Zeichen (£, zł, Kč, Lei). „kr“ allein ist mehrdeutig (DKK/SEK/NOK/ISK) und wird nicht ausgewertet.
     */
    fun currency(raw: String): String? {
        val t = raw.uppercase()
        // „RON“ steht auf Bons meist für die Oktanzahl – Lei nur über „LEI“ erkennen
        val codes = Fx.european.filter { it != "EUR" && it != "RON" } + "EUR"
        val counts = codes.associateWith { c -> Regex("""(?<![A-Z])$c(?![A-Z])""").findAll(t).count() }.filterValues { it > 0 }
        counts.maxByOrNull { it.value }?.let { return it.key }
        return when {
            raw.contains('£') -> "GBP"
            Regex("""\d\s*ZŁ|ZŁ\s*\d|PLN""").containsMatchIn(t) -> "PLN"
            Regex("""\d\s*KČ|KČ\s*\d""").containsMatchIn(t) -> "CZK"
            Regex("""\d\s*LEI\b""").containsMatchIn(t) -> "RON"
            raw.contains('€') -> "EUR"
            else -> null
        }
    }

    private const val NUM = """(\d{1,4}(?:[.,]\d{3})*[.,]\d{2,3})"""
    private val amountRe = Regex("""$NUM\s*(?:l|ltr|liter|litre|lt)\b""", RegexOption.IGNORE_CASE)
    private val amountKeyRe = Regex("""(?:menge|mængde|volume|quantity)\D{0,12}$NUM""", RegexOption.IGNORE_CASE)
    private val ppuRe = Regex("""$NUM\s*(?:€|eur|euro|kr\.?|dkk|chf|£|\$)?\s*/\s*(?:l|ltr|liter|litre)\b""", RegexOption.IGNORE_CASE)
    private val ppuKeyRe = Regex("""(?:€|eur|kr\.?|dkk)\s*/\s*(?:l|ltr|liter)\D{0,6}$NUM""", RegexOption.IGNORE_CASE)
    private val totalKeyRe = Regex(
        """(summe|gesamt|total|betrag|zu zahlen|zahlbetrag|i alt|at betale|amount|bar|ec[- ]?karte|girocard|kartenzahlung|visa|mastercard|dankort)""",
        RegexOption.IGNORE_CASE,
    )
    private val kwhRe = Regex("""$NUM\s*kwh\b""", RegexOption.IGNORE_CASE)
    private val kwhPriceRe = Regex("""$NUM\s*(?:€|eur|euro|kr\.?|dkk|chf)?\s*/\s*kwh\b""", RegexOption.IGNORE_CASE)
    private val moneyRe = Regex("""(\d{1,5}(?:[.,]\d{3})*[.,]\d{2})(?!\d)""")
    private val dateRe = Regex("""\b(\d{1,2})[./-](\d{1,2})[./-](\d{4}|\d{2})\b""")

    fun num(s: String): Double? {
        val lc = s.lastIndexOf(',')
        val ld = s.lastIndexOf('.')
        val norm = when {
            lc >= 0 && ld >= 0 -> if (lc > ld) s.replace(".", "").replace(',', '.') else s.replace(",", "")
            lc >= 0 -> s.replace(',', '.')
            else -> s
        }
        return norm.toDoubleOrNull()
    }

    /**
     * Typische OCR-Verwechsler in Zahlen korrigieren (O→0, I→1, S→5, B→8) – nur innerhalb von
     * Zeichenfolgen, die bereits Ziffern enthalten. „l“ bleibt, das ist meist die Einheit Liter.
     */
    private fun cleanDigits(text: String): String = Regex("""[\dOoISB][\dOoISB.,]*""").replace(text) { m ->
        val t = m.value
        if (t.none { it.isDigit() } || t.count { it.isLetter() } > t.count { it.isDigit() } + 2) t
        else t.map { c -> when (c) { 'O', 'o' -> '0'; 'I' -> '1'; 'S' -> '5'; 'B' -> '8'; else -> c } }.joinToString("")
    }

    fun parse(
        raw: String,
        nowYear: Int = Calendar.getInstance().get(Calendar.YEAR),
        /** Vom Nutzer schon verwendete Betreiber (z. B. kleine freie Tankstellen). */
        ownStations: List<String> = emptyList(),
    ): ReceiptData {
        val text = cleanDigits(raw)
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }

        // Ladequittung: Energie in kWh und Preis pro kWh
        val kwh = kwhRe.findAll(text).mapNotNull { num(it.groupValues[1]) }.filter { it in 0.5..250.0 }.toList()
        val kwhPrice = kwhPriceRe.findAll(text).mapNotNull { num(it.groupValues[1]) }.filter { it in 0.05..15.0 }.toList()
        val isCharge = kwh.isNotEmpty() || kwhPrice.isNotEmpty()

        val amounts = if (isCharge) kwh else ((amountRe.findAll(text).map { it.groupValues[1] } + amountKeyRe.findAll(text).map { it.groupValues[1] })
            .mapNotNull { num(it) }.filter { it in 1.0..500.0 }.toList())
        val ppus = if (isCharge) kwhPrice else ((ppuRe.findAll(text).map { it.groupValues[1] } + ppuKeyRe.findAll(text).map { it.groupValues[1] })
            .mapNotNull { num(it) }.filter { it in 0.3..30.0 }.toList())
        val totalsOnKeyLines = lines.filter { totalKeyRe.containsMatchIn(it) }
            .flatMap { l -> moneyRe.findAll(l).mapNotNull { num(it.groupValues[1]) }.toList() }
            .filter { it in 1.0..5000.0 }
        val allMoney = moneyRe.findAll(text).mapNotNull { num(it.groupValues[1]) }.filter { it in 1.0..5000.0 }.toList()

        var amount = amounts.firstOrNull()
        var ppu = ppus.firstOrNull()
        var total: Double? = null

        // Gesamtbetrag: passt er zu Menge × Preis, ist er sicher
        if (amount != null && ppu != null) {
            val expected = amount * ppu
            total = (totalsOnKeyLines + allMoney).minByOrNull { kotlin.math.abs(it - expected) }
                ?.takeIf { kotlin.math.abs(it - expected) <= maxOf(0.05, expected * 0.01) }
                ?: (Math.round(expected * 100) / 100.0)
        } else {
            total = totalsOnKeyLines.maxOrNull()
            if (amount == null && ppu != null && total != null) amount = Math.round(total / ppu * 100) / 100.0
            if (ppu == null && amount != null && total != null && amount > 0) ppu = Math.round(total / amount * 1000) / 1000.0
        }

        val date = dateRe.findAll(text).mapNotNull { m ->
            val d = m.groupValues[1].toInt()
            val mo = m.groupValues[2].toInt()
            var y = m.groupValues[3].toInt()
            if (y < 100) y += 2000
            if (d in 1..31 && mo in 1..12 && y in (nowYear - 1)..nowYear) Triple(y, mo, d) else null
        }.firstOrNull()

        return ReceiptData(
            amount, ppu, total, date, if (isCharge) ELECTRIC else fuelType(text),
            station(lines, isCharge, ownStations), address(lines),
        )
    }

    /** Marke aus den ersten Zeilen (dort steht sie auf dem Bon); nur bekannte Marken, ganze Wörter. */
    fun station(lines: List<String>, charging: Boolean = false, own: List<String> = emptyList()): String? {
        val head = lines.take(8).joinToString("\n").lowercase()
        val generic = setOf("tankstelle", "tankstation", "station", "tank", "freie tankstelle", "zuhause", "arbeitgeber", "service")
        val known = if (charging) Options.chargeNetworks.filter { it != "Zuhause" && it != "Arbeitgeber" }
        else Options.brands.filter { it != "Freie Tankstelle" }
        // Eigene Einträge (z. B. „Winkler“, „Wiking“) zählen mit, allgemeine Wörter nicht
        val names = (known + own.map { it.trim() }.filter { it.length >= 3 && it.lowercase() !in generic })
            .distinctBy { it.lowercase() }
        fun hit(alias: String) =
            Regex("(^|[^\\p{L}\\p{N}])" + Regex.escape(alias.lowercase()) + "($|[^\\p{L}\\p{N}])").containsMatchIn(head)
        // Längere Namen zuerst, damit z. B. „Shell Recharge“ vor „Shell“ greift
        return names.sortedByDescending { it.length }.firstOrNull { n ->
            (n.split("/") + if (n == "TotalEnergies") listOf("total") else emptyList()).any { hit(it) }
        }
    }

    /** Straße + PLZ/Ort aus dem Kopf (5-stellige PLZ wie in DE, 4-stellige wie in DK). */
    fun address(lines: List<String>): String? {
        val head = lines.take(10)
        val idx = head.indexOfFirst { Regex("""^(?:[A-Z]{1,2}-)?\d{4,5}\s+\p{L}[\p{L} .\-]+$""").matches(it.trim()) }
        if (idx < 0) return null
        val city = head[idx].trim().replace(Regex("""\s+"""), " ")
        val street = head.getOrNull(idx - 1)?.trim()?.takeIf {
            Regex("""\p{L}.*\d""").containsMatchIn(it) && it.length in 4..60 && !Regex("""\d{2}[.:]\d{2}""").containsMatchIn(it)
        }
        return listOfNotNull(street, city).joinToString(", ")
    }

    fun fuelType(text: String): String? {
        val t = text.lowercase()
        return when {
            Regex("""\bhvo""").containsMatchIn(t) -> "HVO100"
            Regex("""diesel""").containsMatchIn(t) && Regex("""ultimate|v-power|premium|excellium""").containsMatchIn(t) -> "Premium-Diesel"
            Regex("""diesel""").containsMatchIn(t) -> "Diesel"
            Regex("""\b(lpg|autogas)\b""").containsMatchIn(t) -> "LPG"
            Regex("""\bcng\b|erdgas""").containsMatchIn(t) -> "CNG"
            // Markenkraftstoffe mit 100+ Oktan (V-Power, Ultimate, Excellium …)
            Regex("""ultimate|v-power|excellium|\b10[02]\s*oktan|super\s*10[02]\b|blyfri\s*100""").containsMatchIn(t) -> "Premium"
            Regex("""super\s*plus|super\s*98\b""").containsMatchIn(t) -> "Super Plus"
            Regex("""\be\s?10\b|blyfri\s*95""").containsMatchIn(t) -> "Super E10"
            Regex("""\be\s?5\b|super\s*95|super\b""").containsMatchIn(t) -> "Super E5"
            else -> null
        }
    }

    /**
     * Alle Zahlen im erkannten Text (für die manuelle Zuordnung), ohne Dubletten und Uhrzeiten,
     * größere bzw. dezimale Zahlen zuerst.
     */
    fun numbers(raw: String, max: Int = 30): List<Pair<String, Double>> {
        val text = cleanDigits(raw)
        val found = Regex("""(?<![\d:])\d{1,3}(?:[. ]\d{3})+(?:,\d{1,3})?(?![\d:])|(?<![\d:])\d+(?:[.,]\d{1,3})?(?![\d:])""")
            .findAll(text)
            .map { it.value.trim() }
            .mapNotNull { s -> num(s.replace(" ", "").let { if (Regex("""^\d{1,3}(\.\d{3})+$""").matches(it)) it.replace(".", "") else it })?.let { s to it } }
            .filter { (s, v) -> v > 0 && (s.any { it == ',' || it == '.' } || v >= 10) }
            .distinctBy { it.second }
            .toList()
        return found.sortedWith(compareByDescending<Pair<String, Double>> { (s, _) -> s.any { it == ',' || it == '.' } }.thenByDescending { it.second })
            .take(max)
    }

}
