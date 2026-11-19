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
import androidx.compose.runtime.saveable.rememberSaveable
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
            Icon(serviceIcon(s.category), contentDescription = lbl(s.category), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row {
                    Text(s.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    if (s.cost > 0) Text("${s.cost.fmt()} ${v.currency}", fontWeight = FontWeight.SemiBold)
                }
                Text(
                    listOf(s.timestamp.date(), if (s.odometer > 0) s.odometer.dist(v.distanceUnit) else "", lbl(s.category))
                        .filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (s.workshop.isNotBlank()) Text(s.workshop, style = MaterialTheme.typography.bodySmall)
                if (s.tireSummary().isNotBlank()) {
                    Text(s.tireSummary(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                if (s.treadDepth.isNotBlank()) {
                    val t = TreadCodec.parse(s.treadDepth)
                    if (s.tireSetId.isNotBlank() || s.removedTireSetId.isNotBlank()) {
                        val stored = t.mapNotNull { (k, v) -> v.old?.let { "${lbl(k)} ${it.fmt(1)}" } }
                        val mounted = t.mapNotNull { (k, v) -> v.new?.let { "${lbl(k)} ${it.fmt(1)}" } }
                        if (stored.isNotEmpty()) Text(str(R.string.tread_stored, stored.joinToString(" · ")),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        if (mounted.isNotEmpty()) Text(str(R.string.tread_mounted, mounted.joinToString(" · ")),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    } else {
                        Text(str(R.string.tread_summary, TreadCodec.summary(s.treadDepth)), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary)
                    }
                }
                if (s.note.isNotBlank()) Text(s.note, style = MaterialTheme.typography.bodySmall)
                if (s.attachments.isNotBlank()) {
                    Text("📎 " + str(R.string.n_receipts, s.attachmentList().size), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary)
                }
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
    val startImport = rememberServiceImporter(vehicleId, vehicle, snackbar)
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    val dotColor = MaterialTheme.colorScheme.primary
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(str(R.string.service_title, vehicle?.name ?: "")) },
                navigationIcon = { BackButton(nav) },
                actions = {
                    IconButton(onClick = startImport) { Icon(Icons.Default.FileUpload, contentDescription = str(R.string.cd_service_import)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { nav.navigate("serviceEdit/$vehicleId/new") },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(str(R.string.entry)) },
            )
        },
    ) { pad ->
        val v = vehicle ?: return@Scaffold
        if (services.isEmpty()) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(str(R.string.empty_services_title))
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.padding(pad),
            contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
        ) {
            item {
                Text(
                    str(R.string.services_summary, services.size, services.sumOf { it.cost }.fmt() + " " + v.currency),
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
    val isNew = serviceId.startsWith("new")
    val isWheelChange = serviceId == "new-wheel"
    // Eingaben überstehen das Neuerstellen der Activity (z. B. während Kamera/Dateiauswahl)
    var loaded by rememberSaveable { mutableStateOf(false) }
    var base by remember { mutableStateOf<ServiceEntry?>(null) }
    var veh by remember { mutableStateOf<Vehicle?>(null) }
    var timestamp by rememberSaveable { mutableLongStateOf(now()) }
    var odometer by rememberSaveable { mutableStateOf("") }
    var title by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("Wartung") }
    var cost by rememberSaveable { mutableStateOf("") }
    var workshop by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var attachments by rememberSaveable { mutableStateOf(listOf<String>()) }
    var tireSeason by rememberSaveable { mutableStateOf("") }
    var tireBrand by rememberSaveable { mutableStateOf("") }
    var tireModel by rememberSaveable { mutableStateOf("") }
    var tireSize by rememberSaveable { mutableStateOf("") }
    val tireSets by repo.tireSets(vehicleId).collectAsStateWithLifecycle(emptyList())
    var mountedSetId by rememberSaveable { mutableStateOf("") }
    var removedSetId by rememberSaveable { mutableStateOf("") }
    val treadOld = rememberSaveable(saver = StringMapSaver) { mutableStateMapOf<String, String>() }
    val treadNew = rememberSaveable(saver = StringMapSaver) { mutableStateMapOf<String, String>() }
    var error by remember { mutableStateOf<String?>(null) }
    var showDate by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(serviceId) {
        veh = repo.getVehicle(vehicleId)
        val s = if (isNew) ServiceEntry(vehicleId = vehicleId, odometer = repo.currentOdometer(vehicleId))
        else repo.getService(serviceId) ?: return@LaunchedEffect
        base = s
        if (loaded) return@LaunchedEffect
        loaded = true
        timestamp = s.timestamp
        odometer = if (s.odometer > 0) s.odometer.toString() else ""
        title = s.title
        category = s.category
        cost = if (s.cost > 0) s.cost.fmt(2) else ""
        workshop = s.workshop
        note = s.note
        attachments = s.attachmentList()
        tireSeason = s.tireSeason; tireBrand = s.tireBrand; tireModel = s.tireModel; tireSize = s.tireSize
        mountedSetId = s.tireSetId; removedSetId = s.removedTireSetId
        if (isNew) {
            val sets = repo.db.tireSets().forVehicle(vehicleId)
            if (sets.isNotEmpty()) {
                // Radwechsel: der bisher montierte Satz kommt runter, der andere drauf
                val current = repo.mountedTireSetId(vehicleId)
                removedSetId = current ?: ""
                mountedSetId = sets.firstOrNull { it.id != current }?.id ?: ""
            } else repo.db.services().lastTireChange(vehicleId)?.let { last ->
                tireSeason = last.tireSeason; tireBrand = last.tireBrand; tireModel = last.tireModel; tireSize = last.tireSize
            }
        }
        if (isWheelChange) { category = "Reifen"; title = str(R.string.wheel_change) }
        TreadCodec.parse(s.treadDepth).forEach { (k, t) ->
            t.old?.let { treadOld[k] = it.fmt(1) }
            t.new?.let { treadNew[k] = it.fmt(1) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(str(if (isNew) R.string.service_new else R.string.service_entry)) },
                navigationIcon = { BackButton(nav) },
                actions = {
                    if (!isNew) IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Default.Delete, contentDescription = str(R.string.delete))
                    }
                    IconButton(onClick = {
                        val b = base ?: return@IconButton
                        if (title.isBlank()) { error = str(R.string.err_enter_title); return@IconButton }
                        val mountedSet = tireSets.firstOrNull { it.id == mountedSetId }
                        if (category == "Reifen" && mountedSet != null) {
                            tireSeason = mountedSet.season
                            tireBrand = if (mountedSet.sameAxles || mountedSet.rearTire.isBlank()) mountedSet.frontTire
                            else "${mountedSet.frontTire} / ${mountedSet.rearTire}"
                            tireModel = ""
                            tireSize = mountedSet.sizes()
                        }
                        scope.launch {
                            repo.saveService(
                                b.copy(
                                    tireSetId = if (category == "Reifen") mountedSetId else "",
                                    removedTireSetId = if (category == "Reifen") removedSetId else "",
                                    timestamp = timestamp, odometer = odometer.toIntKm() ?: 0, title = title.trim(),
                                    category = category, cost = cost.toDoubleDe() ?: 0.0,
                                    workshop = workshop.trim(), note = note.trim(),
                                    attachments = attachments.joinToString("|"),
                                    tireSeason = if (category == "Reifen") tireSeason else "",
                                    tireBrand = if (category == "Reifen") tireBrand.trim() else "",
                                    tireModel = if (category == "Reifen") tireModel.trim() else "",
                                    tireSize = if (category == "Reifen") tireSize.trim() else "",
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
                    }) { Icon(Icons.Default.Check, contentDescription = str(R.string.save)) }
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
            SuggestField(str(R.string.what_done), title, Options.serviceSuggestions) { title = it }
            ChoiceField(str(R.string.category), category, Options.serviceCategories) { category = it }
            NumberField(str(R.string.odometer), odometer, { odometer = it }, suffix = Units.dist(veh), decimal = false)
            NumberField(str(R.string.cost), cost, { cost = it }, suffix = Units.cur(veh))
            if (category == "Reifen" && tireSets.isNotEmpty()) {
                SectionTitle(str(R.string.wheel_change))
                val none = str(R.string.none_dash)
                val labels = tireSets.associate { it.id to it.label() }
                ChoiceField(str(R.string.removed_stored), labels[removedSetId] ?: none, listOf(none) + tireSets.map { it.label() }) { sel ->
                    removedSetId = tireSets.firstOrNull { it.label() == sel }?.id ?: ""
                }
                ChoiceField(str(R.string.mounted), labels[mountedSetId] ?: none, listOf(none) + tireSets.map { it.label() }) { sel ->
                    mountedSetId = tireSets.firstOrNull { it.label() == sel }?.id ?: ""
                }
                TextButton(onClick = { nav.navigate("tireSetEdit/$vehicleId/new") }) { Text(str(R.string.add_another_set)) }
                SectionTitle(str(R.string.tread_depth))
                Text(
                    str(R.string.tread_set_info),
                    style = MaterialTheme.typography.bodySmall,
                )
                Options.tirePositions(veh?.vehicleType).forEach { (key, label) ->
                    Text(label, style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumberField(str(R.string.stored), treadOld[key] ?: "", { treadOld[key] = it }, suffix = "mm", modifier = Modifier.weight(1f))
                        NumberField(str(R.string.mounted), treadNew[key] ?: "", { treadNew[key] = it }, suffix = "mm", modifier = Modifier.weight(1f))
                    }
                }
            } else if (category == "Reifen") {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(str(R.string.tip_tire_sets), style = MaterialTheme.typography.titleSmall)
                        Text(
                            str(R.string.tip_tire_sets_text),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = { nav.navigate("tireSetEdit/$vehicleId/new") }) { Text(str(R.string.create_tire_set)) }
                    }
                }
                SectionTitle(str(R.string.tires))
                SingleChips(Options.tireTypes, tireSeason) { tireSeason = it }
                SuggestField(str(R.string.manufacturer), tireBrand, Options.tireBrands) { tireBrand = it }
                OutlinedTextField(
                    tireModel, { tireModel = it }, label = { Text(str(R.string.tire_name_hint)) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    tireSize, { tireSize = it }, label = { Text(str(R.string.tire_size_hint)) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                SectionTitle(str(R.string.tread_depth))
                Text(
                    str(R.string.tread_oldnew_info),
                    style = MaterialTheme.typography.bodySmall,
                )
                Options.tirePositions(veh?.vehicleType).forEach { (key, label) ->
                    Text(label, style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumberField(str(R.string.old), treadOld[key] ?: "", { treadOld[key] = it }, suffix = "mm", modifier = Modifier.weight(1f))
                        NumberField(str(R.string.new_), treadNew[key] ?: "", { treadNew[key] = it }, suffix = "mm", modifier = Modifier.weight(1f))
                    }
                }
            }
            OutlinedTextField(
                workshop, { workshop = it }, label = { Text(str(R.string.workshop)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                note, { note = it }, label = { Text(str(R.string.service_note_hint)) }, minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            AttachmentSection(attachments) { attachments = it }
            Text(
                str(R.string.service_reminder_info),
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
                }) { Text(str(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text(str(R.string.cancel)) } },
        ) { DatePicker(state = state) }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(str(R.string.service_delete_q)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch { repo.deleteService(serviceId); nav.popBackStack() }
                }) { Text(str(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(str(R.string.cancel)) } },
        )
    }
}
