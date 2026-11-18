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
)

/**
 * Liest Menge, Preis pro Liter, Gesamtbetrag, Datum und Sorte aus dem erkannten Text eines Tankbelegs.
 * Reines Kotlin ohne Android-Abhängigkeiten, damit es sich testen lässt.
 */
object ReceiptParser {
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

    fun parse(raw: String, nowYear: Int = Calendar.getInstance().get(Calendar.YEAR)): ReceiptData {
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

        return ReceiptData(amount, ppu, total, date, if (isCharge) ELECTRIC else fuelType(text))
    }

    fun fuelType(text: String): String? {
        val t = text.lowercase()
        return when {
            Regex("""\bhvo""").containsMatchIn(t) -> "HVO100"
            Regex("""diesel""").containsMatchIn(t) -> "Diesel"
            Regex("""\b(lpg|autogas)\b""").containsMatchIn(t) -> "LPG"
            Regex("""\bcng\b|erdgas""").containsMatchIn(t) -> "CNG"
            Regex("""super\s*plus|ultimate|v-power|\b100\s*oktan|blyfri\s*100""").containsMatchIn(t) -> "Super Plus"
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
        val found = Regex("""(?<![\d:])\d{1,3}(?:[.\s]\d{3})+(?:,\d{1,3})?(?![\d:])|(?<![\d:])\d+(?:[.,]\d{1,3})?(?![\d:])""")
            .findAll(text)
            .map { it.value.trim() }
            .mapNotNull { s -> num(s.replace(" ", "").let { if (Regex("""^\d{1,3}(\.\d{3})+$""").matches(it)) it.replace(".", "") else it })?.let { s to it } }
            .filter { (s, v) -> v > 0 && (s.any { it == ',' || it == '.' } || v >= 10) }
            .distinctBy { it.second }
            .toList()
        return found.sortedWith(compareByDescending<Pair<String, Double>> { (s, _) -> s.any { it == ',' || it == '.' } }.thenByDescending { it.second })
            .take(max)
    }

    /**
     * Kilometerstand aus einem Tachofoto: längste Ziffernfolge (4–7 Stellen), bevorzugt
     * knapp über dem bisherigen Stand.
     */
    fun odometer(raw: String, previous: Int?): Int? {
        val candidates = Regex("""\d[\d .]{2,9}\d""").findAll(cleanDigits(raw))
            .map { it.value.filter { c -> c.isDigit() } }
            .filter { it.length in 4..7 }
            .mapNotNull { it.toIntOrNull() }
            .toList()
        if (candidates.isEmpty()) return null
        if (previous != null && previous > 0) {
            candidates.filter { it >= previous && it - previous <= 5000 }.minOrNull()?.let { return it }
        }
        return candidates.maxByOrNull { it.toString().length * 10_000_000L + it }
    }
}
