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
import androidx.compose.material.icons.filled.EvStation
import androidx.compose.material.icons.filled.Euro
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Build
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
import androidx.compose.material3.ScrollableTabRow
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

private fun yearOfTs(ts: Long) = Calendar.getInstance().apply { timeInMillis = ts }.get(Calendar.YEAR)

/** Startet den Kosten-Import (CSV, z. B. Spritmonitor) und zeigt den Bestätigungsdialog. */
@Composable
fun rememberCostImporter(vehicleId: String, vehicle: Vehicle?, snackbar: SnackbarHostState): () -> Unit {
    val ctx = LocalContext.current
    val repo = ctx.repo
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<CostImport.Result?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val veh = vehicle
        if (uri != null && veh != null) scope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
                    CostImport.parse(CsvImport.decode(bytes), veh)
                }
            }
            res.onSuccess { pending = it }.onFailure { snackbar.showSnackbar(str(R.string.import_failed, it.message ?: "")) }
        }
    }
    pending?.let { imp ->
        val empty = imp.expenses.isEmpty() && imp.services.isEmpty()
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(str(R.string.cost_import)) },
            text = {
                Text(
                    imp.error ?: (
                        str(R.string.cost_import_found, plural(R.plurals.pl_costs, imp.expenses.size), plural(R.plurals.pl_service_entries, imp.services.size)) +
                            (if (imp.skipped > 0) str(R.string.svc_import_skipped, plural(R.plurals.pl_rows, imp.skipped)) else "") +
                            " (${imp.total.fmt()} ${vehicle?.currency ?: "€"}).\n\n" +
                            str(R.string.cost_import_info)
                        )
                )
            },
            confirmButton = {
                if (!empty) TextButton(onClick = {
                    pending = null
                    scope.launch {
                        val (a1, d1) = repo.importExpenses(vehicleId, imp.expenses)
                        val (a2, d2) = repo.importServices(vehicleId, imp.services)
                        val dups = d1 + d2
                        snackbar.showSnackbar(str(R.string.imported_n, a1 + a2) + if (dups > 0) str(R.string.already_present, dups) else "")
                    }
                }) { Text(str(R.string.do_import)) }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(str(R.string.cancel)) } },
        )
    }
    return { launcher.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel", "application/octet-stream")) }
}

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
            res.onSuccess { pending = it }.onFailure { snackbar.showSnackbar(str(R.string.import_failed, it.message ?: "")) }
        }
    }
    pending?.let { imp ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(str(R.string.service_import)) },
            text = {
                Text(
                    imp.error ?: (
                        str(R.string.svc_import_found, plural(R.plurals.pl_entries, imp.entries.size)) +
                            (if (imp.skipped > 0) str(R.string.svc_import_skipped, plural(R.plurals.pl_rows, imp.skipped)) else "") +
                            " (${imp.entries.sumOf { it.cost }.fmt()} ${vehicle?.currency ?: "€"}).\n\n" +
                            str(R.string.svc_import_info)
                        )
                )
            },
            confirmButton = {
                if (imp.entries.isNotEmpty()) TextButton(onClick = {
                    pending = null
                    scope.launch {
                        val (added, dups) = repo.importServices(vehicleId, imp.entries)
                        snackbar.showSnackbar(str(R.string.imported_n, added) + if (dups > 0) str(R.string.already_present, dups) else "")
                    }
                }) { Text(str(R.string.do_import)) }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(str(R.string.cancel)) } },
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

/**
 * Verbrauchsverlauf (chronologisch): Fläche mit Farbverlauf unter der Linie, gestrichelte
 * Durchschnittslinie, Punkte grün (unter Ø), gelb (um Ø) oder rot (deutlich über Ø).
 */
@Composable
fun ConsumptionChart(values: List<Double>, title: String? = null, format: (Double) -> String) {
    if (values.size < 2) return
    val line = MaterialTheme.colorScheme.primary
    val avgColor = MaterialTheme.colorScheme.outline
    val good = Color(0xFF2E9E5B)
    val mid = Color(0xFFE0A100)
    val bad = Color(0xFFD64545)
    val avg = values.average()
    fun colorFor(v: Double) = when {
        v <= avg * 0.97 -> good
        v >= avg * 1.05 -> bad
        else -> mid
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title ?: str(R.string.chart_title, plural(R.plurals.pl_full_refuels, values.size)), style = MaterialTheme.typography.titleSmall)
            Canvas(Modifier.fillMaxWidth().height(140.dp)) {
                val min = values.min()
                val max = values.max()
                val range = (max - min).takeIf { it > 0 } ?: 1.0
                val padY = size.height * 0.1f
                fun y(v: Double) = (size.height - padY - ((v - min) / range) * (size.height - 2 * padY)).toFloat()
                val stepX = size.width / (values.size - 1)
                val pts = values.mapIndexed { i, v -> Offset(i * stepX, y(v)) }

                // Hintergrund-Bänder: unter Ø leicht grün, über Ø leicht rot
                val avgY = y(avg)
                drawRect(
                    Brush.verticalGradient(listOf(bad.copy(alpha = 0.10f), bad.copy(alpha = 0.02f)), 0f, avgY),
                    topLeft = Offset(0f, 0f), size = Size(size.width, avgY),
                )
                drawRect(
                    Brush.verticalGradient(listOf(good.copy(alpha = 0.02f), good.copy(alpha = 0.10f)), avgY, size.height),
                    topLeft = Offset(0f, avgY), size = Size(size.width, size.height - avgY),
                )

                // Fläche unter der Linie mit Farbverlauf
                val area = Path().apply {
                    moveTo(pts.first().x, size.height)
                    pts.forEach { lineTo(it.x, it.y) }
                    lineTo(pts.last().x, size.height)
                    close()
                }
                drawPath(area, Brush.verticalGradient(listOf(line.copy(alpha = 0.35f), line.copy(alpha = 0.02f))))

                drawLine(
                    avgColor, Offset(0f, avgY), Offset(size.width, avgY),
                    strokeWidth = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f)),
                )
                val path = Path()
                pts.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
                drawPath(path, line, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
                pts.forEachIndexed { i, p ->
                    drawCircle(Color.White, 4.5.dp.toPx(), p)
                    drawCircle(colorFor(values[i]), 3.5.dp.toPx(), p)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(str(R.string.chart_min, format(values.min())), style = MaterialTheme.typography.bodySmall, color = good)
                Text("Ø ${format(avg)}", style = MaterialTheme.typography.bodySmall)
                Text(str(R.string.chart_max, format(values.max())), style = MaterialTheme.typography.bodySmall, color = bad)
            }
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
    val tabs = listOf(str(R.string.tab_overview), str(R.string.tab_refuels), str(R.string.tab_service), str(R.string.tab_costs))
    val expenses by repo.expenses(vehicleId).collectAsStateWithLifecycle(emptyList())
    val pager = rememberPagerState { tabs.size }
    val importServices = rememberServiceImporter(vehicleId, vehicle, snackbar)
    val importCosts = rememberCostImporter(vehicleId, vehicle, snackbar)
    val tireSets by repo.tireSets(vehicleId).collectAsStateWithLifecycle(emptyList())
    val v = vehicle
    val fuel = v?.defaultFuelType ?: ""
    val amt = Units.amount(v, fuel)
    val du = Units.dist(v)
    val cur = Units.cur(v)
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    val dotColor = MaterialTheme.colorScheme.primary
    val distances = remember(refuels) { distancesSincePrevious(refuels) }
    val refuelYears = remember(v, refuels, stats, distances) { if (v == null) emptyList() else groupRefuelsByYear(v, refuels, stats, distances) }
    val ctx = LocalContext.current
    var serviceFilter by remember(vehicleId) { mutableStateOf(ServiceFilter.load(ctx, vehicleId)) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(v?.name ?: "") },
                navigationIcon = { BackButton(nav) },
                actions = {
                    when (pager.currentPage) {
                        0 -> {
                            if (v != null && FuelPrices.typeFor(v.defaultFuelType) != null) {
                                IconButton(onClick = { nav.navigate("prices/$vehicleId") }) {
                                    Icon(Icons.Default.Euro, contentDescription = str(R.string.prices_cd))
                                }
                            }
                            IconButton(onClick = { nav.navigate("stats/$vehicleId") }) {
                                Icon(Icons.Default.BarChart, contentDescription = str(R.string.statistics))
                            }
                        }
                        1 -> {
                            IconButton(onClick = { nav.navigate("map/$vehicleId") }) {
                                Icon(Icons.Default.Map, contentDescription = str(R.string.map))
                            }
                            IconButton(onClick = onImportRefuels) {
                                Icon(Icons.Default.FileUpload, contentDescription = str(R.string.cd_spritmonitor_import))
                            }
                        }
                        2 -> IconButton(onClick = importServices) {
                            Icon(Icons.Default.FileUpload, contentDescription = str(R.string.cd_service_import))
                        }
                        3 -> IconButton(onClick = importCosts) {
                            Icon(Icons.Default.FileUpload, contentDescription = str(R.string.cd_cost_import))
                        }
                    }
                    IconButton(onClick = { nav.navigate("vehicleEdit/$vehicleId") }) {
                        Icon(Icons.Default.Edit, contentDescription = str(R.string.vehicle_edit))
                    }
                },
            )
        },
        floatingActionButton = {
            if (pager.currentPage == 2) {
                ExtendedFloatingActionButton(
                    onClick = { nav.navigate("serviceEdit/$vehicleId/new") },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text(str(R.string.tab_service)) },
                )
            } else if (pager.currentPage == 3) {
                ExtendedFloatingActionButton(
                    onClick = { nav.navigate("expenseEdit/$vehicleId/new") },
                    icon = { Icon(Icons.Default.Payments, contentDescription = null) },
                    text = { Text(str(R.string.expense)) },
                )
            } else {
                val ev = vehicle?.isElectric == true
                ExtendedFloatingActionButton(
                    onClick = { nav.navigate("refuelEdit/$vehicleId/new") },
                    icon = { Icon(if (ev) Icons.Default.EvStation else Icons.Default.LocalGasStation, contentDescription = null) },
                    text = { Text(str(if (ev) R.string.charge else R.string.refuel)) },
                )
            }
        },
    ) { pad ->
        if (v == null || v.deleted) return@Scaffold
        Column(Modifier.padding(pad).fillMaxSize()) {
            ScrollableTabRow(selectedTabIndex = pager.currentPage, edgePadding = 8.dp) {
                tabs.forEachIndexed { i, t ->
                    Tab(
                        selected = pager.currentPage == i,
                        onClick = { scope.launch { pager.animateScrollToPage(i) } },
                        text = {
                            val count = when (i) { 1 -> refuels.size; 2 -> services.size; 3 -> expenses.size; else -> 0 }
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
                        0 -> {
                            overviewItems(
                            nav, v, refuels, reminders, services, expenses, stats, currentOdo, fuel, amt, du, cur,
                            onEditOdometer,
                            onDone = { r ->
                                scope.launch {
                                    repo.completeReminder(r, currentOdo)
                                    snackbar.showSnackbar(str(R.string.done_service_created))
                                }
                            },
                            openTab = { scope.launch { pager.animateScrollToPage(it) } },
                            )
                            // Fahrzeugdokumente (Fahrzeugschein, Versicherungskarte, aktueller TÜV-Bericht) und Steckbrief
                            documentItems(nav, v, services)
                            specItems(nav, v)
                        }
                        1 -> refuelItems(nav, v, refuelYears, stats, distances)
                        2 -> {
                            tireSetItems(nav, v, tireSets, services)
                            serviceItems(nav, v, services, serviceFilter, lineColor, dotColor) {
                                serviceFilter = it
                                ServiceFilter.save(ctx, vehicleId, it)
                            }
                        }
                        3 -> expenseItems(nav, v, expenses)
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
    expenses: List<Expense>,
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
            Text(str(R.string.vehicle_archived_banner), modifier = Modifier.padding(12.dp))
        }
    }
    Registration.describe(v)?.let { d ->
        item(key = "registration") {
            Text(d, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    // Auffälligkeiten in den Betankungen (z. B. Tippfehler beim Kilometerstand)
    item(key = "checks") {
        val ctx = LocalContext.current
        val n = remember(v, refuels) { DataCheck.open(ctx, v, refuels).size }
        if (n > 0) Card(
            onClick = { nav.navigate("checks") },
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        ) {
            Text(plural(R.plurals.pl_check_issues, n), modifier = Modifier.padding(12.dp))
        }
    }
    item(key = "hero") {
        VehicleHero(v) {
            Column {
                if (v.plate.isNotBlank()) Text(v.plate, style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = FontWeight.Bold)
                Text(
                    listOf(lbl(v.defaultFuelType), if (refuels.isNotEmpty()) plural(R.plurals.pl_refuels, refuels.size) else "", if (services.isNotEmpty()) plural(R.plurals.pl_services, services.size) else "")
                        .filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.85f),
                )
            }
        }
    }
    item(key = "tire-season") { TireSeasonBanner(nav, v, services) }
    item(key = "kpi") {
        val otherCost = services.sumOf { it.cost } + expenses.sumOf { it.amount }
        KpiSection(v, stats, otherCost, currentOdo, onEditOdometer) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                StatLine(str(R.string.stat_avg), Units.consumption(v, fuel, stats.avg))
                StatLine(str(R.string.stat_last), Units.consumption(v, fuel, stats.last))
                if (stats.avgElectric != null) {
                    StatLine(str(R.string.stat_avg_electric), Units.consumption(v, ELECTRIC, stats.avgElectric))
                }
                stats.batteryAvg?.let { b ->
                    StatLine(str(R.string.stat_battery), Units.consumption(v, ELECTRIC, b))
                    val grid = if (fuel == ELECTRIC) stats.avg else stats.avgElectric
                    if (grid != null && grid > b) StatLine(str(R.string.stat_charge_loss), "≈ ${((grid / b - 1) * 100).fmt(0)} %")
                }
                stats.obcDeviation?.let { d ->
                    StatLine(
                        str(R.string.stat_obc, stats.obcCount),
                        (if (d >= 0) "+" else "−") + (kotlin.math.abs(d) * 100).fmt(1) + " %",
                    )
                }
                StatLine(str(R.string.stat_cost_per100, du), stats.costPer100?.let { "${it.fmt()} $cur" } ?: "–")
                StatLine(str(if (v.charges) R.string.stat_energy_cost else R.string.stat_fuel_cost), "${stats.totalCost.fmt()} $cur")
                StatLine(str(R.string.stat_service_cost), "${services.sumOf { it.cost }.fmt()} $cur")
                if (expenses.isNotEmpty()) StatLine(str(R.string.stat_other_cost), "${expenses.sumOf { it.amount }.fmt()} $cur")
                if (stats.distance > 0) {
                    StatLine(str(R.string.stat_total_per100, du), "${((stats.totalCost + otherCost) / stats.distance * 100).fmt()} $cur")
                }
                StatLine(str(if (fuel == ELECTRIC) R.string.stat_total_charged else R.string.stat_total_amount), "${stats.totalAmount.fmt(1)} $amt")
                if (fuel != ELECTRIC && stats.totalElectric > 0) StatLine(str(R.string.stat_total_charged), "${stats.totalElectric.fmt(1)} kWh")
                StatLine(str(R.string.stat_distance), stats.distance.dist(du))
                StatLine(str(R.string.odometer), currentOdo.dist(du))
            }
        }
    }
    // Verbrauchsverlauf im gewählten Zeitraum, chronologisch
    val withValue = refuels.filter { it.isCharge == (fuel == ELECTRIC) && stats.perRefuel[it.id] != null }
        .sortedWith(compareBy({ it.odometer }, { it.timestamp }))
    if (withValue.size >= 2) item {
        val range by ChartRange.state.collectAsStateWithLifecycle()
        val chartValues = range.apply(withValue) { it.timestamp }.mapNotNull { stats.perRefuel[it.id] }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ChartRangeChips()
            if (chartValues.size >= 2) {
                ConsumptionChart(chartValues, str(R.string.chart_title, plural(R.plurals.pl_full_refuels, chartValues.size)) + " · " + range.label()) {
                    Units.consumption(v, fuel, it)
                }
            } else {
                Text(str(R.string.range_too_few), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    item {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { SectionTitle(str(R.string.reminders)) }
            TextButton(onClick = { nav.navigate("reminderEdit/${v.id}/new") }) { Text(str(R.string.plus_new)) }
        }
    }
    if (reminders.isEmpty()) item {
        Text(str(R.string.no_reminders), style = MaterialTheme.typography.bodySmall)
    }
    items(reminders.sortedBy { it.state(currentOdo).urgency }, key = { "rem-" + it.id }) { r ->
        val st = r.state(currentOdo)
        val warn = st.overdue || st.warn
        val kmProgress = if (r.byKm) (currentOdo - r.lastDoneKm).toFloat() / r.intervalKm else 0f
        val dateProgress = r.dueDate?.let { d -> (now() - r.lastDoneDate).toFloat() / (d - r.lastDoneDate).coerceAtLeast(1) } ?: 0f
        val progress = maxOf(kmProgress, dateProgress).coerceIn(0f, 1f)
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
                        Text(ReminderChecker.describe(r, st, du), style = MaterialTheme.typography.bodySmall)
                    }
                    // HU erledigt: Eintrag mit Ergebnis und Prüfbericht statt nur abhaken
                    OutlinedButton(onClick = { if (r.isHu) nav.navigate("serviceEdit/${v.id}/new-hu") else onDone(r) }) { Text(str(R.string.done)) }
                }
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
    if (!v.isElectric) item(key = "oil") { OilCard(v, services, currentOdo) }
    refuels.firstOrNull()?.let { last ->
        item { SectionTitle(str(R.string.last_refuel)) }
        item(key = "last-refuel") { RefuelCard(v, last, stats.perRefuel[last.id], stats.avg) { openTab(1) } }
    }
    services.firstOrNull()?.let { last ->
        item { SectionTitle(str(R.string.last_service)) }
        item(key = "last-service") { ServiceCard(v, last) { openTab(2) } }
    }
}

/** Ein Jahr in der Betankungsliste mit fertiger Zusammenfassung (einmal berechnet, nicht bei jedem Neuzeichnen). */
class RefuelYear(val year: Int, val list: List<Refuel>, val summary: String)

fun groupRefuelsByYear(v: Vehicle, refuels: List<Refuel>, stats: Stats, distances: Map<String, Int> = emptyMap()): List<RefuelYear> {
    val unit = Units.amount(v, v.defaultFuelType)
    return refuels.groupBy { yearOfTs(it.timestamp) }.toSortedMap(compareByDescending { it }).map { (year, list) ->
        val per = list.mapNotNull { stats.perRefuel[it.id] }
        val avg = if (per.isNotEmpty()) Units.consumption(v, v.defaultFuelType, per.average()) else "–"
        RefuelYear(
            year, list,
            plural(R.plurals.pl_refuels, list.size) + " · " +
                (list.sumOf { distances[it.id] ?: 0 }.takeIf { it > 0 }?.let { it.dist(v.distanceUnit) + " · " } ?: "") +
                "${list.sumOf { it.amount }.fmt(1)} $unit · " +
                "${list.sumOf { it.totalPrice }.fmt()} ${v.currency} · Ø $avg",
        )
    }
}

private fun LazyListScope.refuelItems(nav: NavController, v: Vehicle, years: List<RefuelYear>, stats: Stats, distances: Map<String, Int>) {
    if (years.isEmpty()) {
        item {
            EmptyState(
                Icons.Default.LocalGasStation, str(R.string.empty_refuels_title),
                str(R.string.empty_refuels_text),
            )
        }
        return
    }
    years.forEach { y ->
        item(key = "y-${y.year}", contentType = "year") {
            Column(Modifier.padding(top = 8.dp)) {
                Text(y.year.toString(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Text(y.summary, style = MaterialTheme.typography.bodySmall)
            }
        }
        // Nur die sichtbaren Karten werden gezeichnet; gleiche Art (contentType) wird beim Scrollen wiederverwendet
        items(y.list, key = { it.id }, contentType = { "refuel" }) { r ->
            RefuelCard(v, r, stats.perRefuel[r.id], if (r.isCharge && stats.avgElectric != null) stats.avgElectric else stats.avg, distances[r.id]) {
                nav.navigate("refuelEdit/${v.id}/${r.id}")
            }
        }
    }
}

/** Hinweis „Winter-/Sommerreifen aufziehen“ – bis der Radwechsel eingetragen oder als erledigt markiert ist. */
@Composable
private fun TireSeasonBanner(nav: NavController, v: Vehicle, services: List<ServiceEntry>) {
    val ctx = LocalContext.current
    val sets by ctx.repo.tireSets(v.id).collectAsStateWithLifecycle(emptyList())
    var due by remember { mutableStateOf<TireSeason.Due?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(v.id, v.tireSeasonDone, sets, services, refresh) { due = TireSeason.due(ctx, v) }
    val d = due ?: return
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(TireSeason.text(d), style = MaterialTheme.typography.titleSmall)
            Text(str(R.string.tires_due_info), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { nav.navigate("serviceEdit/${v.id}/new-wheel") }) { Text(str(R.string.wheel_change)) }
                val scope = rememberCoroutineScope()
                TextButton(onClick = { scope.launch { TireSeason.markDone(ctx, v.id, d); refresh++ } }) { Text(str(R.string.tires_due_done)) }
            }
        }
    }
}

/** Radsätze mit Status (montiert/eingelagert) und letzter Profiltiefe. */
private fun LazyListScope.tireSetItems(nav: NavController, v: Vehicle, sets: List<TireSet>, services: List<ServiceEntry>) {
    val tireHistory = services.filter { it.category == "Reifen" }
    val mountedId = tireHistory.filter { it.tireSetId.isNotBlank() }.maxByOrNull { it.timestamp }?.tireSetId
    item(key = "tires-head") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { SectionTitle(str(R.string.tire_sets)) }
            if (sets.isNotEmpty()) TextButton(onClick = { nav.navigate("serviceEdit/${v.id}/new-wheel") }) { Text(str(R.string.wheel_change)) }
            TextButton(onClick = { nav.navigate("tireSetEdit/${v.id}/new") }) { Text(str(R.string.plus_new)) }
        }
    }
    if (sets.isEmpty()) {
        item(key = "tires-empty") {
            Text(
                str(R.string.tire_sets_empty),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    items(sets, key = { "set-" + it.id }) { set ->
        TireSetCard(v, set, mounted = set.id == mountedId, tread = lastTreadOf(set, tireHistory)) {
            nav.navigate("tireSetEdit/${v.id}/${set.id}")
        }
    }
    item(key = "svc-head") { SectionTitle(str(R.string.service_history)) }
}

private fun LazyListScope.serviceItems(
    nav: NavController,
    v: Vehicle,
    all: List<ServiceEntry>,
    filter: Map<String, Boolean>,
    lineColor: Color,
    dotColor: Color,
    onFilter: (Map<String, Boolean>) -> Unit,
) {
    if (all.isEmpty()) {
        item {
            EmptyState(
                Icons.Default.Build, str(R.string.empty_services_title),
                str(R.string.empty_services_text),
            )
        }
        return
    }
    item(key = "svc-filter") { ServiceFilterChips(all, filter, onFilter) }
    val services = ServiceFilter.apply(all, filter)
    if (services.isEmpty()) {
        item(key = "svc-none") { Text(str(R.string.filter_none), style = MaterialTheme.typography.bodyMedium) }
        return
    }
    item {
        Text(
            str(R.string.services_summary, plural(R.plurals.pl_entries, services.size), services.sumOf { it.cost }.fmt() + " " + v.currency),
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
