package de.spritbuch.app

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

private data class YearRow(val year: Int, val fuel: Double, val service: Double, val other: Double, val distance: Int) {
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
    val repo = LocalContext.current.repo
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

    Scaffold(topBar = { TopAppBar(title = { Text(str(R.string.stats_title, v?.name ?: "")) }, navigationIcon = { BackButton(nav) }) }) { pad ->
        if (v == null) return@Scaffold
        val cur = v.currency
        val du = v.distanceUnit

        // Kosten je Jahr; Strecke = Spanne der Kilometerstände der Betankungen im Jahr
        val years = remember(refuels, services, expenses) {
            val ys = (refuels.map { cal(it.timestamp).get(Calendar.YEAR) } +
                services.map { cal(it.timestamp).get(Calendar.YEAR) } +
                expenses.map { cal(it.timestamp).get(Calendar.YEAR) }).toSortedSet().reversed()
            ys.map { y ->
                val r = refuels.filter { cal(it.timestamp).get(Calendar.YEAR) == y }
                val dist = if (r.size >= 2) r.maxOf { it.odometer } - r.minOf { it.odometer } else 0
                YearRow(
                    y,
                    r.sumOf { it.totalPrice },
                    services.filter { cal(it.timestamp).get(Calendar.YEAR) == y }.sumOf { it.cost },
                    expenses.filter { cal(it.timestamp).get(Calendar.YEAR) == y }.sumOf { it.amount },
                    dist,
                )
            }
        }
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
        val prices = remember(refuels) {
            refuels.filter { it.amount > 0 && it.totalPrice > 0 && Units.isLiquid(it.fuelType) }
                .sortedBy { it.timestamp }.map { it.pricePerUnit }.takeLast(40)
        }
        fun groupAvg(key: (Refuel) -> String) = refuels.filter { it.isCharge == (v.defaultFuelType == ELECTRIC) }.mapNotNull { r -> stats.perRefuel[r.id]?.let { key(r) to it } }
            .filter { it.first.isNotBlank() }
            .groupBy({ it.first }, { it.second })
            .map { (k, list) -> Triple(k, list.average(), list.size) }
            .sortedBy { it.second }
        val kwhPrices = remember(refuels) {
            refuels.filter { it.isCharge && it.amount > 0 && it.totalPrice > 0 }.sortedBy { it.timestamp }.map { it.pricePerUnit }.takeLast(40)
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
                        if (years.isEmpty()) Text("–")
                        years.forEachIndexed { i, y ->
                            if (i > 0) HorizontalDivider()
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(y.year.toString(), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                Text("${y.total.fmt(0)} $cur", fontWeight = FontWeight.SemiBold)
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
                    }
                }
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
