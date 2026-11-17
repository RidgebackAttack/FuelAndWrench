package de.spritbuch.app

import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.alpha
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
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Map
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
import androidx.compose.runtime.mutableStateMapOf
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
    val allReminders by repo.allReminders.collectAsStateWithLifecycle(emptyList())
    val syncing by SyncManager.running.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var showArchived by remember { mutableStateOf(false) }
    val update by UpdateChecker.available.collectAsStateWithLifecycle()
    var dismissedUpdate by remember { mutableStateOf(Prefs(ctx).dismissedUpdate) }
    LaunchedEffect(Unit) { UpdateChecker.check(ctx) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Fuel & Wrench") },
                actions = {
                    IconButton(
                        onClick = { scope.launch { snackbar.showSnackbar(SyncManager.sync(ctx).message) } },
                        enabled = !syncing,
                    ) { Icon(Icons.Default.Sync, contentDescription = str(R.string.sync_now)) }
                    IconButton(onClick = { nav.navigate("settings") }) {
                        Icon(Icons.Default.Settings, contentDescription = str(R.string.settings))
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { nav.navigate("vehicleEdit/new") },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(str(R.string.vehicle)) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        if (vehicles.isEmpty()) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    Icons.Default.DirectionsCar, str(R.string.welcome_title),
                    str(R.string.welcome_text),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(pad),
                contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                update?.takeIf { it.version != dismissedUpdate }?.let { u ->
                    item(key = "update") {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(str(R.string.update_available, u.version), style = MaterialTheme.typography.titleSmall)
                                Row {
                                    TextButton(onClick = { UpdateChecker.open(ctx, u) }) { Text(str(R.string.update_download)) }
                                    TextButton(onClick = { Prefs(ctx).dismissedUpdate = u.version; dismissedUpdate = u.version }) {
                                        Text(str(R.string.later))
                                    }
                                }
                            }
                        }
                    }
                }
                val active = vehicles.filter { !it.archived }
                val archived = vehicles.filter { it.archived }
                if (active.isEmpty()) item { Text(str(R.string.all_archived)) }
                items(active, key = { it.id }) { v -> VehicleHeroCard(nav, v, refuels, allReminders) }
                if (archived.isNotEmpty()) {
                    item(key = "archive-header") {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            Text(
                                str(R.string.archive_title, archived.size),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { showArchived = !showArchived }) {
                                Text(str(if (showArchived) R.string.archive_compact else R.string.archive_expand))
                            }
                        }
                    }
                    if (showArchived) {
                        items(archived, key = { it.id }) { v -> VehicleHeroCard(nav, v, refuels, allReminders) }
                    } else {
                        items(archived, key = { it.id }) { v -> ArchivedVehicleRow(nav, v, refuels) }
                    }
                }
            }
        }
    }
}

/** Archivierte Fahrzeuge zugeklappt: klein und ausgegraut, antippen öffnet das Fahrzeug. */
@Composable
private fun ArchivedVehicleRow(nav: NavController, v: Vehicle, refuels: List<Refuel>) {
    val last = remember(refuels, v.id) { refuels.filter { it.vehicleId == v.id }.maxByOrNull { it.timestamp } }
    Card(
        onClick = { nav.navigate("vehicle/${v.id}") },
        modifier = Modifier.fillMaxWidth().alpha(0.6f),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            VehiclePhoto(
                v,
                Modifier.size(36.dp)
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        // Foto in Graustufen
                        drawRect(Color.Gray, blendMode = BlendMode.Saturation)
                    },
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(v.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                last?.let {
                    Text(str(R.string.archived_last, it.timestamp.date()), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
            Icon(Icons.Default.Inventory2, contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun VehicleCard(nav: NavController, v: Vehicle, refuels: List<Refuel>) {
    val stats = remember(refuels, v.id) { computeStats(refuels.filter { it.vehicleId == v.id }) }
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
                Text(v.name + if (v.archived) " (" + str(R.string.archived_lower) + ")" else "", style = MaterialTheme.typography.titleMedium)
                if (v.plate.isNotBlank()) Text(v.plate, style = MaterialTheme.typography.bodyMedium)
                Text(
                    stats.avg?.let { "Ø " + Units.consumption(v, v.defaultFuelType, it) } ?: str(R.string.no_consumption_yet),
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
    val services by repo.services(vehicleId).collectAsStateWithLifecycle(emptyList())
    var odoDialog by remember { mutableStateOf(false) }
    val stats = remember(refuels, vehicle?.batteryCapacity) { computeStats(refuels, vehicle?.batteryCapacity) }
    val v = vehicle
    val currentOdo = if (v == null) 0 else maxOf(v.initialOdometer, v.manualOdometer, refuels.maxOfOrNull { it.odometer } ?: 0)
    val fuel = v?.defaultFuelType ?: ""
    val amt = Units.amount(v, fuel)
    val du = Units.dist(v)
    val cur = Units.cur(v)
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vehicleId) { repo.ensureDefaultReminders(vehicleId) }
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
                .onFailure { snackbar.showSnackbar(str(R.string.import_failed, it.message ?: "")) }
        }
    }

    VehicleDetailContent(
        nav = nav,
        vehicleId = vehicleId,
        vehicle = v,
        refuels = refuels,
        reminders = reminders,
        services = services,
        stats = stats,
        currentOdo = currentOdo,
        snackbar = snackbar,
        onImportRefuels = {
            importLauncher.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel", "application/octet-stream"))
        },
        onEditOdometer = { odoDialog = true },
    )

    pendingImport?.let { imp ->
        val mapping = remember(imp) {
            mutableStateMapOf<String, String>().apply {
                imp.codes.forEach { (f, list) -> list.forEach { put("${f.name}:${it.raw}", it.suggestion) } }
            }
        }
        val rates = remember(imp) {
            mutableStateMapOf<String, String>().apply {
                imp.foreignCurrencies.keys.forEach { put(it, CsvImport.suggestedRate(v?.currency ?: "€", it)) }
            }
        }
        var extrasForAll by remember(imp) { mutableStateOf(setOf<String>()) }
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text(str(R.string.import_title)) },
            text = {
              Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (imp.missingColumns.isNotEmpty()) {
                        str(R.string.import_missing_cols, imp.missingColumns.joinToString())
                    } else {
                        val first = imp.refuels.minByOrNull { it.timestamp }?.timestamp?.date() ?: "–"
                        val last = imp.refuels.maxByOrNull { it.timestamp }?.timestamp?.date() ?: "–"
                        str(R.string.import_found, imp.refuels.size, first, last) +
                            (if (imp.skipped > 0) str(R.string.import_skipped, imp.skipped) else "") +
                            ".\n\n" + str(R.string.import_confirm, v?.name ?: "")
                    }
                )
                val hints = buildList {
                    if (imp.fileDuplicates > 0) add(str(R.string.import_hint_dups, imp.fileDuplicates))
                    if (imp.derivedOdometers > 0) add(str(R.string.import_hint_derived, imp.derivedOdometers))
                    if (imp.correctedPrices > 0) add(str(R.string.import_hint_corrected, imp.correctedPrices))
                    if (imp.missingPrices > 0) add(str(R.string.import_hint_noprice, imp.missingPrices))
                }
                if (hints.isNotEmpty()) {
                    Text(hints.joinToString("\n") { "• $it" }, style = MaterialTheme.typography.bodySmall)
                }
                if (imp.codes.isNotEmpty() && imp.refuels.isNotEmpty()) {
                    Text(
                        str(R.string.import_codes_info),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    imp.codes.forEach { (field, list) ->
                        SectionTitle(field.label)
                        list.forEach { c ->
                            val key = "${field.name}:${c.raw}"
                            val none = str(R.string.not_specified)
                            ChoiceField(
                                str(R.string.import_code, c.raw, c.count),
                                (mapping[key] ?: "").ifEmpty { none },
                                field.options.map { it.ifEmpty { none } },
                            ) { sel -> mapping[key] = if (sel == none) "" else sel }
                        }
                    }
                }
                if (imp.foreignCurrencies.isNotEmpty() && imp.refuels.isNotEmpty()) {
                    SectionTitle(str(R.string.foreign_currency))
                    imp.foreignCurrencies.forEach { (code, n) ->
                        NumberField(
                            "1 ${v?.currency ?: "€"} = … $code ($n×)",
                            rates[code] ?: "",
                            { rates[code] = it },
                            suffix = code,
                        )
                    }
                    Text(
                        str(R.string.foreign_currency_info),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (imp.refuels.isNotEmpty()) {
                    SectionTitle(str(R.string.import_extras_all))
                    Text(
                        str(R.string.import_extras_info),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    MultiChips(Options.extras, extrasForAll) { extrasForAll = it }
                }
              }
            },
            confirmButton = {
                if (imp.refuels.isNotEmpty()) TextButton(onClick = {
                    pendingImport = null
                    scope.launch {
                        val map = CodeField.entries.associateWith { f ->
                            imp.codes[f].orEmpty().associate { it.raw to (mapping["${f.name}:${it.raw}"] ?: "") }
                        }
                        val rateValues = rates.mapValues { (_, txt) -> txt.toDoubleDe() }
                        val (added, dups) = repo.importRefuels(vehicleId, imp.finalize(map, rateValues, extrasForAll))
                        snackbar.showSnackbar(str(R.string.imported_n, added) + if (dups > 0) str(R.string.dups_skipped, dups) else "")
                    }
                }) { Text(str(R.string.do_import)) }
            },
            dismissButton = { TextButton(onClick = { pendingImport = null }) { Text(str(R.string.cancel)) } },
        )
    }

    if (odoDialog) {
        var text by remember { mutableStateOf(currentOdo.toString()) }
        AlertDialog(
            onDismissRequest = { odoDialog = false },
            title = { Text(str(R.string.odometer)) },
            text = { NumberField(str(R.string.current_reading), text, { text = it }, suffix = du, decimal = false) },
            confirmButton = {
                TextButton(onClick = {
                    text.toIntKm()?.let { km -> scope.launch { repo.setManualOdometer(vehicleId, km) } }
                    odoDialog = false
                }) { Text(str(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { odoDialog = false }) { Text(str(R.string.cancel)) } },
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
fun RefuelCard(v: Vehicle, r: Refuel, consumption: Double?, avg: Double? = null, onClick: () -> Unit) {
    val unit = Units.amount(v, r.fuelType)
    val cur = Units.cur(v)
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row {
                Text(r.timestamp.dateTime(), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (consumption != null) ConsumptionBadge(Units.consumption(v, r.fuelType, consumption), consumption, avg)
            }
            Text("${r.odometer.dist(v.distanceUnit)} · ${r.amount.fmt(2)} $unit · ${r.totalPrice.fmt(2)} $cur (${r.pricePerUnit.fmt(3)} $cur/$unit)")
            r.obcConsumption?.let { o ->
                val diff = consumption?.takeIf { it > 0 }?.let { c ->
                    val d = (o / c - 1.0) * 100
                    " (" + (if (d >= 0) "+" else "−") + kotlin.math.abs(d).fmt(1) + " %)"
                } ?: ""
                Text(str(R.string.obc_value, Units.consumption(v, r.fuelType, o) + diff), style = MaterialTheme.typography.bodySmall)
            }
            val station = (if (r.isCharge) listOf("⚡ " + str(R.string.charge), r.stationBrand, r.chargeLocation) else listOf(r.stationBrand, r.fuelType))
                .filter { it.isNotBlank() }.joinToString(" · ") { lbl(it) }
            if (r.socStart != null || r.socEnd != null) {
                Text(str(R.string.soc_range, r.socStart?.toString() ?: "?", r.socEnd?.toString() ?: "?"), style = MaterialTheme.typography.bodySmall)
            }
            if (station.isNotBlank()) Text(station, style = MaterialTheme.typography.bodyMedium)
            if (r.stationAddress.isNotBlank()) Text(r.stationAddress, style = MaterialTheme.typography.bodySmall)
            val cond = (listOf(r.tireType, r.drivingStyle, r.routeType) + r.extrasList()).filter { it.isNotBlank() }
            if (cond.isNotEmpty()) Text(cond.joinToString(" · ") { lbl(it) }, style = MaterialTheme.typography.bodySmall)
            if (!r.fullTank) Text(str(if (r.isCharge) R.string.partial_charge else R.string.partial_refuel), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            if (r.missedPrevious) Text(str(R.string.previous_missing), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
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
    var thumbPos by remember { mutableStateOf(PhotoPos()) }
    var largePos by remember { mutableStateOf(PhotoPos()) }
    var distU by remember { mutableStateOf("km") }
    var vType by remember { mutableStateOf("Auto") }
    var drive by remember { mutableStateOf("Verbrenner") }
    var battery by remember { mutableStateOf("") }
    var volU by remember { mutableStateOf("l") }
    var fmtU by remember { mutableStateOf("per100") }
    var cur by remember { mutableStateOf("€") }
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
        thumbPos = PhotoPos.parse(v.photoThumbPos); largePos = PhotoPos.parse(v.photoLargePos)
        distU = v.distanceUnit; volU = v.volumeUnit; fmtU = v.consumptionFormat; cur = v.currency
        vType = v.vehicleType
        drive = if (v.driveType == "Verbrenner" && v.defaultFuelType == ELECTRIC) "Elektro" else v.driveType
        battery = v.batteryCapacity?.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.fmt(1) } ?: ""
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val b = base
        if (uri != null && b != null) scope.launch {
            runCatching { PhotoStore.import(ctx, uri, b.id) }
                .onSuccess { photoVersion = it; thumbPos = PhotoPos(); largePos = PhotoPos() }
                .onFailure { error = it.message }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(str(if (isNew) R.string.vehicle_new else R.string.vehicle_edit)) },
                navigationIcon = { BackButton(nav) },
                actions = {
                    if (!isNew) IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Default.Delete, contentDescription = str(R.string.delete))
                    }
                    IconButton(onClick = {
                        val b = base ?: return@IconButton
                        if (name.isBlank()) { error = str(R.string.err_enter_name); return@IconButton }
                        scope.launch {
                            repo.saveVehicle(
                                b.copy(
                                    name = name.trim(), plate = plate.trim(), defaultFuelType = fuel,
                                    tankCapacity = tank.toDoubleDe(), initialOdometer = initialOdo.toIntKm() ?: 0,
                                    photoVersion = photoVersion, archived = archived,
                                    photoThumbPos = thumbPos.encode(), photoLargePos = largePos.encode(),
                                    distanceUnit = distU, volumeUnit = volU, consumptionFormat = fmtU, currency = cur,
                                    vehicleType = vType,
                                    driveType = drive,
                                    batteryCapacity = if (drive == "Elektro" || drive == "Plug-in-Hybrid") battery.toDoubleDe() else null,
                                )
                            )
                            if (isNew) nav.navigate("vehicle/${b.id}") { popUpTo("vehicles") } else nav.popBackStack()
                        }
                    }) { Icon(Icons.Default.Check, contentDescription = str(R.string.save)) }
                },
            )
        },
    ) { pad ->
        val b = base ?: return@Scaffold
        Column(
            Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val photoFile = if (photoVersion > 0) PhotoStore.file(ctx, b.id, photoVersion) else null
            if (photoFile != null && photoFile.exists()) {
                PhotoPositionEditor(photoFile, thumbPos, largePos, { thumbPos = it }, { largePos = it })
            } else {
                VehiclePhoto(b, Modifier.fillMaxWidth().aspectRatio(LARGE_PHOTO_ASPECT), large = true)
            }
            OutlinedButton(
                onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(str(if (photoVersion > 0) R.string.photo_change else R.string.photo_choose)) }
            OutlinedTextField(name, { name = it }, label = { Text(str(R.string.vehicle_name_hint)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(plate, { plate = it }, label = { Text(str(R.string.plate)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            ChoiceField(str(R.string.vehicle_type), vType, Options.vehicleTypes) { vType = it }
            SectionTitle(str(R.string.drive_type))
            SingleChips(Options.driveTypes, drive) { sel ->
                if (sel.isEmpty()) return@SingleChips
                drive = sel
                if (sel == "Elektro") fuel = ELECTRIC
                else if (fuel == ELECTRIC) fuel = "Super E10"
            }
            if (drive != "Elektro") {
                ChoiceField(str(R.string.default_fuel), fuel, Options.fuelTypes.filter { it != ELECTRIC }) { fuel = it }
            }
            val previewVehicle = b.copy(volumeUnit = volU, distanceUnit = distU)
            if (drive != "Elektro") {
                NumberField(str(R.string.tank_capacity), tank, { tank = it }, suffix = Units.amount(previewVehicle, fuel))
            }
            if (drive == "Elektro" || drive == "Plug-in-Hybrid") {
                NumberField(str(R.string.battery_capacity), battery, { battery = it }, suffix = "kWh")
                if (drive == "Plug-in-Hybrid") Text(str(R.string.phev_info), style = MaterialTheme.typography.bodySmall)
            }
            NumberField(str(R.string.initial_odometer), initialOdo, { initialOdo = it }, suffix = distU, decimal = false)

            SectionTitle(str(R.string.units))
            ChoiceField(str(R.string.distance), Units.distanceUnits[distU] ?: distU, Units.distanceUnits.values.toList()) { lbl ->
                distU = Units.distanceUnits.entries.first { it.value == lbl }.key
            }
            ChoiceField(str(R.string.volume), Units.volumeUnits[volU] ?: volU, Units.volumeUnits.values.toList()) { lbl ->
                volU = Units.volumeUnits.entries.first { it.value == lbl }.key
            }
            ChoiceField(str(R.string.consumption_display), Units.formats[fmtU] ?: fmtU, Units.formats.values.toList()) { lbl ->
                fmtU = Units.formats.entries.first { it.value == lbl }.key
            }
            ChoiceField(str(R.string.currency), cur, Units.currencies) { cur = it }
            if (!isNew && (distU != b.distanceUnit || volU != b.volumeUnit)) {
                Text(
                    str(R.string.units_convert_info),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            if (!isNew && cur != b.currency) {
                Text(str(R.string.currency_info), style = MaterialTheme.typography.bodySmall)
            }
            if (!isNew) {
                LabeledSwitch(str(R.string.archived_switch), archived) { archived = it }
                Text(
                    str(R.string.archived_info),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(str(R.string.vehicle_delete_q)) },
            text = { Text(str(R.string.vehicle_delete_info)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        repo.deleteVehicle(vehicleId)
                        nav.popBackStack("vehicles", false)
                    }
                }) { Text(str(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(str(R.string.cancel)) } },
        )
    }
}
