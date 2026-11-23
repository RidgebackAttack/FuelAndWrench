package de.spritbuch.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.util.Calendar

/** Ölverbrauch aus Ölwechseln und Nachfüllungen. */
object OilStats {
    /** Mindeststrecke, ab der ein Verbrauch angezeigt wird (darunter ist er zu ungenau). */
    const val MIN_DISTANCE = 300

    class Summary(
        val lastChange: ServiceEntry?,
        val topUps: List<ServiceEntry>,
        /** Menge je 1000 Strecke (Fahrzeugeinheiten) oder null. */
        val per1000: Double?,
    ) {
        val amount: Double get() = topUps.sumOf { it.oilAmount ?: 0.0 }
        val isEmpty: Boolean get() = lastChange == null && topUps.isEmpty()
    }

    /**
     * Seit dem letzten Ölwechsel: Nachgefülltes geteilt durch die Strecke bis zur letzten Nachfüllung
     * (was danach verbraucht wurde, ist noch nicht nachgefüllt). Ohne Ölwechsel zählt die erste
     * Nachfüllung als Ausgangspunkt.
     */
    fun summary(services: List<ServiceEntry>): Summary {
        val change = services.filter { it.category != OIL_TOPUP && it.isOilChange }.maxByOrNull { it.timestamp }
        val topUps = services.filter { it.category == OIL_TOPUP && (change == null || it.timestamp > change.timestamp) }
            .sortedWith(compareBy({ it.timestamp }, { it.odometer }))
        val withKm = topUps.filter { it.odometer > 0 && (it.oilAmount ?: 0.0) > 0 }
        val per1000 = if (change != null && change.odometer > 0) {
            rate(withKm.sumOf { it.oilAmount ?: 0.0 }, (withKm.maxOfOrNull { it.odometer } ?: 0) - change.odometer)
        } else if (withKm.size >= 2) {
            rate(withKm.drop(1).sumOf { it.oilAmount ?: 0.0 }, withKm.last().odometer - withKm.first().odometer)
        } else null
        return Summary(change, topUps, per1000)
    }

    private fun rate(amount: Double, distance: Int): Double? =
        if (amount > 0 && distance >= MIN_DISTANCE) amount / distance * 1000 else null

    class Year(val year: Int, val amount: Double, val count: Int, val distance: Int) {
        val per1000: Double? get() = rate(amount, distance)
    }

    /** Nachfüllungen je Jahr; die Jahresstrecke kommt aus den Kilometerständen aller Einträge. */
    fun perYear(services: List<ServiceEntry>, refuels: List<Refuel>): List<Year> {
        val tops = services.filter { it.category == OIL_TOPUP && (it.oilAmount ?: 0.0) > 0 }
        if (tops.isEmpty()) return emptyList()
        fun yearOf(ts: Long) = Calendar.getInstance().apply { timeInMillis = ts }.get(Calendar.YEAR)
        // Kilometerstände aus Betankungen und Service-Einträgen, chronologisch
        val odo = (refuels.map { it.timestamp to it.odometer } + services.map { it.timestamp to it.odometer })
            .filter { it.second > 0 }.sortedBy { it.first }
        val maxByYear = odo.groupBy { yearOf(it.first) }.mapValues { (_, l) -> l.maxOf { it.second } }
        val first = tops.minOf { yearOf(it.timestamp) }
        val last = maxOf(yearOf(now()), tops.maxOf { yearOf(it.timestamp) })
        return (first..last).mapNotNull { y ->
            val inYear = tops.filter { yearOf(it.timestamp) == y }
            val end = maxByYear[y] ?: return@mapNotNull null
            val start = maxByYear.filterKeys { it < y }.values.maxOrNull() ?: odo.first { yearOf(it.first) == y }.second
            Year(y, inYear.sumOf { it.oilAmount ?: 0.0 }, inYear.size, (end - start).coerceAtLeast(0))
        }.filter { it.count > 0 || it.distance > 0 }.reversed()
    }

    fun unit(v: Vehicle?): String = Units.amount(v, "Diesel")
    fun rateText(v: Vehicle?, per1000: Double): String = "${per1000.fmt(2)} ${unit(v)}/1000 ${Units.dist(v)}"
}

/** Motoröl in der Übersicht: Sorte aus dem Steckbrief, letzter Ölwechsel, Nachgefülltes, Verbrauch, Schnelleingabe. */
@Composable
fun OilCard(v: Vehicle, services: List<ServiceEntry>, currentOdo: Int) {
    val s = OilStats.summary(services)
    val spec = Specs.value(v, "oil_spec")
    var adding by rememberSaveable { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.WaterDrop, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(str(R.string.oil_section), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 8.dp).weight(1f))
                OutlinedButton(onClick = { adding = true }) { Text(str(R.string.oil_topup_btn)) }
            }
            if (spec != null) Text(str(R.string.oil_spec_line, spec), style = MaterialTheme.typography.bodySmall)
            if (!s.isEmpty) {
                Text(
                    s.lastChange?.let { c ->
                        str(R.string.oil_last_change, listOf(c.timestamp.date(), if (c.odometer > 0) c.odometer.dist(v.distanceUnit) else "")
                            .filter { it.isNotBlank() }.joinToString(" · "))
                    } ?: str(R.string.oil_no_change),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (s.topUps.isNotEmpty()) Text(
                    str(R.string.oil_topped_up, "${s.amount.fmt(2)} ${OilStats.unit(v)} (${s.topUps.size}×)"),
                    style = MaterialTheme.typography.bodySmall,
                )
                s.per1000?.let {
                    Text(str(R.string.oil_consumption, OilStats.rateText(v, it)), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
    if (adding) OilTopUpDialog(v, currentOdo) { adding = false }
}

@Composable
private fun OilTopUpDialog(v: Vehicle, currentOdo: Int, onClose: () -> Unit) {
    val repo = LocalContext.current.repo
    val scope = rememberCoroutineScope()
    var amount by rememberSaveable { mutableStateOf("") }
    var odometer by rememberSaveable { mutableStateOf(if (currentOdo > 0) currentOdo.toString() else "") }
    var cost by rememberSaveable { mutableStateOf("") }
    val amt = amount.toDoubleDe()
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(str(R.string.oil_topup_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Specs.value(v, "oil_spec")?.let { Text(str(R.string.oil_spec_line, it), style = MaterialTheme.typography.bodySmall) }
                NumberField(str(R.string.oil_amount), amount, { amount = it }, suffix = OilStats.unit(v))
                NumberField(str(R.string.odometer), odometer, { odometer = it }, suffix = Units.dist(v), decimal = false)
                NumberField(str(R.string.cost_optional), cost, { cost = it }, suffix = Units.cur(v))
            }
        },
        confirmButton = {
            TextButton(enabled = amt != null && amt > 0, onClick = {
                scope.launch {
                    repo.saveService(
                        ServiceEntry(
                            vehicleId = v.id, odometer = odometer.toIntKm() ?: 0, title = str(R.string.oil_topup_title),
                            category = OIL_TOPUP, oilAmount = amt, cost = cost.toDoubleDe() ?: 0.0,
                        ),
                    )
                    onClose()
                }
            }) { Text(str(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(str(R.string.cancel)) } },
    )
}
