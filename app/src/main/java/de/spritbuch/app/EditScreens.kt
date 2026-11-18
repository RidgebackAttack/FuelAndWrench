package de.spritbuch.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RefuelEditScreen(nav: NavController, vehicleId: String, refuelId: String) {
    val ctx = LocalContext.current
    val repo = ctx.repo
    val scope = rememberCoroutineScope()
    val isNew = refuelId == "new"

    var base by remember { mutableStateOf<Refuel?>(null) }
    var timestamp by remember { mutableLongStateOf(now()) }
    var odometer by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var ppu by remember { mutableStateOf("") }
    var total by remember { mutableStateOf("") }
    var fullTank by remember { mutableStateOf(true) }
    var missed by remember { mutableStateOf(false) }
    var fuelType by remember { mutableStateOf("") }
    var brand by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var lat by remember { mutableStateOf<Double?>(null) }
    var lon by remember { mutableStateOf<Double?>(null) }
    var extras by remember { mutableStateOf(setOf<String>()) }
    var style by remember { mutableStateOf("") }
    var route by remember { mutableStateOf("") }
    var tires by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var obc by remember { mutableStateOf("") }
    var chargeLoc by remember { mutableStateOf("") }
    var socStart by remember { mutableStateOf("") }
    var socEnd by remember { mutableStateOf("") }
    var fuelOnlyType by remember { mutableStateOf("") }
    var brandOptions by remember { mutableStateOf(Options.brands) }
    var lastOdo by remember { mutableStateOf<Int?>(null) }
    var veh by remember { mutableStateOf<Vehicle?>(null) }
    var locating by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var showDate by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var scanInfo by remember { mutableStateOf<String?>(null) }
    var odoFromScan by remember { mutableStateOf(false) }
    var scanMode by remember { mutableStateOf("") }
    val scanTarget = remember { AttachmentStore.cameraTarget(ctx) }

    var vehicleChoices by remember { mutableStateOf(listOf<Vehicle>()) }
    var cropSource by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var scanBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var review by remember { mutableStateOf<ScanReview?>(null) }
    var scanStation by remember { mutableStateOf<String?>(null) }
    var scanAddress by remember { mutableStateOf<String?>(null) }

    fun runScan(bitmap: android.graphics.Bitmap, crop: android.graphics.RectF, mode: String) {
        scope.launch {
            scanning = true
            scanInfo = null
            try {
                val text = Ocr.recognize(ctx, bitmap, crop, digitsOnly = mode == "odo")
                review = if (mode == "odo") {
                    val km = ReceiptParser.odometer(text, lastOdo)
                    val nums = ReceiptParser.numbers(text).filter { it.second >= 100 && it.second % 1.0 == 0.0 }
                    ScanReview(true, km?.let { mapOf(ScanTarget.ODOMETER to it.toDouble()) } ?: emptyMap(), null, null, nums)
                } else {
                    // Eigene Betreiber (aus früheren Betankungen) mit durchsuchen
                    val own = repo.db.refuels().getAllRaw().filter { !it.deleted }.map { it.stationBrand }.distinct()
                    val r = ReceiptParser.parse(text, ownStations = own)
                    val v = buildMap {
                        r.amount?.let { put(ScanTarget.AMOUNT, it) }
                        r.pricePerUnit?.let { put(ScanTarget.PRICE, it) }
                        r.total?.let { put(ScanTarget.TOTAL, it) }
                    }
                    // Sorte nur übernehmen, wenn sie zum Fahrzeug passt
                    val f = r.fuelType?.takeIf { f ->
                        val diesel = { s: String -> s.contains("Diesel") || s == "HVO100" }
                        val vf = veh?.defaultFuelType ?: fuelType
                        when {
                            f == ELECTRIC -> veh?.charges != false
                            veh?.isElectric == true -> false
                            else -> vf.isBlank() || vf == ELECTRIC || diesel(vf) == diesel(f)
                        }
                    }
                    scanStation = r.station
                    scanAddress = r.address
                    ScanReview(false, v, f, r.date, ReceiptParser.numbers(text))
                }
            } catch (e: Throwable) {
                scanInfo = str(R.string.ocr_failed) + (e.message?.let { " ($it)" } ?: "")
            } finally {
                scanning = false
            }
        }
    }
    // 1. Foto laden, 2. ganzes Bild erkennen, 3. „Stimmt das?“, 4. bei Bedarf Bereich eingrenzen (CropDialog)
    fun loadForScan(uri: android.net.Uri) {
        scope.launch {
            scanning = true
            scanInfo = null
            runCatching { withContext(Dispatchers.IO) { Ocr.load(ctx, uri, 2400) } }
                .onSuccess { bmp ->
                    // Erst selbst auf dem ganzen Bild versuchen; eingrenzen nur, wenn das Ergebnis nicht stimmt
                    scanBitmap = bmp
                    scanning = false
                    runScan(bmp, android.graphics.RectF(0f, 0f, 1f, 1f), scanMode)
                }
                .onFailure {
                    scanInfo = str(R.string.ocr_failed) + (it.message?.let { m -> " ($m)" } ?: "")
                    scanning = false
                }
        }
    }

    val scanCamera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) loadForScan(android.net.Uri.fromFile(scanTarget))
    }
    val scanPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) loadForScan(uri)
    }
    fun startScan(mode: String) {
        scanMode = mode
        runCatching { scanCamera.launch(AttachmentStore.uri(ctx, scanTarget)) }
            .onFailure { scanPicker.launch("image/*") }
    }

    fun locate() {
        scope.launch {
            locating = true
            info = null
            try {
                val loc = LocationHelper.current(ctx)
                if (loc == null) {
                    info = str(R.string.loc_no_gps)
                } else {
                    lat = loc.latitude
                    lon = loc.longitude
                    val p = Prefs(ctx)
                    val st = if (p.useOverpass) LocationHelper.nearbyStation(loc.latitude, loc.longitude, charging = fuelType == ELECTRIC) else null
                    st?.brand?.let { brand = it }
                    val addr = st?.address
                        ?: (if (p.useOverpass) LocationHelper.osmAddress(loc.latitude, loc.longitude) else null)
                        ?: (if (p.useGeocoder) LocationHelper.address(ctx, loc.latitude, loc.longitude) else null)
                    address = addr ?: ""
                    info = when {
                        !p.useOverpass -> str(R.string.loc_taken_no_search)
                        st != null -> str(R.string.loc_station_found)
                        else -> str(R.string.loc_no_station)
                    }
                }
            } finally {
                locating = false
            }
        }
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.ACCESS_FINE_LOCATION] == true) locate()
        else info = str(R.string.loc_need_precise)
    }

    fun requestLocate() {
        if (LocationHelper.hasPermission(ctx)) locate()
        else permLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    LaunchedEffect(refuelId) {
        val last = repo.lastRefuel(vehicleId)
        val v = repo.getVehicle(vehicleId)
        veh = v
        if (isNew) vehicleChoices = repo.db.vehicles().getAllRaw().filter { !it.deleted && !it.archived && Prefs(ctx).shows(it.id) }
        val r = if (isNew) {
            Refuel(
                vehicleId = vehicleId,
                fuelType = last?.fuelType ?: v?.defaultFuelType ?: "",
                stationBrand = last?.stationBrand ?: "",
                drivingStyle = last?.drivingStyle ?: "Normal",
                routeType = last?.routeType ?: "Gemischt",
                tireType = repo.db.services().lastTireChange(vehicleId)
                    ?.takeIf { it.timestamp > (last?.timestamp ?: 0L) }?.tireSeason
                    ?: last?.tireType ?: "",
            )
        } else repo.getRefuel(refuelId) ?: return@LaunchedEffect
        lastOdo = if (isNew) repo.currentOdometer(vehicleId) else null
        base = r
        timestamp = r.timestamp
        odometer = if (r.odometer > 0) r.odometer.toString() else ""
        amount = if (r.amount > 0) r.amount.fmt(2) else ""
        total = if (r.totalPrice > 0) r.totalPrice.fmt(2) else ""
        ppu = if (r.amount > 0) r.pricePerUnit.fmt(3) else ""
        fullTank = r.fullTank
        missed = r.missedPrevious
        fuelType = r.fuelType
        brand = r.stationBrand
        address = r.stationAddress
        lat = r.latitude
        lon = r.longitude
        extras = r.extrasList().toSet()
        style = r.drivingStyle
        route = r.routeType
        tires = r.tireType
        note = r.note
        obc = r.obcConsumption?.let { (if (v?.consumptionFormat == "perUnit") 100.0 / it else it).fmt(1) } ?: ""
        chargeLoc = r.chargeLocation.ifBlank {
            if (isNew) repo.db.refuels().forVehicle(vehicleId).filter { it.isCharge }.maxByOrNull { it.timestamp }?.chargeLocation ?: "" else ""
        }
        socStart = r.socStart?.toString() ?: ""
        socEnd = r.socEnd?.toString() ?: ""
        // Für Plug-in-Hybride: welcher Kraftstoff beim Umschalten auf „Tanken“
        fuelOnlyType = (if (v?.defaultFuelType == ELECTRIC) "" else v?.defaultFuelType) ?: ""
        if (isNew && v?.isElectric == true) fuelType = ELECTRIC
        // Eigene, häufig genutzte Betreiber zuerst, danach die Standardliste
        val used = repo.db.refuels().getAllRaw().filter { !it.deleted && it.stationBrand.isNotBlank() }
            .groupingBy { it.stationBrand }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
        brandOptions = (used + Options.brands).distinctBy { it.lowercase() }
        if (isNew && LocationHelper.hasPermission(ctx)) locate()
    }

    val unit = Units.amount(veh, fuelType)
    val du = Units.dist(veh)
    val cur = Units.cur(veh)

    fun recalcTotal() {
        val a = amount.toDoubleDe()
        val p = ppu.toDoubleDe()
        if (a != null && p != null) total = (a * p).fmt(2)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val c = fuelType == ELECTRIC
                    Text(str(if (isNew) (if (c) R.string.charge_new else R.string.refuel_new) else (if (c) R.string.charge else R.string.refuel)))
                },
                navigationIcon = { BackButton(nav) },
                actions = {
                    if (!isNew) IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Default.Delete, contentDescription = str(R.string.delete))
                    }
                    IconButton(onClick = {
                        val b = base ?: return@IconButton
                        val odo = odometer.toIntKm()
                        val a = amount.toDoubleDe()
                        if (odo == null || odo <= 0) { error = str(R.string.err_enter_odometer); return@IconButton }
                        if (a == null || a <= 0) { error = str(R.string.err_enter_amount); return@IconButton }
                        val t = total.toDoubleDe() ?: ((ppu.toDoubleDe() ?: 0.0) * a)
                        val obcVal = obc.toDoubleDe()?.takeIf { it > 0 }?.let {
                            if (veh?.consumptionFormat == "perUnit") 100.0 / it else it
                        }
                        scope.launch {
                            repo.saveRefuel(
                                b.copy(
                                    timestamp = timestamp, odometer = odo, amount = a, totalPrice = t,
                                    fullTank = fullTank, missedPrevious = missed, fuelType = fuelType,
                                    stationBrand = brand.trim(), stationAddress = address.trim(),
                                    latitude = lat, longitude = lon,
                                    extras = Options.extras.filter { it in extras }.joinToString("|"),
                                    drivingStyle = style, routeType = route, tireType = tires, note = note.trim(),
                                    obcConsumption = obcVal,
                                    chargeLocation = if (fuelType == ELECTRIC) chargeLoc else "",
                                    socStart = if (fuelType == ELECTRIC) socStart.toIntKm()?.coerceIn(0, 100) else null,
                                    socEnd = if (fuelType == ELECTRIC) socEnd.toIntKm()?.coerceIn(0, 100) else null,
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

            if (isNew && vehicleChoices.size > 1) {
                // Falsches Fahrzeug gewählt? Hier wechseln, ohne neu anzufangen
                val names = vehicleChoices.map { it.name }
                ChoiceField(str(R.string.vehicle), veh?.name ?: "", names) { sel ->
                    val target = vehicleChoices.firstOrNull { it.name == sel } ?: return@ChoiceField
                    if (target.id != vehicleId) {
                        nav.navigate("refuelEdit/${target.id}/new") { popUpTo("refuelEdit/{vehicleId}/{id}") { inclusive = true } }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { startScan("receipt") }, enabled = !scanning, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.ReceiptLong, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(str(R.string.scan_receipt))
                }
                OutlinedButton(onClick = { startScan("odo") }, enabled = !scanning, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Speed, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(str(R.string.scan_odometer))
                }
            }
            if (scanning) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(str(R.string.scan_running), style = MaterialTheme.typography.bodySmall)
            }
            scanInfo?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }

            OutlinedButton(onClick = { showDate = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.CalendarToday, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(timestamp.dateTime())
            }
            NumberField(str(R.string.odometer), odometer, { odometer = it; odoFromScan = false }, suffix = du, decimal = false)
            if (odoFromScan) Text(str(R.string.scan_check_odo), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            lastOdo?.let { if (it > 0) Text(str(R.string.previously, it.dist(du)), style = MaterialTheme.typography.bodySmall) }
            val charging = fuelType == ELECTRIC
            if (veh?.isPlugInHybrid == true) {
                SingleChips(listOf(str(R.string.refuel), str(R.string.charge)), str(if (charging) R.string.charge else R.string.refuel)) { sel ->
                    if (sel.isNotEmpty()) fuelType = if (sel == str(R.string.charge)) ELECTRIC else fuelOnlyType.ifBlank { "Super E10" }
                }
            }
            if (!charging) {
                ChoiceField(str(R.string.fuel_type), fuelType, Options.fuelTypes.filter { veh?.isElectric != true || it == ELECTRIC }) { fuelType = it }
            } else if (veh?.charges != true) {
                // Fahrzeug ohne Elektro-Antrieb: Rückweg zum Kraftstoff ermöglichen
                ChoiceField(str(R.string.fuel_type), fuelType, Options.fuelTypes) { fuelType = it }
            }
            NumberField(str(if (charging) R.string.energy else R.string.amount), amount, { amount = it; recalcTotal() }, suffix = unit)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(str(R.string.price_per_unit, unit), ppu, { ppu = it; recalcTotal() }, suffix = cur, modifier = Modifier.weight(1f))
                NumberField(str(R.string.total), total, {
                    total = it
                    val a = amount.toDoubleDe()
                    val t = it.toDoubleDe()
                    if (a != null && t != null && a > 0) ppu = (t / a).fmt(3)
                }, suffix = cur, modifier = Modifier.weight(1f))
            }
            LabeledSwitch(str(if (charging) R.string.charged_to_target else R.string.full_tank), fullTank) { fullTank = it }
            if (charging) Text(str(R.string.charged_to_target_info), style = MaterialTheme.typography.bodySmall)
            LabeledSwitch(str(if (charging) R.string.missed_previous_charge else R.string.missed_previous), missed) { missed = it }
            if (charging) {
                SectionTitle(str(R.string.charge_location))
                SingleChips(Options.chargeLocations, chargeLoc) { chargeLoc = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(str(R.string.soc_before), socStart, { socStart = it }, suffix = "%", decimal = false, modifier = Modifier.weight(1f))
                    NumberField(str(R.string.soc_after), socEnd, { socEnd = it }, suffix = "%", decimal = false, modifier = Modifier.weight(1f))
                }
                if (veh?.batteryCapacity == null) Text(str(R.string.soc_info_capacity), style = MaterialTheme.typography.bodySmall)
            }
            val obcUnit = if (veh?.consumptionFormat == "perUnit") "$du/$unit" else "$unit/100 $du"
            NumberField(str(R.string.obc_field), obc, { obc = it }, suffix = obcUnit)

            HorizontalDivider()
            SectionTitle(str(if (charging) R.string.charging_station else R.string.station))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { requestLocate() }, enabled = !locating) {
                    Icon(Icons.Default.MyLocation, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(str(R.string.current_position))
                }
                Spacer(Modifier.width(12.dp))
                if (locating) CircularProgressIndicator(Modifier.size(24.dp))
            }
            info?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            SuggestField(
                str(if (charging) R.string.charge_network else R.string.operator), brand,
                if (charging) Options.chargeNetworks else brandOptions,
            ) { brand = it }
            OutlinedTextField(address, { address = it }, label = { Text(str(R.string.address)) }, modifier = Modifier.fillMaxWidth())
            val la = lat
            val lo = lon
            if (la != null && lo != null) {
                Text(String.format(Locale.GERMANY, "%.5f, %.5f", la, lo), style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        scope.launch {
                            locating = true
                            val p = Prefs(ctx)
                            val a = (if (p.useOverpass) LocationHelper.osmAddress(la, lo) else null)
                                ?: (if (p.useGeocoder) LocationHelper.address(ctx, la, lo) else null)
                            if (a != null) { address = a; info = null }
                            else info = if (!p.useOverpass && !p.useGeocoder) str(R.string.addr_search_off)
                            else str(R.string.addr_not_found)
                            locating = false
                        }
                    }, enabled = !locating) {
                        Icon(Icons.Default.Place, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(str(R.string.address))
                    }
                    TextButton(onClick = {
                        val uri = Uri.parse("geo:$la,$lo?q=$la,$lo")
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                    }) {
                        Icon(Icons.Default.Map, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(str(R.string.map))
                    }
                    TextButton(onClick = { lat = null; lon = null }) { Text(str(R.string.remove)) }
                }
            }

            HorizontalDivider()
            SectionTitle(str(R.string.extras))
            MultiChips(Options.extras, extras) { extras = it }
            SectionTitle(str(R.string.driving_style))
            SingleChips(Options.drivingStyles, style) { style = it }
            SectionTitle(str(R.string.route))
            SingleChips(Options.routeTypes, route) { route = it }
            SectionTitle(str(R.string.tires))
            SingleChips(Options.tireTypes, tires) { tires = it }

            OutlinedTextField(note, { note = it }, label = { Text(str(R.string.note)) }, modifier = Modifier.fillMaxWidth())
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

    cropSource?.let { bmp ->
        val odo = scanMode == "odo"
        CropDialog(
            bmp,
            if (odo) android.graphics.RectF(0.15f, 0.35f, 0.85f, 0.65f) else android.graphics.RectF(0.03f, 0.03f, 0.97f, 0.97f),
            str(if (odo) R.string.crop_hint_odo else R.string.crop_hint_receipt),
            onCancel = { cropSource = null },
        ) { rect ->
            cropSource = null
            runScan(bmp, rect, scanMode)
        }
    }

    review?.let { rv ->
        val u = Units.amount(veh, fuelType)
        val c = Units.cur(veh)
        val extras = listOfNotNull(
            scanStation?.takeIf { !rv.forOdometer }?.let { lbl(it) },
            scanAddress?.takeIf { !rv.forOdometer },
            rv.fuelType?.let { lbl(it) },
            rv.date?.let { (y, m, d) -> String.format(appLocale(), "%02d.%02d.%d", d, m, y) },
        ).joinToString(" · ").ifBlank { null }
        ScanReviewDialog(
            rv,
            label = { t ->
                when (t) {
                    ScanTarget.ODOMETER -> str(R.string.odometer)
                    ScanTarget.AMOUNT -> str(if (fuelType == ELECTRIC) R.string.energy else R.string.amount)
                    ScanTarget.PRICE -> str(R.string.price_per_unit, u)
                    ScanTarget.TOTAL -> str(R.string.total)
                }
            },
            format = { t, v ->
                when (t) {
                    ScanTarget.ODOMETER -> v.toInt().dist(du)
                    ScanTarget.AMOUNT -> "${v.fmt(2)} $u"
                    ScanTarget.PRICE -> "${v.fmt(3)} $c"
                    ScanTarget.TOTAL -> "${v.fmt(2)} $c"
                }
            },
            extras = extras,
            onDismiss = { review = null },
            onCrop = scanBitmap?.let { bmp -> { review = null; cropSource = bmp } },
        ) { values ->
            review = null
            values[ScanTarget.ODOMETER]?.let { odometer = it.toInt().toString(); odoFromScan = true }
            values[ScanTarget.AMOUNT]?.let { amount = it.fmt(2) }
            values[ScanTarget.PRICE]?.let { ppu = it.fmt(3) }
            values[ScanTarget.TOTAL]?.let { total = it.fmt(2) }
            // Fehlender Wert aus den anderen beiden berechnen
            val a = amount.toDoubleDe(); val p = ppu.toDoubleDe(); val t = total.toDoubleDe()
            if (values[ScanTarget.TOTAL] == null && a != null && p != null && values.isNotEmpty() && !rv.forOdometer) total = (a * p).fmt(2)
            if (values[ScanTarget.PRICE] == null && a != null && t != null && a > 0 && !rv.forOdometer) ppu = (t / a).fmt(3)
            if (!rv.forOdometer) {
                scanStation?.let { brand = it }
                scanAddress?.let { if (address.isBlank()) address = it }
                rv.fuelType?.let { fuelType = it }
                rv.date?.let { (y, m, d) ->
                    val cal = java.util.Calendar.getInstance().apply { timeInMillis = timestamp; set(y, m - 1, d) }
                    if (cal.timeInMillis <= now()) timestamp = cal.timeInMillis
                }
            }
            scanInfo = str(R.string.scan_applied)
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(str(R.string.refuel_delete_q)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch { repo.deleteRefuel(refuelId); nav.popBackStack() }
                }) { Text(str(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(str(R.string.cancel)) } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderEditScreen(nav: NavController, vehicleId: String, reminderId: String) {
    val ctx = LocalContext.current
    val repo = ctx.repo
    val scope = rememberCoroutineScope()
    val isNew = reminderId == "new"
    var base by remember { mutableStateOf<Reminder?>(null) }
    var title by remember { mutableStateOf("") }
    var interval by remember { mutableStateOf("") }
    var lastDone by remember { mutableStateOf("") }
    var warn by remember { mutableStateOf("") }
    var months by remember { mutableStateOf("") }
    var lastDate by remember { mutableLongStateOf(0L) }
    var showDate by remember { mutableStateOf(false) }
    var du by remember { mutableStateOf("km") }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(reminderId) {
        du = Units.dist(repo.getVehicle(vehicleId))
        val r = if (isNew) Reminder(vehicleId = vehicleId, lastDoneKm = repo.currentOdometer(vehicleId), lastDoneDate = now())
        else repo.getReminder(reminderId) ?: return@LaunchedEffect
        base = r
        title = r.title
        interval = if (r.intervalKm > 0) r.intervalKm.toString() else ""
        lastDone = r.lastDoneKm.toString()
        warn = r.warnBeforeKm.toString()
        months = if (r.intervalMonths > 0) r.intervalMonths.toString() else ""
        lastDate = if (r.lastDoneDate > 0) r.lastDoneDate else now()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(str(if (isNew) R.string.reminder_new else R.string.reminder)) },
                navigationIcon = { BackButton(nav) },
                actions = {
                    if (!isNew) IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Default.Delete, contentDescription = str(R.string.delete))
                    }
                    IconButton(onClick = {
                        val b = base ?: return@IconButton
                        val i = interval.toIntKm() ?: 0
                        val m = months.toIntKm() ?: 0
                        if (title.isBlank()) { error = str(R.string.err_enter_title); return@IconButton }
                        if (i <= 0 && m <= 0) { error = str(R.string.err_enter_interval); return@IconButton }
                        scope.launch {
                            repo.saveReminder(
                                b.copy(
                                    title = title.trim(), intervalKm = i,
                                    lastDoneKm = lastDone.toIntKm() ?: 0, warnBeforeKm = warn.toIntKm() ?: 0,
                                    intervalMonths = m, lastDoneDate = if (m > 0) lastDate else b.lastDoneDate,
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
            SuggestField(str(R.string.title), title, Options.reminderSuggestions) { title = it }
            Text(str(R.string.reminder_either_info), style = MaterialTheme.typography.bodySmall)
            SectionTitle(str(R.string.by_distance))
            NumberField(str(R.string.interval), interval, { interval = it }, suffix = du, decimal = false)
            NumberField(str(R.string.last_done_at), lastDone, { lastDone = it }, suffix = du, decimal = false)
            NumberField(str(R.string.warn_before), warn, { warn = it }, suffix = str(R.string.unit_before, du), decimal = false)
            SectionTitle(str(R.string.by_time))
            NumberField(str(R.string.interval), months, { months = it }, suffix = str(R.string.months), decimal = false)
            OutlinedButton(onClick = { showDate = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.CalendarToday, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(str(R.string.last_done_on, lastDate.date()))
            }
            val preview = Reminder(
                intervalKm = interval.toIntKm() ?: 0, lastDoneKm = lastDone.toIntKm() ?: 0,
                intervalMonths = months.toIntKm() ?: 0, lastDoneDate = lastDate,
            )
            val dueParts = listOfNotNull(
                if (preview.byKm) preview.dueKm.dist(du) else null,
                preview.dueDate?.date(),
            )
            if (dueParts.isNotEmpty()) {
                Text(str(R.string.next_due, dueParts.joinToString(str(R.string.or_sep))), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    if (showDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = toUtcDate(lastDate))
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { lastDate = combineDate(it, lastDate) }
                    showDate = false
                }) { Text(str(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text(str(R.string.cancel)) } },
        ) { DatePicker(state = state) }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(str(R.string.reminder_delete_q)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch { repo.deleteReminder(reminderId); nav.popBackStack() }
                }) { Text(str(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(str(R.string.cancel)) } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(nav: NavController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { Prefs(ctx) }
    // Bisherige Einrichtung (vollständige WebDAV-URL) in Server + Ordner zerlegen
    val legacy = remember {
        val u = prefs.url
        if (prefs.server.isBlank() && u.contains("/remote.php/dav/files/")) {
            val srv = u.substringBefore("/remote.php/dav/files/")
            val rest = u.substringAfter("/remote.php/dav/files/").substringAfter('/', "")
            srv to java.net.URLDecoder.decode(rest.replace("+", "%2B"), "UTF-8")
        } else null
    }
    var server by remember { mutableStateOf(prefs.server.ifBlank { legacy?.first ?: prefs.url }) }
    var folder by remember {
        mutableStateOf(if (prefs.server.isNotBlank()) prefs.folder else legacy?.second ?: if (prefs.url.isBlank()) "FuelAndWrench" else "")
    }
    var user by remember { mutableStateOf(prefs.user) }
    var password by remember { mutableStateOf(prefs.password) }
    var cloudInfo by remember { mutableStateOf<String?>(null) }
    var loggingIn by remember { mutableStateOf(false) }
    var pickerDav by remember { mutableStateOf<WebDavClient?>(null) }

    /** WebDAV-Stamm ermitteln: bei Nextcloud automatisch, sonst die Adresse selbst. */
    suspend fun resolveRoot(): String {
        val srv = Nextcloud.normalizeServer(server)
        return if (Nextcloud.isNextcloud(srv)) Nextcloud.davRoot(srv, user) else srv
    }
    var templates by remember { mutableStateOf(prefs.reminderTemplates) }
    var useOverpass by remember { mutableStateOf(prefs.useOverpass) }
    var useGeocoder by remember { mutableStateOf(prefs.useGeocoder) }
    var useNetworkLocation by remember { mutableStateOf(prefs.useNetworkLocation) }
    var newTitle by remember { mutableStateOf("") }
    var newKm by remember { mutableStateOf("") }
    var newMonths by remember { mutableStateOf("") }
    val status by SyncManager.status.collectAsStateWithLifecycle()
    val running by SyncManager.running.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        if (SyncManager.status.value.isBlank()) SyncManager.status.value = prefs.lastSync
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(str(R.string.settings)) },
                navigationIcon = { BackButton(nav) },
                actions = {
                    TextButton(onClick = { nav.navigate("about") }) { Text(str(R.string.about_licenses)) }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionTitle(str(R.string.appearance))
            val themeCfg by ThemeSettings.state.collectAsStateWithLifecycle()
            val modeLabels = linkedMapOf("system" to str(R.string.theme_system), "light" to str(R.string.theme_light), "dark" to str(R.string.theme_dark))
            SingleChips(modeLabels.values.toList(), modeLabels[themeCfg.mode] ?: str(R.string.theme_system)) { sel ->
                val key = modeLabels.entries.firstOrNull { it.value == sel }?.key ?: "system"
                ThemeSettings.update(ctx, themeCfg.copy(mode = key))
            }
            Text(str(R.string.color_scheme), style = MaterialTheme.typography.labelLarge)
            PalettePicker(themeCfg.palette) { ThemeSettings.update(ctx, themeCfg.copy(palette = it)) }
            LabeledSwitch(str(R.string.amoled), themeCfg.amoled) {
                ThemeSettings.update(ctx, themeCfg.copy(amoled = it))
            }
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                // Sprache pro App (Android 13+); sonst gilt die Systemsprache
                OutlinedButton(
                    onClick = {
                        runCatching {
                            ctx.startActivity(
                                Intent(android.provider.Settings.ACTION_APP_LOCALE_SETTINGS)
                                    .setData(Uri.fromParts("package", ctx.packageName, null))
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(str(R.string.language_button, appLocale().getDisplayLanguage(appLocale()))) }
            }

            SectionTitle(str(R.string.device_vehicles))
            Text(str(R.string.device_vehicles_info), style = MaterialTheme.typography.bodySmall)
            var allVehicleList by remember { mutableStateOf(listOf<Vehicle>()) }
            var visible by remember { mutableStateOf(prefs.visibleVehicles) }
            LaunchedEffect(Unit) { allVehicleList = ctx.repo.db.vehicles().getAllRaw().filter { !it.deleted }.sortedBy { it.archived } }
            fun saveVisible(v: Set<String>?) {
                visible = v
                prefs.visibleVehicles = v
                scope.launch { Shortcuts.update(ctx) }
            }
            LabeledSwitch(str(R.string.device_all_vehicles), visible == null) { all ->
                saveVisible(if (all) null else allVehicleList.filter { !it.archived }.map { it.id }.toSet())
            }
            if (visible != null) {
                allVehicleList.forEach { v ->
                    LabeledSwitch(v.name + if (v.archived) " (" + str(R.string.archived_lower) + ")" else "", v.id in (visible ?: emptySet())) { on ->
                        val cur = visible ?: emptySet()
                        saveVisible(if (on) cur + v.id else cur - v.id)
                    }
                }
            }

            SectionTitle(str(R.string.shortcuts_title))
            Text(str(R.string.shortcuts_info), style = MaterialTheme.typography.bodySmall)
            var shortcutMode by remember { mutableStateOf(prefs.shortcutMode) }
            val modes = linkedMapOf("generic" to str(R.string.shortcuts_generic), "perVehicle" to str(R.string.shortcuts_per_vehicle))
            SingleChips(modes.values.toList(), modes[shortcutMode] ?: "") { sel ->
                val key = modes.entries.firstOrNull { it.value == sel }?.key ?: return@SingleChips
                shortcutMode = key
                prefs.shortcutMode = key
                scope.launch { Shortcuts.update(ctx) }
            }

            HorizontalDivider()
            SectionTitle(str(R.string.sync))
            Text(
                str(R.string.sync_help),
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                server, { server = it }, label = { Text(str(R.string.server_hint)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                enabled = server.isNotBlank() && !loggingIn,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    scope.launch {
                        val srv = Nextcloud.normalizeServer(server)
                        server = srv
                        loggingIn = true
                        try {
                            val start = Nextcloud.startLogin(srv)
                            ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(start.loginUrl)))
                            cloudInfo = str(R.string.login_browser)
                            repeat(600) {
                                kotlinx.coroutines.delay(2000)
                                val res = Nextcloud.poll(start)
                                if (res != null) {
                                    user = res.loginName
                                    password = res.appPassword
                                    cloudInfo = str(R.string.logged_in_as, res.loginName)
                                    return@launch
                                }
                            }
                            cloudInfo = str(R.string.login_expired)
                        } catch (e: Exception) {
                            cloudInfo = str(R.string.login_failed, e.message ?: str(R.string.server_unreachable))
                        } finally {
                            loggingIn = false
                        }
                    }
                },
            ) { Text(str(if (loggingIn) R.string.login_waiting else R.string.login_nextcloud)) }
            cloudInfo?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            OutlinedTextField(user, { user = it }, label = { Text(str(R.string.user)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                password, { password = it }, label = { Text(str(R.string.app_password)) }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(str(R.string.folder), style = MaterialTheme.typography.labelMedium)
                    Text("/" + folder, style = MaterialTheme.typography.bodyLarge)
                }
                OutlinedButton(
                    enabled = server.isNotBlank() && user.isNotBlank() && password.isNotBlank(),
                    onClick = {
                        scope.launch {
                            cloudInfo = str(R.string.connecting)
                            val root = resolveRoot()
                            cloudInfo = null
                            pickerDav = WebDavClient(root, user, password)
                        }
                    },
                ) { Text(str(R.string.choose_folder)) }
            }
            Button(
                onClick = {
                    scope.launch {
                        val root = resolveRoot()
                        prefs.server = Nextcloud.normalizeServer(server)
                        prefs.folder = folder
                        prefs.url = if (folder.isBlank()) root else root + "/" + encodePath(folder)
                        prefs.user = user
                        prefs.password = password
                        prefs.uploadedPhotos = emptySet()
                        prefs.uploadedAttachments = emptySet()
                        SyncScheduler.schedulePeriodic(ctx)
                        SyncManager.sync(ctx)
                    }
                },
                enabled = !running && server.isNotBlank() && user.isNotBlank() && password.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(str(R.string.save_and_sync)) }
            if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (status.isNotBlank()) Text(str(R.string.last_sync, status), style = MaterialTheme.typography.bodyMedium)
            Text(
                str(R.string.sync_auto_info),
                style = MaterialTheme.typography.bodySmall,
            )

            HorizontalDivider()
            SectionTitle(str(R.string.backup))
            var backupInfo by remember { mutableStateOf<String?>(null) }
            var backupBusy by remember { mutableStateOf(false) }
            fun summaryText(s: Backup.Summary) = str(R.string.backup_summary, s.vehicles, s.refuels, s.services, s.expenses, s.files)
            val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
                if (uri != null) scope.launch {
                    backupBusy = true
                    backupInfo = runCatching { Backup.export(ctx, uri) }
                        .fold({ str(R.string.backup_done) + " " + summaryText(it) }, { str(R.string.error_prefix, it.message ?: "") })
                    backupBusy = false
                }
            }
            val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) scope.launch {
                    backupBusy = true
                    backupInfo = runCatching { Backup.restore(ctx, uri) }
                        .fold({ str(R.string.restore_done) + " " + summaryText(it) }, { str(R.string.error_prefix, it.message ?: "") })
                    backupBusy = false
                }
            }
            Text(str(R.string.backup_info), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { exportLauncher.launch(Backup.suggestedName()) }, enabled = !backupBusy, modifier = Modifier.weight(1f)) {
                    Text(str(R.string.backup_create))
                }
                OutlinedButton(
                    onClick = { restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed")) },
                    enabled = !backupBusy, modifier = Modifier.weight(1f),
                ) { Text(str(R.string.backup_restore)) }
            }
            if (backupBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            backupInfo?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

            HorizontalDivider()
            SectionTitle(str(R.string.privacy))
            Text(
                str(R.string.privacy_intro),
                style = MaterialTheme.typography.bodySmall,
            )
            LabeledSwitch(str(R.string.privacy_osm), useOverpass) { useOverpass = it; prefs.useOverpass = it }
            Text(str(R.string.privacy_osm_info), style = MaterialTheme.typography.bodySmall)
            LabeledSwitch(str(R.string.privacy_geocoder), useGeocoder) { useGeocoder = it; prefs.useGeocoder = it }
            Text(str(R.string.privacy_geocoder_info), style = MaterialTheme.typography.bodySmall)
            LabeledSwitch(str(R.string.privacy_network), useNetworkLocation) {
                useNetworkLocation = it; prefs.useNetworkLocation = it
            }
            Text(
                str(R.string.privacy_network_info),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                str(R.string.privacy_tiles_info),
                style = MaterialTheme.typography.bodySmall,
            )
            if (UpdateChecker.isDirectInstall(ctx)) {
                var updateCheck by remember { mutableStateOf(prefs.updateCheck) }
                var updateInfo by remember { mutableStateOf<String?>(null) }
                LabeledSwitch(str(R.string.update_check), updateCheck) { updateCheck = it; prefs.updateCheck = it }
                Text(str(R.string.update_check_info), style = MaterialTheme.typography.bodySmall)
                if (updateCheck) {
                    TextButton(onClick = {
                        scope.launch {
                            val u = UpdateChecker.check(ctx, force = true)
                            updateInfo = if (u != null) str(R.string.update_available, u.version) else str(R.string.update_none)
                            if (u != null) UpdateChecker.open(ctx, u)
                        }
                    }) { Text(str(R.string.update_check_now)) }
                    updateInfo?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }

            HorizontalDivider()
            SectionTitle(str(R.string.default_reminders))
            Text(
                str(R.string.default_reminders_info),
                style = MaterialTheme.typography.bodySmall,
            )
            templates.forEachIndexed { i, (title, km, mo) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(templateTitle(title), modifier = Modifier.weight(1f))
                    Text(listOfNotNull(if (km > 0) km.km() else null, if (mo > 0) "$mo " + str(R.string.months_short) else null).joinToString(" / "))
                    IconButton(onClick = {
                        templates = templates.toMutableList().also { it.removeAt(i) }
                        prefs.reminderTemplates = templates
                    }) { Icon(Icons.Default.Delete, contentDescription = str(R.string.remove)) }
                }
            }
            SuggestField(str(R.string.title), newTitle, Options.reminderSuggestions) { newTitle = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(str(R.string.interval), newKm, { newKm = it }, suffix = "km", decimal = false, modifier = Modifier.weight(1f))
                NumberField(str(R.string.interval), newMonths, { newMonths = it }, suffix = str(R.string.months), decimal = false, modifier = Modifier.weight(1f))
            }
            OutlinedButton(
                onClick = {
                    val km = newKm.toIntKm() ?: 0
                    val mo = newMonths.toIntKm() ?: 0
                    if (newTitle.isNotBlank() && (km > 0 || mo > 0)) {
                        templates = templates + ReminderTemplate(newTitle.trim(), km, mo)
                        prefs.reminderTemplates = templates
                        newTitle = ""; newKm = ""; newMonths = ""
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(str(R.string.add_template)) }
        }
    }

    pickerDav?.let { dav ->
        FolderPickerDialog(
            dav = dav,
            initial = folder,
            onPick = { folder = it; pickerDav = null },
            onDismiss = { pickerDav = null },
        )
    }
}
