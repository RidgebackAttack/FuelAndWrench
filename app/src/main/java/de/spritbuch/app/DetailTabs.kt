package de.spritbuch.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

private fun yearOfTs(ts: Long) = Calendar.getInstance().apply { timeInMillis = ts }.get(Calendar.YEAR)

/** Startet den Service-Import (CSV) und zeigt den Bestätigungsdialog. Liefert die Startfunktion. */
@Composable
fun rememberServiceImporter(vehicleId: String, vehicle: Vehicle?, snackbar: SnackbarHostState): () -> Unit {
    val ctx = LocalContext.current
    val repo = ctx.repo
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<ServiceImport.Result?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val veh = vehicle
        if (uri != null && veh != null) scope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
                    ServiceImport.parse(CsvImport.decode(bytes), veh)
                }
            }
            res.onSuccess { pending = it }.onFailure { snackbar.showSnackbar("Import fehlgeschlagen: ${it.message}") }
        }
    }
    pending?.let { imp ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Service-Import") },
            text = {
                Text(
                    imp.error ?: (
                        "${imp.entries.size} Einträge gefunden" +
                            (if (imp.skipped > 0) ", ${imp.skipped} Zeilen übersprungen" else "") +
                            " (${imp.entries.sumOf { it.cost }.fmt()} ${vehicle?.currency ?: "€"}).\n\n" +
                            "Bereits vorhandene Einträge werden nicht doppelt angelegt. Passende Erinnerungen " +
                            "(z. B. Ölwechsel) werden auf den letzten km-Stand gesetzt."
                        )
                )
            },
            confirmButton = {
                if (imp.entries.isNotEmpty()) TextButton(onClick = {
                    pending = null
                    scope.launch {
                        val (added, dups) = repo.importServices(vehicleId, imp.entries)
                        snackbar.showSnackbar("$added importiert" + if (dups > 0) ", $dups bereits vorhanden" else "")
                    }
                }) { Text("Importieren") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Abbrechen") } },
        )
    }
    return { launcher.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel", "application/octet-stream")) }
}

@Composable
private fun StatLine(label: String, value: String) {
    Row {
        Text(label, modifier = Modifier.weight(1f))
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

/** Verbrauchsverlauf (chronologisch) mit gestrichelter Durchschnittslinie. */
@Composable
fun ConsumptionChart(values: List<Double>, format: (Double) -> String) {
    if (values.size < 2) return
    val line = MaterialTheme.colorScheme.primary
    val avgColor = MaterialTheme.colorScheme.outline
    val avg = values.average()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Verbrauchsverlauf (letzte ${values.size} Volltankungen)", style = MaterialTheme.typography.titleSmall)
            Canvas(Modifier.fillMaxWidth().height(120.dp)) {
                val min = values.min()
                val max = values.max()
                val range = (max - min).takeIf { it > 0 } ?: 1.0
                val padY = size.height * 0.1f
                fun y(v: Double) = (size.height - padY - ((v - min) / range) * (size.height - 2 * padY)).toFloat()
                val stepX = size.width / (values.size - 1)
                drawLine(
                    avgColor, Offset(0f, y(avg)), Offset(size.width, y(avg)),
                    strokeWidth = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f)),
                )
                val path = Path()
                values.forEachIndexed { i, v ->
                    val p = Offset(i * stepX, y(v))
                    if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                }
                drawPath(path, line, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
                values.forEachIndexed { i, v -> drawCircle(line, 3.dp.toPx(), Offset(i * stepX, y(v))) }
            }
            Text(
                "min ${format(values.min())} · Ø ${format(avg)} · max ${format(values.max())}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VehicleDetailContent(
    nav: NavController,
    vehicleId: String,
    vehicle: Vehicle?,
    refuels: List<Refuel>,
    reminders: List<Reminder>,
    services: List<ServiceEntry>,
    stats: Stats,
    currentOdo: Int,
    snackbar: SnackbarHostState,
    onImportRefuels: () -> Unit,
    onEditOdometer: () -> Unit,
) {
    val repo = LocalContext.current.repo
    val scope = rememberCoroutineScope()
    val tabs = listOf("Übersicht", "Tanken", "Service")
    val pager = rememberPagerState { tabs.size }
    val importServices = rememberServiceImporter(vehicleId, vehicle, snackbar)
    val v = vehicle
    val fuel = v?.defaultFuelType ?: ""
    val amt = Units.amount(v, fuel)
    val du = Units.dist(v)
    val cur = Units.cur(v)
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    val dotColor = MaterialTheme.colorScheme.primary

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(v?.name ?: "") },
                navigationIcon = { BackButton(nav) },
                actions = {
                    when (pager.currentPage) {
                        1 -> {
                            IconButton(onClick = { nav.navigate("map/$vehicleId") }) {
                                Icon(Icons.Default.Map, contentDescription = "Karte")
                            }
                            IconButton(onClick = onImportRefuels) {
                                Icon(Icons.Default.FileUpload, contentDescription = "Spritmonitor-Import")
                            }
                        }
                        2 -> IconButton(onClick = importServices) {
                            Icon(Icons.Default.FileUpload, contentDescription = "Service importieren")
                        }
                    }
                    IconButton(onClick = { nav.navigate("vehicleEdit/$vehicleId") }) {
                        Icon(Icons.Default.Edit, contentDescription = "Fahrzeug bearbeiten")
                    }
                },
            )
        },
        floatingActionButton = {
            if (pager.currentPage == 2) {
                ExtendedFloatingActionButton(
                    onClick = { nav.navigate("serviceEdit/$vehicleId/new") },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("Service") },
                )
            } else {
                ExtendedFloatingActionButton(
                    onClick = { nav.navigate("refuelEdit/$vehicleId/new") },
                    icon = { Icon(Icons.Default.LocalGasStation, contentDescription = null) },
                    text = { Text("Tanken") },
                )
            }
        },
    ) { pad ->
        if (v == null || v.deleted) return@Scaffold
        Column(Modifier.padding(pad).fillMaxSize()) {
            TabRow(selectedTabIndex = pager.currentPage) {
                tabs.forEachIndexed { i, t ->
                    Tab(
                        selected = pager.currentPage == i,
                        onClick = { scope.launch { pager.animateScrollToPage(i) } },
                        text = {
                            val count = when (i) { 1 -> refuels.size; 2 -> services.size; else -> 0 }
                            Text(if (count > 0) "$t ($count)" else t, maxLines = 1)
                        },
                    )
                }
            }
            HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { page ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    when (page) {
                        0 -> overviewItems(
                            nav, v, refuels, reminders, services, stats, currentOdo, fuel, amt, du, cur,
                            onEditOdometer,
                            onDone = { r ->
                                scope.launch {
                                    repo.completeReminder(r, currentOdo)
                                    snackbar.showSnackbar("Erledigt – Service-Eintrag angelegt")
                                }
                            },
                            openTab = { scope.launch { pager.animateScrollToPage(it) } },
                        )
                        1 -> refuelItems(nav, v, refuels, stats)
                        2 -> serviceItems(nav, v, services, lineColor, dotColor)
                    }
                }
            }
        }
    }
}

private fun LazyListScope.overviewItems(
    nav: NavController,
    v: Vehicle,
    refuels: List<Refuel>,
    reminders: List<Reminder>,
    services: List<ServiceEntry>,
    stats: Stats,
    currentOdo: Int,
    fuel: String,
    amt: String,
    du: String,
    cur: String,
    onEditOdometer: () -> Unit,
    onDone: (Reminder) -> Unit,
    openTab: (Int) -> Unit,
) {
    if (v.archived) item {
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        ) {
            Text("Fahrzeug ist archiviert. Erinnerungen sind pausiert.", modifier = Modifier.padding(12.dp))
        }
    }
    if (v.photoVersion > 0) item {
        VehiclePhoto(v, Modifier.fillMaxWidth().aspectRatio(LARGE_PHOTO_ASPECT), large = true)
    }
    item {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                StatLine("Ø Verbrauch", Units.consumption(v, fuel, stats.avg))
                StatLine("Letzter Verbrauch", Units.consumption(v, fuel, stats.last))
                StatLine("Kosten pro 100 $du", stats.costPer100?.let { "${it.fmt()} $cur" } ?: "–")
                StatLine("Tankkosten gesamt", "${stats.totalCost.fmt()} $cur")
                StatLine("Servicekosten gesamt", "${services.sumOf { it.cost }.fmt()} $cur")
                StatLine("Getankt gesamt", "${stats.totalAmount.fmt(1)} $amt")
                StatLine("Erfasste Strecke", stats.distance.dist(du))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Kilometerstand", modifier = Modifier.weight(1f))
                    Text(currentOdo.dist(du), fontWeight = FontWeight.SemiBold)
                    IconButton(onClick = onEditOdometer) {
                        Icon(Icons.Default.Edit, contentDescription = "Kilometerstand ändern")
                    }
                }
            }
        }
    }
    // Verbrauchsverlauf: letzte 30 Werte chronologisch
    val chartValues = refuels.sortedWith(compareBy({ it.odometer }, { it.timestamp }))
        .mapNotNull { stats.perRefuel[it.id] }.takeLast(30)
    if (chartValues.size >= 2) item {
        ConsumptionChart(chartValues) { Units.consumption(v, fuel, it) }
    }
    item {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { SectionTitle("Erinnerungen") }
            TextButton(onClick = { nav.navigate("reminderEdit/${v.id}/new") }) { Text("+ Neu") }
        }
    }
    if (reminders.isEmpty()) item {
        Text("Keine Erinnerungen angelegt.", style = MaterialTheme.typography.bodySmall)
    }
    items(reminders.sortedBy { it.dueKm - currentOdo }, key = { "rem-" + it.id }) { r ->
        val remaining = r.dueKm - currentOdo
        val due = remaining <= 0
        val warn = remaining <= r.warnBeforeKm
        val progress = if (r.intervalKm > 0) ((currentOdo - r.lastDoneKm).toFloat() / r.intervalKm).coerceIn(0f, 1f) else 0f
        Card(
            onClick = { nav.navigate("reminderEdit/${v.id}/${r.id}") },
            modifier = Modifier.fillMaxWidth(),
            colors = if (warn) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            else CardDefaults.cardColors(),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (due) "Fällig seit ${(-remaining).dist(du)} (bei ${r.dueKm.dist(du)})"
                            else "Noch ${remaining.dist(du)} (fällig bei ${r.dueKm.dist(du)})",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    OutlinedButton(onClick = { onDone(r) }) { Text("Erledigt") }
                }
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
    refuels.firstOrNull()?.let { last ->
        item { SectionTitle("Letzte Tankung") }
        item(key = "last-refuel") { RefuelCard(v, last, stats.perRefuel[last.id]) { openTab(1) } }
    }
    services.firstOrNull()?.let { last ->
        item { SectionTitle("Letzter Service") }
        item(key = "last-service") { ServiceCard(v, last) { openTab(2) } }
    }
}

private fun LazyListScope.refuelItems(nav: NavController, v: Vehicle, refuels: List<Refuel>, stats: Stats) {
    if (refuels.isEmpty()) {
        item { Text("Noch keine Tankvorgänge. Über „Tanken“ erfassen oder oben per Spritmonitor-Import übernehmen.") }
        return
    }
    val unit = Units.amount(v, v.defaultFuelType)
    refuels.groupBy { yearOfTs(it.timestamp) }.toSortedMap(compareByDescending { it }).forEach { (year, list) ->
        val per = list.mapNotNull { stats.perRefuel[it.id] }
        val avg = if (per.isNotEmpty()) Units.consumption(v, v.defaultFuelType, per.average()) else "–"
        item(key = "y-$year") {
            Column(Modifier.padding(top = 8.dp)) {
                Text(year.toString(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Text(
                    "${list.size} Tankungen · ${list.sumOf { it.amount }.fmt(1)} $unit · " +
                        "${list.sumOf { it.totalPrice }.fmt()} ${v.currency} · Ø $avg",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        items(list, key = { it.id }) { r ->
            RefuelCard(v, r, stats.perRefuel[r.id]) { nav.navigate("refuelEdit/${v.id}/${r.id}") }
        }
    }
}

private fun LazyListScope.serviceItems(
    nav: NavController,
    v: Vehicle,
    services: List<ServiceEntry>,
    lineColor: Color,
    dotColor: Color,
) {
    if (services.isEmpty()) {
        item { Text("Noch keine Service-Einträge. Über „Service“ anlegen oder oben eine CSV importieren.") }
        return
    }
    item {
        Text(
            "${services.size} Einträge · ${services.sumOf { it.cost }.fmt()} ${v.currency} gesamt",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    services.forEachIndexed { i, s ->
        val year = yearOfTs(s.timestamp)
        if (i == 0 || yearOfTs(services[i - 1].timestamp) != year) {
            item(key = "sy-$year-$i") {
                Text(
                    year.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                )
            }
        }
        item(key = "s-" + s.id) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                Canvas(Modifier.width(28.dp).fillMaxHeight()) {
                    val cx = size.width / 2f
                    val dotY = 26.dp.toPx()
                    if (i > 0) drawLine(lineColor, Offset(cx, -12.dp.toPx()), Offset(cx, dotY), strokeWidth = 2.dp.toPx())
                    if (i < services.lastIndex) {
                        drawLine(lineColor, Offset(cx, dotY), Offset(cx, size.height + 12.dp.toPx()), strokeWidth = 2.dp.toPx())
                    }
                    drawCircle(dotColor, radius = 6.dp.toPx(), center = Offset(cx, dotY))
                }
                Spacer(Modifier.width(4.dp))
                ServiceCard(v, s, Modifier.weight(1f)) { nav.navigate("serviceEdit/${v.id}/${s.id}") }
            }
        }
    }
}
