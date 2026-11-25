package de.spritbuch.app

import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.consumeWindowInsets
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
import androidx.compose.runtime.saveable.rememberSaveable
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

    // Eingaben überstehen das Neuerstellen der Activity (z. B. während Kamera/Fotoauswahl)
    var loaded by rememberSaveable { mutableStateOf(false) }
    var base by remember { mutableStateOf<Refuel?>(null) }
    var timestamp by rememberSaveable { mutableLongStateOf(now()) }
    var odometer by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }
    var ppu by rememberSaveable { mutableStateOf("") }
    var total by rememberSaveable { mutableStateOf("") }
    var fullTank by rememberSaveable { mutableStateOf(true) }
    var missed by rememberSaveable { mutableStateOf(false) }
    var fuelType by rememberSaveable { mutableStateOf("") }
    var brand by rememberSaveable { mutableStateOf("") }
    var address by rememberSaveable { mutableStateOf("") }
    var lat by rememberSaveable { mutableStateOf<Double?>(null) }
    var lon by rememberSaveable { mutableStateOf<Double?>(null) }
    var extras by rememberSaveable { mutableStateOf(setOf<String>()) }
    var style by rememberSaveable { mutableStateOf("") }
    var route by rememberSaveable { mutableStateOf("") }
    var tires by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var obc by rememberSaveable { mutableStateOf("") }
    var chargeLoc by rememberSaveable { mutableStateOf("") }
    var socStart by rememberSaveable { mutableStateOf("") }
    var socEnd by rememberSaveable { mutableStateOf("") }
    var fuelOnlyType by rememberSaveable { mutableStateOf("") }
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
    val scanTarget = remember { AttachmentStore.cameraTarget(ctx) }

    var vehicleChoices by remember { mutableStateOf(listOf<Vehicle>()) }
    var cropSource by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var scanBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var review by remember { mutableStateOf<ScanReview?>(null) }
    var scanStation by rememberSaveable { mutableStateOf<String?>(null) }
    var scanAddress by rememberSaveable { mutableStateOf<String?>(null) }


    fun runScan(bitmap: android.graphics.Bitmap, crop: android.graphics.RectF) {
        scope.launch {
            scanning = true
            scanInfo = null
            try {
                val text = Ocr.recognize(ctx, bitmap, crop)
                review = run {
                    // Eigene Betreiber (aus früheren Betankungen) mit durchsuchen
                    val own = repo.db.refuels().usedBrands()
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
                    ScanReview(v, f, r.date, ReceiptParser.numbers(text))
                }
            } catch (e: Throwable) {
                DebugLog.error("Texterkennung fehlgeschlagen", e)
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
                    runScan(bmp, android.graphics.RectF(0f, 0f, 1f, 1f))
                }
                .onFailure {
                    DebugLog.error("Foto laden fehlgeschlagen", it)
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
    fun startScan() {
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
                    // Der Bon steht für die tatsächliche Tankstelle (Kartendaten können veraltet sein):
                    // Betreiber und Adresse vom Bon haben Vorrang, GPS füllt nur, was dort fehlt
                    if (scanStation == null) st?.brand?.let { brand = it }
                    if (scanAddress == null) {
                        val addr = st?.address
                            ?: (if (p.useOverpass) LocationHelper.osmAddress(loc.latitude, loc.longitude) else null)
                            ?: (if (p.useGeocoder) LocationHelper.address(ctx, loc.latitude, loc.longitude) else null)
                        if (addr != null) address = addr
                    }
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
        // Eigene, häufig genutzte Betreiber zuerst, danach die Standardliste
        brandOptions = (repo.db.refuels().usedBrands() + Options.brands).distinctBy { it.lowercase() }
        if (loaded) return@LaunchedEffect
        loaded = true
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
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { nav.popBackStack() }
                        }
                    }) { Icon(Icons.Default.Check, contentDescription = str(R.string.save)) }
                },
            )
        },
    ) { pad ->
        if (base == null) return@Scaffold
        Column(
            Modifier.padding(pad).consumeWindowInsets(pad).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
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
                OutlinedButton(onClick = { startScan() }, enabled = !scanning, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.ReceiptLong, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(str(R.string.scan_receipt))
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
            NumberField(str(R.string.odometer), odometer, { odometer = it }, suffix = du, decimal = false)
            lastOdo?.let { if (it > 0) Text(str(R.string.previously, it.dist(du)), style = MaterialTheme.typography.bodySmall) }
            val charging = fuelType == ELECTRIC
            if (veh?.isPlugInHybrid == true) {
                SingleChips(listOf(str(R.string.refuel), str(R.string.charge)), str(if (charging) R.string.charge else R.string.refuel)) { sel ->
                    if (sel.isNotEmpty()) fuelType = if (sel == str(R.string.charge)) ELECTRIC else fuelOnlyType.ifBlank { "Super E10" }
                }
            }
            if (!charging) {
                ChoiceField(str(R.string.fuel_type), fuelType, Options.fuelsFor(veh, fuelType)) { fuelType = it }
            } else if (veh?.charges != true) {
                // Fahrzeug ohne Elektro-Antrieb: Rückweg zum Kraftstoff ermöglichen
                ChoiceField(str(R.string.fuel_type), fuelType, Options.fuelsFor(veh, fuelType) + ELECTRIC) { fuelType = it }
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
            // Plausibilitätsprüfung (z. B. Lesefehler beim Bon): aktueller Preis dieser Tankstelle laut Tankerkönig
            var reported by remember { mutableStateOf<Double?>(null) }
            LaunchedEffect(lat, lon, fuelType) {
                reported = null
                val la = lat ?: return@LaunchedEffect
                val lo = lon ?: return@LaunchedEffect
                if (!isNew || !FuelPrices.enabled(ctx)) return@LaunchedEffect
                val type = FuelPrices.typeFor(fuelType) ?: return@LaunchedEffect
                reported = runCatching { FuelPrices.nearby(ctx, la, lo, type, radiusKm = 2) }.getOrNull()
                    ?.let { FuelPrices.at(it, la, lo) }?.price
            }
            val entered = ppu.toDoubleDe()
            val rep = reported
            if (rep != null && entered != null && entered > 0 && FuelPrices.deviates(entered, rep)) {
                Text(
                    str(R.string.prices_check_hint, FuelPrices.price(rep)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
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
        CropDialog(
            bmp,
            android.graphics.RectF(0.03f, 0.03f, 0.97f, 0.97f),
            str(R.string.crop_hint_receipt),
            onCancel = { cropSource = null },
        ) { rect ->
            cropSource = null
            runScan(bmp, rect)
        }
    }

    review?.let { rv ->
        val u = Units.amount(veh, fuelType)
        val c = Units.cur(veh)
        val extras = listOfNotNull(
            scanStation?.let { lbl(it) },
            scanAddress,
            rv.fuelType?.let { lbl(it) },
            rv.date?.let { (y, m, d) -> String.format(appLocale(), "%02d.%02d.%d", d, m, y) },
        ).joinToString(" · ").ifBlank { null }
        ScanReviewDialog(
            rv,
            label = { t ->
                when (t) {
                    ScanTarget.AMOUNT -> str(if (fuelType == ELECTRIC) R.string.energy else R.string.amount)
                    ScanTarget.PRICE -> str(R.string.price_per_unit, u)
                    ScanTarget.TOTAL -> str(R.string.total)
                }
            },
            format = { t, v ->
                when (t) {
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
            values[ScanTarget.AMOUNT]?.let { amount = it.fmt(2) }
            values[ScanTarget.PRICE]?.let { ppu = it.fmt(3) }
            values[ScanTarget.TOTAL]?.let { total = it.fmt(2) }
            // Fehlender Wert aus den anderen beiden berechnen
            val a = amount.toDoubleDe(); val p = ppu.toDoubleDe(); val t = total.toDoubleDe()
            if (values[ScanTarget.TOTAL] == null && a != null && p != null && values.isNotEmpty()) total = (a * p).fmt(2)
            if (values[ScanTarget.PRICE] == null && a != null && t != null && a > 0) ppu = (t / a).fmt(3)
            run {
                // Betreiber und Adresse vom Bon haben Vorrang vor den Kartendaten
                scanStation?.let { brand = it }
                scanAddress?.let { address = it }
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
