package de.spritbuch.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.LocalCarWash
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.OilBarrel
import androidx.compose.material.icons.filled.TireRepair
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import java.util.Calendar

fun serviceIcon(category: String): ImageVector = when (category) {
    "Wartung" -> Icons.Default.OilBarrel
    "Reparatur" -> Icons.Default.Build
    "Reifen" -> Icons.Default.TireRepair
    "HU/AU" -> Icons.Default.Verified
    "Pflege" -> Icons.Default.LocalCarWash
    "Zubehör" -> Icons.Default.Extension
    else -> Icons.Default.MoreHoriz
}

private fun yearOf(ts: Long) = Calendar.getInstance().apply { timeInMillis = ts }.get(Calendar.YEAR)

@Composable
fun ServiceCard(v: Vehicle, s: ServiceEntry, modifier: Modifier = Modifier.fillMaxWidth(), onClick: () -> Unit) {
    Card(onClick = onClick, modifier = modifier) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(serviceIcon(s.category), contentDescription = s.category, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row {
                    Text(s.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    if (s.cost > 0) Text("${s.cost.fmt()} ${v.currency}", fontWeight = FontWeight.SemiBold)
                }
                Text(
                    listOf(s.timestamp.date(), if (s.odometer > 0) s.odometer.dist(v.distanceUnit) else "", s.category)
                        .filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (s.workshop.isNotBlank()) Text(s.workshop, style = MaterialTheme.typography.bodySmall)
                if (s.treadDepth.isNotBlank()) {
                    Text("Profil: " + TreadCodec.summary(s.treadDepth), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary)
                }
                if (s.note.isNotBlank()) Text(s.note, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServiceTimelineScreen(nav: NavController, vehicleId: String) {
    val repo = LocalContext.current.repo
    val vehicle by repo.vehicle(vehicleId).collectAsStateWithLifecycle(null)
    val services by repo.services(vehicleId).collectAsStateWithLifecycle(emptyList())
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var pending by remember { mutableStateOf<ServiceImport.Result?>(null) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
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
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    val dotColor = MaterialTheme.colorScheme.primary
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Service – ${vehicle?.name ?: ""}") },
                navigationIcon = { BackButton(nav) },
                actions = {
                    IconButton(onClick = {
                        importLauncher.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel", "application/octet-stream"))
                    }) { Icon(Icons.Default.FileUpload, contentDescription = "Service importieren") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { nav.navigate("serviceEdit/$vehicleId/new") },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Eintrag") },
            )
        },
    ) { pad ->
        val v = vehicle ?: return@Scaffold
        if (services.isEmpty()) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Noch keine Service-Einträge.")
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.padding(pad),
            contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
        ) {
            item {
                Text(
                    "${services.size} Einträge · ${services.sumOf { it.cost }.fmt()} ${v.currency} gesamt",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            services.forEachIndexed { i, s ->
                val year = yearOf(s.timestamp)
                if (i == 0 || yearOf(services[i - 1].timestamp) != year) {
                    item(key = "year-$year-$i") {
                        Text(
                            year.toString(),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 8.dp),
                        )
                    }
                }
                item(key = s.id) {
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                        Canvas(Modifier.width(28.dp).fillMaxHeight()) {
                            val cx = size.width / 2f
                            val dotY = 26.dp.toPx()
                            if (i > 0) drawLine(lineColor, Offset(cx, 0f), Offset(cx, dotY), strokeWidth = 2.dp.toPx())
                            if (i < services.lastIndex) {
                                drawLine(lineColor, Offset(cx, dotY), Offset(cx, size.height), strokeWidth = 2.dp.toPx())
                            }
                            drawCircle(dotColor, radius = 6.dp.toPx(), center = Offset(cx, dotY))
                        }
                        ServiceCard(v, s, Modifier.weight(1f).padding(bottom = 12.dp)) {
                            nav.navigate("serviceEdit/$vehicleId/${s.id}")
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServiceEditScreen(nav: NavController, vehicleId: String, serviceId: String) {
    val ctx = LocalContext.current
    val repo = ctx.repo
    val scope = rememberCoroutineScope()
    val isNew = serviceId == "new"
    var base by remember { mutableStateOf<ServiceEntry?>(null) }
    var veh by remember { mutableStateOf<Vehicle?>(null) }
    var timestamp by remember { mutableLongStateOf(now()) }
    var odometer by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Wartung") }
    var cost by remember { mutableStateOf("") }
    var workshop by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val treadOld = remember { mutableStateMapOf<String, String>() }
    val treadNew = remember { mutableStateMapOf<String, String>() }
    var error by remember { mutableStateOf<String?>(null) }
    var showDate by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(serviceId) {
        veh = repo.getVehicle(vehicleId)
        val s = if (isNew) ServiceEntry(vehicleId = vehicleId, odometer = repo.currentOdometer(vehicleId))
        else repo.getService(serviceId) ?: return@LaunchedEffect
        base = s
        timestamp = s.timestamp
        odometer = if (s.odometer > 0) s.odometer.toString() else ""
        title = s.title
        category = s.category
        cost = if (s.cost > 0) s.cost.fmt(2) else ""
        workshop = s.workshop
        note = s.note
        TreadCodec.parse(s.treadDepth).forEach { (k, t) ->
            t.old?.let { treadOld[k] = it.fmt(1) }
            t.new?.let { treadNew[k] = it.fmt(1) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "Neuer Service-Eintrag" else "Service-Eintrag") },
                navigationIcon = { BackButton(nav) },
                actions = {
                    if (!isNew) IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "Löschen")
                    }
                    IconButton(onClick = {
                        val b = base ?: return@IconButton
                        if (title.isBlank()) { error = "Bitte eine Bezeichnung eingeben"; return@IconButton }
                        scope.launch {
                            repo.saveService(
                                b.copy(
                                    timestamp = timestamp, odometer = odometer.toIntKm() ?: 0, title = title.trim(),
                                    category = category, cost = cost.toDoubleDe() ?: 0.0,
                                    workshop = workshop.trim(), note = note.trim(),
                                    treadDepth = if (category == "Reifen") {
                                        TreadCodec.encode(
                                            Options.tirePositions(veh?.vehicleType).associate { (k, _) ->
                                                k to Tread(treadOld[k]?.toDoubleDe(), treadNew[k]?.toDoubleDe())
                                            }
                                        )
                                    } else b.treadDepth,
                                )
                            )
                            nav.popBackStack()
                        }
                    }) { Icon(Icons.Default.Check, contentDescription = "Speichern") }
                },
            )
        },
    ) { pad ->
        if (base == null) return@Scaffold
        Column(
            Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedButton(onClick = { showDate = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.CalendarToday, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(timestamp.date())
            }
            SuggestField("Was wurde gemacht?", title, Options.serviceSuggestions) { title = it }
            ChoiceField("Kategorie", category, Options.serviceCategories) { category = it }
            NumberField("Kilometerstand", odometer, { odometer = it }, suffix = Units.dist(veh), decimal = false)
            NumberField("Kosten", cost, { cost = it }, suffix = Units.cur(veh))
            if (category == "Reifen") {
                SectionTitle("Profiltiefe")
                Text(
                    "Alt = Profil der ausgebauten Reifen bzw. vor der Messung, neu = eingebaute Reifen. Felder dürfen leer bleiben.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Options.tirePositions(veh?.vehicleType).forEach { (key, label) ->
                    Text(label, style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumberField("Alt", treadOld[key] ?: "", { treadOld[key] = it }, suffix = "mm", modifier = Modifier.weight(1f))
                        NumberField("Neu", treadNew[key] ?: "", { treadNew[key] = it }, suffix = "mm", modifier = Modifier.weight(1f))
                    }
                }
            }
            OutlinedTextField(
                workshop, { workshop = it }, label = { Text("Werkstatt") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                note, { note = it }, label = { Text("Notiz (Teile, Rechnungsnummer …)") }, minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Gibt es eine Erinnerung mit gleichem Namen (z. B. Ölwechsel), wird sie mit diesem Kilometerstand als erledigt markiert.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    if (showDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = toUtcDate(timestamp))
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { timestamp = combineDate(it, timestamp) }
                    showDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text("Abbrechen") } },
        ) { DatePicker(state = state) }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Service-Eintrag löschen?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch { repo.deleteService(serviceId); nav.popBackStack() }
                }) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen") } },
        )
    }
}
