package de.spritbuch.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileUpload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VehicleListScreen(nav: NavController) {
    val ctx = LocalContext.current
    val repo = ctx.repo
    val vehicles by repo.vehicles.collectAsStateWithLifecycle(emptyList())
    val refuels by repo.allRefuels.collectAsStateWithLifecycle(emptyList())
    val syncing by SyncManager.running.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var showArchived by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Spritbuch") },
                actions = {
                    IconButton(
                        onClick = { scope.launch { snackbar.showSnackbar(SyncManager.sync(ctx).message) } },
                        enabled = !syncing,
                    ) { Icon(Icons.Default.Sync, contentDescription = "Synchronisieren") }
                    IconButton(onClick = { nav.navigate("settings") }) {
                        Icon(Icons.Default.Settings, contentDescription = "Einstellungen")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { nav.navigate("vehicleEdit/new") },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Fahrzeug") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        if (vehicles.isEmpty()) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Noch kein Fahrzeug angelegt.")
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(pad),
                contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val active = vehicles.filter { !it.archived }
                val archived = vehicles.filter { it.archived }
                if (active.isEmpty()) item { Text("Alle Fahrzeuge sind archiviert.") }
                items(active, key = { it.id }) { v -> VehicleCard(nav, v, refuels) }
                if (archived.isNotEmpty()) {
                    item {
                        TextButton(onClick = { showArchived = !showArchived }) {
                            Text(if (showArchived) "Archiv ausblenden" else "Archiv anzeigen (${archived.size})")
                        }
                    }
                    if (showArchived) items(archived, key = { it.id }) { v -> VehicleCard(nav, v, refuels) }
                }
            }
        }
    }
}

@Composable
private fun VehicleCard(nav: NavController, v: Vehicle, refuels: List<Refuel>) {
    val stats = remember(refuels, v.id) { computeStats(refuels.filter { it.vehicleId == v.id }) }
    val unit = Options.unit(v.defaultFuelType)
    Card(
        onClick = { nav.navigate("vehicle/${v.id}") },
        modifier = Modifier.fillMaxWidth(),
        colors = if (v.archived) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        else CardDefaults.cardColors(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            VehiclePhoto(v, Modifier.size(72.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(v.name + if (v.archived) " (archiviert)" else "", style = MaterialTheme.typography.titleMedium)
                if (v.plate.isNotBlank()) Text(v.plate, style = MaterialTheme.typography.bodyMedium)
                Text(
                    stats.avg?.let { "Ø ${it.fmt()} $unit/100 km" } ?: "Noch kein Verbrauch berechenbar",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VehicleDetailScreen(nav: NavController, vehicleId: String) {
    val ctx = LocalContext.current
    val repo = ctx.repo
    val scope = rememberCoroutineScope()
    val vehicle by repo.vehicle(vehicleId).collectAsStateWithLifecycle(null)
    val refuels by repo.refuels(vehicleId).collectAsStateWithLifecycle(emptyList())
    val reminders by repo.reminders(vehicleId).collectAsStateWithLifecycle(emptyList())
    var odoDialog by remember { mutableStateOf(false) }
    val stats = remember(refuels) { computeStats(refuels) }
    val v = vehicle
    val currentOdo = if (v == null) 0 else maxOf(v.initialOdometer, v.manualOdometer, refuels.maxOfOrNull { it.odometer } ?: 0)
    val unit = Options.unit(v?.defaultFuelType ?: "")
    val snackbar = remember { SnackbarHostState() }
    var pendingImport by remember { mutableStateOf<ImportResult?>(null) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val veh = vehicle
        if (uri != null && veh != null) scope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
                    CsvImport.parse(CsvImport.decode(bytes), veh)
                }
            }
            res.onSuccess { pendingImport = it }
                .onFailure { snackbar.showSnackbar("Import fehlgeschlagen: ${it.message}") }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(v?.name ?: "") },
                navigationIcon = { BackButton(nav) },
                actions = {
                    IconButton(onClick = { importLauncher.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel", "application/octet-stream")) }) {
                        Icon(Icons.Default.FileUpload, contentDescription = "Spritmonitor-Import")
                    }
                    IconButton(onClick = { nav.navigate("vehicleEdit/$vehicleId") }) {
                        Icon(Icons.Default.Edit, contentDescription = "Bearbeiten")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { nav.navigate("refuelEdit/$vehicleId/new") },
                icon = { Icon(Icons.Default.LocalGasStation, contentDescription = null) },
                text = { Text("Tanken") },
            )
        },
    ) { pad ->
        if (v == null || v.deleted) return@Scaffold
        LazyColumn(
            modifier = Modifier.padding(pad),
            contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (v.archived) item {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Fahrzeug ist archiviert. Erinnerungen sind pausiert.", modifier = Modifier.weight(1f))
                        TextButton(onClick = { scope.launch { repo.saveVehicle(v.copy(archived = false)) } }) {
                            Text("Reaktivieren")
                        }
                    }
                }
            }
            if (v.photoVersion > 0) item { VehiclePhoto(v, Modifier.fillMaxWidth().height(200.dp)) }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        StatRow("Ø Verbrauch", stats.avg?.let { "${it.fmt()} $unit/100 km" } ?: "–")
                        StatRow("Letzter Verbrauch", stats.last?.let { "${it.fmt()} $unit/100 km" } ?: "–")
                        StatRow("Kosten pro 100 km", stats.costPer100?.let { "${it.fmt()} €" } ?: "–")
                        StatRow("Gesamtkosten", "${stats.totalCost.fmt()} €")
                        StatRow("Getankt gesamt", "${stats.totalAmount.fmt(1)} $unit")
                        StatRow("Erfasste Strecke", stats.distance.km())
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Kilometerstand", modifier = Modifier.weight(1f))
                            Text(currentOdo.km(), fontWeight = FontWeight.SemiBold)
                            IconButton(onClick = { odoDialog = true }) {
                                Icon(Icons.Default.Edit, contentDescription = "Kilometerstand ändern")
                            }
                        }
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { SectionTitle("Erinnerungen") }
                    TextButton(onClick = { nav.navigate("reminderEdit/$vehicleId/new") }) { Text("+ Neu") }
                }
            }
            if (reminders.isEmpty()) item {
                Text("Keine Erinnerungen angelegt.", style = MaterialTheme.typography.bodySmall)
            }
            items(reminders, key = { it.id }) { r ->
                val remaining = r.dueKm - currentOdo
                val due = remaining <= 0
                val warn = remaining <= r.warnBeforeKm
                Card(
                    onClick = { nav.navigate("reminderEdit/$vehicleId/${r.id}") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = if (warn) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    else CardDefaults.cardColors(),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.title, style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (due) "Fällig seit ${(-remaining).km()} (bei ${r.dueKm.km()})"
                                else "Noch ${remaining.km()} (fällig bei ${r.dueKm.km()})",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text("Intervall ${r.intervalKm.km()}", style = MaterialTheme.typography.bodySmall)
                        }
                        OutlinedButton(onClick = {
                            scope.launch { repo.saveReminder(r.copy(lastDoneKm = currentOdo)) }
                        }) { Text("Erledigt") }
                    }
                }
            }
            item { SectionTitle("Tankvorgänge (${refuels.size})") }
            items(refuels, key = { it.id }) { r ->
                RefuelCard(r, stats.perRefuel[r.id]) { nav.navigate("refuelEdit/$vehicleId/${r.id}") }
            }
        }
    }

    pendingImport?.let { imp ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("Import") },
            text = {
                Text(
                    if (imp.missingColumns.isNotEmpty()) {
                        "Die Datei konnte nicht gelesen werden. Fehlende Spalten: ${imp.missingColumns.joinToString()}"
                    } else {
                        val first = imp.refuels.minByOrNull { it.timestamp }?.timestamp?.date() ?: "–"
                        val last = imp.refuels.maxByOrNull { it.timestamp }?.timestamp?.date() ?: "–"
                        "${imp.refuels.size} Tankvorgänge gefunden ($first bis $last)" +
                            (if (imp.skipped > 0) ", ${imp.skipped} Zeilen ohne Tankdaten übersprungen (z. B. Kosten, Wartung)" else "") +
                            ".\n\nIn „${v?.name ?: ""}“ importieren? Bereits vorhandene Einträge werden nicht doppelt angelegt."
                    }
                )
            },
            confirmButton = {
                if (imp.refuels.isNotEmpty()) TextButton(onClick = {
                    pendingImport = null
                    scope.launch {
                        val (added, dups) = repo.importRefuels(vehicleId, imp.refuels)
                        snackbar.showSnackbar("$added importiert" + if (dups > 0) ", $dups Duplikate übersprungen" else "")
                    }
                }) { Text("Importieren") }
            },
            dismissButton = { TextButton(onClick = { pendingImport = null }) { Text("Abbrechen") } },
        )
    }

    if (odoDialog) {
        var text by remember { mutableStateOf(currentOdo.toString()) }
        AlertDialog(
            onDismissRequest = { odoDialog = false },
            title = { Text("Kilometerstand") },
            text = { NumberField("Aktueller Stand", text, { text = it }, suffix = "km", decimal = false) },
            confirmButton = {
                TextButton(onClick = {
                    text.toIntKm()?.let { km -> scope.launch { repo.setManualOdometer(vehicleId, km) } }
                    odoDialog = false
                }) { Text("Speichern") }
            },
            dismissButton = { TextButton(onClick = { odoDialog = false }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row {
        Text(label, modifier = Modifier.weight(1f))
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun RefuelCard(r: Refuel, consumption: Double?, onClick: () -> Unit) {
    val unit = Options.unit(r.fuelType)
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row {
                Text(r.timestamp.dateTime(), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (consumption != null) {
                    Text("${consumption.fmt()} $unit/100 km", style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
            Text("${r.odometer.km()} · ${r.amount.fmt(2)} $unit · ${r.totalPrice.fmt(2)} € (${r.pricePerUnit.fmt(3)} €/$unit)")
            val station = listOf(r.stationBrand, r.fuelType).filter { it.isNotBlank() }.joinToString(" · ")
            if (station.isNotBlank()) Text(station, style = MaterialTheme.typography.bodyMedium)
            if (r.stationAddress.isNotBlank()) Text(r.stationAddress, style = MaterialTheme.typography.bodySmall)
            val cond = (listOf(r.tireType, r.drivingStyle, r.routeType) + r.extrasList()).filter { it.isNotBlank() }
            if (cond.isNotEmpty()) Text(cond.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            if (!r.fullTank) Text("Teilbetankung", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            if (r.missedPrevious) Text("Vorherige Tankung fehlt", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VehicleEditScreen(nav: NavController, vehicleId: String) {
    val ctx = LocalContext.current
    val repo = ctx.repo
    val scope = rememberCoroutineScope()
    val isNew = vehicleId == "new"
    var base by remember { mutableStateOf<Vehicle?>(null) }
    var name by remember { mutableStateOf("") }
    var plate by remember { mutableStateOf("") }
    var fuel by remember { mutableStateOf("Super E10") }
    var tank by remember { mutableStateOf("") }
    var initialOdo by remember { mutableStateOf("") }
    var photoVersion by remember { mutableLongStateOf(0L) }
    var archived by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(vehicleId) {
        val v = if (isNew) Vehicle() else repo.getVehicle(vehicleId) ?: Vehicle()
        base = v
        name = v.name; plate = v.plate; fuel = v.defaultFuelType
        tank = v.tankCapacity?.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.fmt(1) } ?: ""
        initialOdo = if (v.initialOdometer > 0) v.initialOdometer.toString() else ""
        photoVersion = v.photoVersion
        archived = v.archived
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val b = base
        if (uri != null && b != null) scope.launch {
            runCatching { PhotoStore.import(ctx, uri, b.id) }
                .onSuccess { photoVersion = it }
                .onFailure { error = it.message }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "Neues Fahrzeug" else "Fahrzeug bearbeiten") },
                navigationIcon = { BackButton(nav) },
                actions = {
                    if (!isNew) IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "Löschen")
                    }
                    IconButton(onClick = {
                        val b = base ?: return@IconButton
                        if (name.isBlank()) { error = "Bitte einen Namen eingeben"; return@IconButton }
                        scope.launch {
                            repo.saveVehicle(
                                b.copy(
                                    name = name.trim(), plate = plate.trim(), defaultFuelType = fuel,
                                    tankCapacity = tank.toDoubleDe(), initialOdometer = initialOdo.toIntKm() ?: 0,
                                    photoVersion = photoVersion, archived = archived,
                                )
                            )
                            if (isNew) nav.navigate("vehicle/${b.id}") { popUpTo("vehicles") } else nav.popBackStack()
                        }
                    }) { Icon(Icons.Default.Check, contentDescription = "Speichern") }
                },
            )
        },
    ) { pad ->
        val b = base ?: return@Scaffold
        Column(
            Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            VehiclePhoto(b.copy(photoVersion = photoVersion), Modifier.fillMaxWidth().height(200.dp))
            OutlinedButton(
                onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (photoVersion > 0) "Foto ändern" else "Foto auswählen") }
            OutlinedTextField(name, { name = it }, label = { Text("Name (z. B. Golf)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(plate, { plate = it }, label = { Text("Kennzeichen") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            ChoiceField("Standard-Kraftstoff", fuel, Options.fuelTypes) { fuel = it }
            NumberField("Tankinhalt", tank, { tank = it }, suffix = Options.unit(fuel))
            NumberField("Kilometerstand bei Erfassungsbeginn", initialOdo, { initialOdo = it }, suffix = "km", decimal = false)
            if (!isNew) {
                LabeledSwitch("Archiviert (z. B. verkauft)", archived) { archived = it }
                Text(
                    "Archivierte Fahrzeuge bleiben mit allen Daten erhalten, erscheinen aber unten im Archiv und lösen keine Erinnerungen aus.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Fahrzeug löschen?") },
            text = { Text("Alle Tankvorgänge und Erinnerungen dieses Fahrzeugs werden auf allen Geräten gelöscht. Zum Aufbewahren stattdessen „Archiviert“ einschalten.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        repo.deleteVehicle(vehicleId)
                        nav.popBackStack("vehicles", false)
                    }
                }) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen") } },
        )
    }
}
