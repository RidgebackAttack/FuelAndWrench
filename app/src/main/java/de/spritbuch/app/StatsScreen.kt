package de.spritbuch.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.IconButton
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date

private data class YearRow(
    val year: Int,
    val fuel: Double,
    val service: Double,
    val other: Double,
    val distance: Int,
    val refuels: List<Refuel>,
    val services: List<ServiceEntry>,
    val expenses: List<Expense>,
) {
    val total get() = fuel + service + other
}

private data class MonthBar(val label: String, val fuel: Double, val service: Double, val other: Double) {
    val total get() = fuel + service + other
}

private fun cal(ts: Long) = Calendar.getInstance().apply { timeInMillis = ts }
private fun monthKey(ts: Long) = cal(ts).let { it.get(Calendar.YEAR) * 12 + it.get(Calendar.MONTH) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(nav: NavController, vehicleId: String) {
    val ctx = LocalContext.current
    val repo = ctx.repo
    val vehicle by repo.vehicle(vehicleId).collectAsStateWithLifecycle(null)
    val refuels by repo.refuels(vehicleId).collectAsStateWithLifecycle(emptyList())
    val services by repo.services(vehicleId).collectAsStateWithLifecycle(emptyList())
    val expenses by repo.expenses(vehicleId).collectAsStateWithLifecycle(emptyList())
    val v = vehicle
    val stats = remember(refuels, vehicle?.batteryCapacity) { computeStats(refuels, vehicle?.batteryCapacity) }
    // Feste, klar unterscheidbare Farben (die Theme-Farben liegen je nach Farbschema zu dicht beieinander)
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val fuelColor = if (dark) Color(0xFF60A5FA) else Color(0xFF2563EB)
    val serviceColor = if (dark) Color(0xFFFBBF24) else Color(0xFFD97706)
    val otherColor = if (dark) Color(0xFF34D399) else Color(0xFF059669)

    val exportScope = rememberCoroutineScope()
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(str(R.string.stats_title, v?.name ?: "")) },
            navigationIcon = { BackButton(nav) },
            actions = {
                v?.let { vv ->
                    IconButton(onClick = { exportScope.launch { runCatching { CsvShare.vehicle(ctx, vv) } } }) {
                        Icon(Icons.Default.Share, contentDescription = str(R.string.csv_export))
                    }
                }
            },
        )
    }) { pad ->
        if (v == null) return@Scaffold
        val cur = v.currency
        val du = v.distanceUnit

        // Gefahrene Strecke seit der jeweils vorherigen Betankung (zählt auch über den Jahreswechsel)
        val distances = remember(refuels) { distancesSincePrevious(refuels) }
        // Kosten je Jahr mit allen Einträgen für die aufklappbaren Details
        val years = remember(refuels, services, expenses, distances) {
            val ys = (refuels.map { cal(it.timestamp).get(Calendar.YEAR) } +
                services.map { cal(it.timestamp).get(Calendar.YEAR) } +
                expenses.map { cal(it.timestamp).get(Calendar.YEAR) }).distinct().sortedDescending()
            ys.map { y ->
                val r = refuels.filter { cal(it.timestamp).get(Calendar.YEAR) == y }
                val sv = services.filter { cal(it.timestamp).get(Calendar.YEAR) == y }
                val ex = expenses.filter { cal(it.timestamp).get(Calendar.YEAR) == y }
                YearRow(
                    y, r.sumOf { it.totalPrice }, sv.sumOf { it.cost }, ex.sumOf { it.amount },
                    r.sumOf { distances[it.id] ?: 0 }, r, sv, ex,
                )
            }
        }
        var openYears by remember { mutableStateOf(setOf<Int>()) }
        val range by ChartRange.state.collectAsStateWithLifecycle()
        // Letzte 12 Monate
        val months = remember(refuels, services, expenses) {
            val nowKey = monthKey(now())
            val fmt = SimpleDateFormat("MMM", appLocale())
            (11 downTo 0).map { back ->
                val key = nowKey - back
                val c = Calendar.getInstance().apply { clear(); set(key / 12, key % 12, 1) }
                MonthBar(
                    fmt.format(Date(c.timeInMillis)).take(3),
                    refuels.filter { monthKey(it.timestamp) == key }.sumOf { it.totalPrice },
                    services.filter { monthKey(it.timestamp) == key }.sumOf { it.cost },
                    expenses.filter { monthKey(it.timestamp) == key }.sumOf { it.amount },
                )
            }
        }
        val prices = remember(refuels, range) {
            range.apply(refuels.filter { it.amount > 0 && it.totalPrice > 0 && Units.isLiquid(it.fuelType) }.sortedBy { it.timestamp }) { it.timestamp }
                .map { it.pricePerUnit }
        }
        fun groupAvg(key: (Refuel) -> String) = refuels.filter { it.isCharge == (v.defaultFuelType == ELECTRIC) }.mapNotNull { r -> stats.perRefuel[r.id]?.let { key(r) to it } }
            .filter { it.first.isNotBlank() }
            .groupBy({ it.first }, { it.second })
            .map { (k, list) -> Triple(k, list.average(), list.size) }
            .sortedBy { it.second }
        val kwhPrices = remember(refuels, range) {
            range.apply(refuels.filter { it.isCharge && it.amount > 0 && it.totalPrice > 0 }.sortedBy { it.timestamp }) { it.timestamp }
                .map { it.pricePerUnit }
        }
        // Anteil der geladenen Energie je Ladeort
        val chargeShare = remember(refuels) {
            val charges = refuels.filter { it.isCharge && it.amount > 0 }
            val sum = charges.sumOf { it.amount }
            charges.groupBy { it.chargeLocation.ifBlank { "?" } }
                .map { (k, l) -> Triple(k, l.sumOf { it.amount } / sum, l.sumOf { it.totalPrice } / l.sumOf { it.amount }) }
                .sortedByDescending { it.second }
        }
        val byTires = remember(refuels) { groupAvg { it.tireType } }
        val byRoute = remember(refuels) { groupAvg { it.routeType } }
        val byStyle = remember(refuels) { groupAvg { it.drivingStyle } }

        LazyColumn(
            modifier = Modifier.padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(str(R.string.stats_months), style = MaterialTheme.typography.titleSmall)
                        val max = months.maxOf { it.total }.takeIf { it > 0 } ?: 1.0
                        Canvas(Modifier.fillMaxWidth().height(140.dp)) {
                            val slot = size.width / months.size
                            val bw = slot * 0.62f
                            months.forEachIndexed { i, m ->
                                var top = size.height
                                val x = i * slot + (slot - bw) / 2
                                for ((value, color) in listOf(m.fuel to fuelColor, m.service to serviceColor, m.other to otherColor)) {
                                    if (value <= 0) continue
                                    val h = (value / max * size.height).toFloat()
                                    top -= h
                                    // kleiner Abstand zwischen den Segmenten
                                    val gap = if (h > 4f) 1.5f else 0f
                                    drawRect(color, Offset(x, top + gap), Size(bw, h - gap))
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth()) {
                            months.forEach { m ->
                                Text(m.label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f), maxLines = 1)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Legend(fuelColor, str(R.string.legend_fuel) + " " + months.sumOf { it.fuel }.fmt(0))
                            Legend(serviceColor, str(R.string.tab_service) + " " + months.sumOf { it.service }.fmt(0))
                            Legend(otherColor, str(R.string.legend_other) + " " + months.sumOf { it.other }.fmt(0))
                        }
                        Text(
                            str(R.string.stats_12m_total, "${months.sumOf { it.total }.fmt(0)} $cur"),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(str(R.string.stats_years), style = MaterialTheme.typography.titleSmall)
                        if (years.isEmpty()) Text("–") else Text(str(R.string.stats_tap_details), style = MaterialTheme.typography.bodySmall)
                        years.forEachIndexed { i, y ->
                            if (i > 0) HorizontalDivider()
                            val open = y.year in openYears
                            Column(
                                Modifier.fillMaxWidth().clickable { openYears = if (open) openYears - y.year else openYears + y.year },
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(y.year.toString(), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                    Text("${y.total.fmt(0)} $cur", fontWeight = FontWeight.SemiBold)
                                    Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
                                }
                                Text(
                                    listOfNotNull(
                                        str(R.string.legend_fuel) + " " + y.fuel.fmt(0),
                                        str(R.string.tab_service) + " " + y.service.fmt(0),
                                        if (y.other > 0) str(R.string.legend_other) + " " + y.other.fmt(0) else null,
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                if (y.distance > 0) {
                                    Text(
                                        y.distance.dist(du) + " · " + str(R.string.stat_total_per100, du) + ": " +
                                            (y.total / y.distance * 100).fmt() + " " + cur,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                            AnimatedVisibility(open) { YearDetails(v, y, stats, distances) }
                        }
                    }
                }
            }
            // Was kostet das Fahrzeug insgesamt – pro km und pro Monat seit dem ersten Eintrag
            item {
                val first = (refuels.map { it.timestamp } + services.map { it.timestamp } + expenses.map { it.timestamp }).minOrNull()
                TotalCostCard(v, years.sumOf { it.fuel }, years.sumOf { it.service }, years.sumOf { it.other }, years.sumOf { it.distance }, first)
            }
            // Zeitraum für Verbrauchs- und Preisverläufe (derselbe wie in der Übersicht)
            item { ChartRangeChips() }
            // Verbrauchsverlauf (bei Plug-in-Hybriden getrennt für Kraftstoff und Strom), chronologisch nach Kilometerstand
            val consumption = listOf(false, true).map { charge ->
                charge to range.apply(
                    refuels.filter { it.isCharge == charge && stats.perRefuel[it.id] != null }
                        .sortedWith(compareBy({ it.odometer }, { it.timestamp })),
                ) { it.timestamp }.mapNotNull { stats.perRefuel[it.id] }
            }.filter { it.second.size >= 2 }
            consumption.forEach { (charge, values) ->
                item {
                    val fuel = if (charge) ELECTRIC else v.defaultFuelType.takeIf { it != ELECTRIC } ?: "Super E10"
                    ConsumptionChart(
                        values,
                        str(R.string.chart_title, plural(R.plurals.pl_full_refuels, values.size)) + " · " + range.label(),
                    ) { Units.consumption(v, fuel, it) }
                }
            }
            if (consumption.isEmpty() && prices.size < 2 && kwhPrices.size < 2) item {
                Text(str(R.string.range_too_few), style = MaterialTheme.typography.bodySmall)
            }
            if (prices.size >= 2) item {
                val unit = Units.amount(v, if (v.defaultFuelType == ELECTRIC) "Super E10" else v.defaultFuelType)
                ConsumptionChart(prices, str(R.string.stats_price_trend, unit)) { "${it.fmt(3)} $cur" }
            }
            if (kwhPrices.size >= 2) item {
                ConsumptionChart(kwhPrices, str(R.string.stats_price_trend, "kWh")) { "${it.fmt(3)} $cur" }
            }
            if (chargeShare.size >= 2) item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(str(R.string.stats_charge_share), style = MaterialTheme.typography.titleSmall)
                        chargeShare.forEach { (loc, share, price) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(if (loc == "?") str(R.string.not_specified) else lbl(loc), modifier = Modifier.weight(1f))
                                Text("${(share * 100).fmt(0)} % · ${price.fmt(2)} $cur/kWh", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
            listOf(
                R.string.stats_by_tires to byTires,
                R.string.stats_by_route to byRoute,
                R.string.stats_by_style to byStyle,
            ).filter { it.second.size >= 2 }.forEach { (title, rows) ->
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(str(title), style = MaterialTheme.typography.titleSmall)
                            val best = rows.first().second
                            rows.forEach { (key, avg, n) ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("${lbl(key)} ($n×)", modifier = Modifier.weight(1f))
                                    Text(Units.consumption(v, v.defaultFuelType, avg), fontWeight = FontWeight.SemiBold)
                                }
                                val diff = (avg / best - 1) * 100
                                if (diff > 0.5) Text("+${diff.fmt(1)} %", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
            // Ölnachfüllungen je Jahr mit Verbrauch pro 1000 km
            val oilYears = OilStats.perYear(services, refuels)
            if (oilYears.isNotEmpty()) item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(str(R.string.oil_stats_title), style = MaterialTheme.typography.titleSmall)
                        oilYears.forEach { y ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${y.year} · ${y.amount.fmt(2)} ${OilStats.unit(v)} (${y.count}×)", modifier = Modifier.weight(1f))
                                Text(y.per1000?.let { OilStats.rateText(v, it) } ?: "–", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
            val oilRates = oilYears.reversed().mapNotNull { it.per1000 }
            if (oilRates.size >= 2) item {
                ConsumptionChart(oilRates, str(R.string.oil_chart_title)) { OilStats.rateText(v, it) }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun Legend(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.labelSmall)
    }
}

/** Eine Zeile „Bezeichnung … Wert“ in den Jahresdetails. */
@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

/** Aufgeklapptes Jahr: Kennzahlen, Monate, Service-Einträge und sonstige Kosten nach Kategorie. */
@Composable
private fun YearDetails(v: Vehicle, y: YearRow, stats: Stats, distances: Map<String, Int>) {
    val cur = v.currency
    val du = v.distanceUnit
    val ev = v.defaultFuelType == ELECTRIC
    val unit = Units.amount(v, v.defaultFuelType)
    val main = y.refuels.filter { it.isCharge == ev }
    val priced = main.filter { it.amount > 0 && it.totalPrice > 0 }
    val per = main.mapNotNull { stats.perRefuel[it.id] }
    val nowCal = Calendar.getInstance()
    val monthsInYear = if (y.year == nowCal.get(Calendar.YEAR)) nowCal.get(Calendar.MONTH) + 1 else 12

    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (y.distance > 0) DetailLine(str(R.string.stat_year_distance), y.distance.dist(du))
        DetailLine(str(R.string.stat_refuel_count), y.refuels.size.toString())
        if (main.isNotEmpty()) DetailLine(str(if (ev) R.string.stat_total_charged else R.string.stat_total_amount), "${main.sumOf { it.amount }.fmt(1)} $unit")
        if (per.isNotEmpty()) DetailLine(str(R.string.stat_avg), Units.consumption(v, v.defaultFuelType, per.average()))
        if (priced.isNotEmpty()) {
            DetailLine(str(R.string.stat_price_avg, unit), "${(priced.sumOf { it.totalPrice } / priced.sumOf { it.amount }).fmt(3)} $cur")
            val min = priced.minBy { it.pricePerUnit }
            val max = priced.maxBy { it.pricePerUnit }
            DetailLine(str(R.string.stat_price_min), "${min.pricePerUnit.fmt(3)} $cur · ${min.timestamp.date()}")
            DetailLine(str(R.string.stat_price_max), "${max.pricePerUnit.fmt(3)} $cur · ${max.timestamp.date()}")
        }
        DetailLine(str(R.string.stat_cost_month), "${(y.total / monthsInYear).fmt(0)} $cur")

        // Monate mit Einträgen
        val fmt = SimpleDateFormat("MMMM", appLocale())
        val months = (0..11).mapNotNull { m ->
            fun inMonth(ts: Long) = cal(ts).get(Calendar.MONTH) == m
            val r = y.refuels.filter { inMonth(it.timestamp) }
            val cost = r.sumOf { it.totalPrice } + y.services.filter { inMonth(it.timestamp) }.sumOf { it.cost } +
                y.expenses.filter { inMonth(it.timestamp) }.sumOf { it.amount }
            if (r.isEmpty() && cost == 0.0) return@mapNotNull null
            val c = Calendar.getInstance().apply { clear(); set(y.year, m, 1) }
            val pm = r.filter { it.isCharge == ev }.mapNotNull { stats.perRefuel[it.id] }
            listOf(
                fmt.format(Date(c.timeInMillis)),
                r.sumOf { distances[it.id] ?: 0 }.takeIf { it > 0 }?.let { "$it" } ?: "–",
                r.filter { it.isCharge == ev }.sumOf { it.amount }.takeIf { it > 0 }?.fmt(0) ?: "–",
                cost.fmt(0),
                if (pm.isNotEmpty()) pm.average().let { Units.consumption(v, v.defaultFuelType, it).substringBefore(' ') } else "–",
            )
        }
        if (months.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(str(R.string.stats_by_month), style = MaterialTheme.typography.titleSmall)
            val weights = listOf(1.6f, 1f, 1f, 1f, 0.9f)
            Row(Modifier.fillMaxWidth()) {
                listOf(
                    str(R.string.col_month), "${str(R.string.col_distance)} ($du)", "${str(R.string.col_amount)} ($unit)",
                    "${str(R.string.col_cost)} ($cur)", str(R.string.col_consumption),
                ).forEachIndexed { i, h ->
                    Text(h, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(weights[i]), maxLines = 2)
                }
            }
            months.forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEachIndexed { i, c ->
                        Text(c, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(weights[i]), maxLines = 1)
                    }
                }
            }
        }

        if (y.services.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(str(R.string.tab_service), style = MaterialTheme.typography.titleSmall)
            y.services.sortedBy { it.timestamp }.forEach { s ->
                DetailLine("${s.timestamp.date()} · ${s.title}", "${s.cost.fmt(2)} $cur")
            }
        }
        if (y.expenses.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(str(R.string.stats_other_by_category), style = MaterialTheme.typography.titleSmall)
            y.expenses.groupBy { it.category }.map { (k, l) -> k to l.sumOf { it.amount } }.sortedByDescending { it.second }
                .forEach { (k, sum) -> DetailLine(lbl(k).ifBlank { str(R.string.legend_other) }, "${sum.fmt(2)} $cur") }
        }
    }
}
