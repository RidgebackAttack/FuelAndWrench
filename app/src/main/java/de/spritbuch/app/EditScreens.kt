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
import kotlinx.coroutines.launch
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
    var lastOdo by remember { mutableStateOf<Int?>(null) }
    var veh by remember { mutableStateOf<Vehicle?>(null) }
    var locating by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var showDate by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun locate() {
        scope.launch {
            locating = true
            info = null
            try {
                val loc = LocationHelper.current(ctx)
                if (loc == null) {
                    info = "Keine GPS-Position (GPS eingeschaltet? Freie Sicht zum Himmel?)"
                } else {
                    lat = loc.latitude
                    lon = loc.longitude
                    val p = Prefs(ctx)
                    val st = if (p.useOverpass) LocationHelper.nearbyStation(loc.latitude, loc.longitude) else null
                    st?.brand?.let { brand = it }
                    val addr = st?.address ?: if (p.useGeocoder) LocationHelper.address(ctx, loc.latitude, loc.longitude) else null
                    addr?.let { address = it }
                    info = when {
                        !p.useOverpass -> "Position übernommen (Tankstellensuche ist in den Einstellungen aus)"
                        st != null -> "Tankstelle erkannt"
                        else -> "Keine Tankstelle in der Nähe gefunden – bitte Betreiber prüfen"
                    }
                }
            } finally {
                locating = false
            }
        }
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.ACCESS_FINE_LOCATION] == true) locate()
        else info = "Für GPS wird der genaue Standort benötigt (nicht „ungefähr“)"
    }

    fun requestLocate() {
        if (LocationHelper.hasPermission(ctx)) locate()
        else permLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    LaunchedEffect(refuelId) {
        val last = repo.lastRefuel(vehicleId)
        val v = repo.getVehicle(vehicleId)
        veh = v
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
                title = { Text(if (isNew) "Tanken" else "Tankvorgang") },
                navigationIcon = { BackButton(nav) },
                actions = {
                    if (!isNew) IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "Löschen")
                    }
                    IconButton(onClick = {
                        val b = base ?: return@IconButton
                        val odo = odometer.toIntKm()
                        val a = amount.toDoubleDe()
                        if (odo == null || odo <= 0) { error = "Bitte Kilometerstand eingeben"; return@IconButton }
                        if (a == null || a <= 0) { error = "Bitte getankte Menge eingeben"; return@IconButton }
                        val t = total.toDoubleDe() ?: ((ppu.toDoubleDe() ?: 0.0) * a)
                        scope.launch {
                            repo.saveRefuel(
                                b.copy(
                                    timestamp = timestamp, odometer = odo, amount = a, totalPrice = t,
                                    fullTank = fullTank, missedPrevious = missed, fuelType = fuelType,
                                    stationBrand = brand.trim(), stationAddress = address.trim(),
                                    latitude = lat, longitude = lon,
                                    extras = Options.extras.filter { it in extras }.joinToString("|"),
                                    drivingStyle = style, routeType = route, tireType = tires, note = note.trim(),
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
                Text(timestamp.dateTime())
            }
            NumberField("Kilometerstand", odometer, { odometer = it }, suffix = du, decimal = false)
            lastOdo?.let { if (it > 0) Text("Bisher: ${it.dist(du)}", style = MaterialTheme.typography.bodySmall) }
            ChoiceField("Kraftstoffart", fuelType, Options.fuelTypes) { fuelType = it }
            NumberField("Menge", amount, { amount = it; recalcTotal() }, suffix = unit)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("Preis pro $unit", ppu, { ppu = it; recalcTotal() }, suffix = cur, modifier = Modifier.weight(1f))
                NumberField("Gesamt", total, {
                    total = it
                    val a = amount.toDoubleDe()
                    val t = it.toDoubleDe()
                    if (a != null && t != null && a > 0) ppu = (t / a).fmt(3)
                }, suffix = cur, modifier = Modifier.weight(1f))
            }
            LabeledSwitch("Vollgetankt", fullTank) { fullTank = it }
            LabeledSwitch("Vorherige Tankung nicht erfasst", missed) { missed = it }

            HorizontalDivider()
            SectionTitle("Tankstelle")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { requestLocate() }, enabled = !locating) {
                    Icon(Icons.Default.MyLocation, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Aktuelle Position")
                }
                Spacer(Modifier.width(12.dp))
                if (locating) CircularProgressIndicator(Modifier.size(24.dp))
            }
            info?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            SuggestField("Betreiber", brand, Options.brands) { brand = it }
            OutlinedTextField(address, { address = it }, label = { Text("Adresse") }, modifier = Modifier.fillMaxWidth())
            val la = lat
            val lo = lon
            if (la != null && lo != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        String.format(Locale.GERMANY, "%.5f, %.5f", la, lo),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        val uri = Uri.parse("geo:$la,$lo?q=$la,$lo")
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                    }) {
                        Icon(Icons.Default.Map, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Karte")
                    }
                    TextButton(onClick = { lat = null; lon = null }) { Text("Entfernen") }
                }
            }

            HorizontalDivider()
            SectionTitle("Zusätzliche Verbraucher")
            MultiChips(Options.extras, extras) { extras = it }
            SectionTitle("Fahrstil")
            SingleChips(Options.drivingStyles, style) { style = it }
            SectionTitle("Strecke")
            SingleChips(Options.routeTypes, route) { route = it }
            SectionTitle("Reifen")
            SingleChips(Options.tireTypes, tires) { tires = it }

            OutlinedTextField(note, { note = it }, label = { Text("Notiz") }, modifier = Modifier.fillMaxWidth())
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
            title = { Text("Tankvorgang löschen?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch { repo.deleteRefuel(refuelId); nav.popBackStack() }
                }) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen") } },
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
    var du by remember { mutableStateOf("km") }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(reminderId) {
        du = Units.dist(repo.getVehicle(vehicleId))
        val r = if (isNew) Reminder(vehicleId = vehicleId, lastDoneKm = repo.currentOdometer(vehicleId))
        else repo.getReminder(reminderId) ?: return@LaunchedEffect
        base = r
        title = r.title
        interval = r.intervalKm.toString()
        lastDone = r.lastDoneKm.toString()
        warn = r.warnBeforeKm.toString()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "Neue Erinnerung" else "Erinnerung") },
                navigationIcon = { BackButton(nav) },
                actions = {
                    if (!isNew) IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "Löschen")
                    }
                    IconButton(onClick = {
                        val b = base ?: return@IconButton
                        val i = interval.toIntKm()
                        if (title.isBlank()) { error = "Bitte Bezeichnung eingeben"; return@IconButton }
                        if (i == null || i <= 0) { error = "Bitte Intervall eingeben"; return@IconButton }
                        scope.launch {
                            repo.saveReminder(
                                b.copy(
                                    title = title.trim(), intervalKm = i,
                                    lastDoneKm = lastDone.toIntKm() ?: 0, warnBeforeKm = warn.toIntKm() ?: 0,
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
            SuggestField("Bezeichnung", title, Options.reminderSuggestions) { title = it }
            NumberField("Intervall", interval, { interval = it }, suffix = du, decimal = false)
            NumberField("Zuletzt erledigt bei", lastDone, { lastDone = it }, suffix = du, decimal = false)
            NumberField("Vorwarnung", warn, { warn = it }, suffix = "$du vorher", decimal = false)
            val due = (lastDone.toIntKm() ?: 0) + (interval.toIntKm() ?: 0)
            Text("Nächste Fälligkeit: ${due.dist(du)}", style = MaterialTheme.typography.bodyMedium)
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Erinnerung löschen?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch { repo.deleteReminder(reminderId); nav.popBackStack() }
                }) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen") } },
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
        mutableStateOf(if (prefs.server.isNotBlank()) prefs.folder else legacy?.second ?: if (prefs.url.isBlank()) "NeedForFuel" else "")
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
    val status by SyncManager.status.collectAsStateWithLifecycle()
    val running by SyncManager.running.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        if (SyncManager.status.value.isBlank()) SyncManager.status.value = prefs.lastSync
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Einstellungen") }, navigationIcon = { BackButton(nav) }) },
    ) { pad ->
        Column(
            Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionTitle("Synchronisation")
            Text(
                "Adresse deiner Nextcloud eingeben, anmelden und Ordner wählen. " +
                    "Andere WebDAV-Server: vollständige WebDAV-Adresse eingeben und Zugangsdaten von Hand eintragen.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                server, { server = it }, label = { Text("Server (z. B. https://cloud.example.de)") }, singleLine = true,
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
                            cloudInfo = "Bitte im Browser anmelden und den Zugriff erlauben, dann hierher zurückkehren …"
                            repeat(600) {
                                kotlinx.coroutines.delay(2000)
                                val res = Nextcloud.poll(start)
                                if (res != null) {
                                    user = res.loginName
                                    password = res.appPassword
                                    cloudInfo = "Angemeldet als ${res.loginName}. Jetzt Ordner wählen."
                                    return@launch
                                }
                            }
                            cloudInfo = "Anmeldung abgelaufen – bitte erneut versuchen."
                        } catch (e: Exception) {
                            cloudInfo = "Anmeldung nicht möglich: ${e.message ?: "Server nicht erreichbar"}"
                        } finally {
                            loggingIn = false
                        }
                    }
                },
            ) { Text(if (loggingIn) "Warte auf Anmeldung …" else "Mit Nextcloud anmelden") }
            cloudInfo?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            OutlinedTextField(user, { user = it }, label = { Text("Benutzer") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                password, { password = it }, label = { Text("App-Passwort") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Ordner", style = MaterialTheme.typography.labelMedium)
                    Text("/" + folder, style = MaterialTheme.typography.bodyLarge)
                }
                OutlinedButton(
                    enabled = server.isNotBlank() && user.isNotBlank() && password.isNotBlank(),
                    onClick = {
                        scope.launch {
                            cloudInfo = "Verbinde …"
                            val root = resolveRoot()
                            cloudInfo = null
                            pickerDav = WebDavClient(root, user, password)
                        }
                    },
                ) { Text("Ordner wählen") }
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
                        SyncScheduler.schedulePeriodic(ctx)
                        SyncManager.sync(ctx)
                    }
                },
                enabled = !running && server.isNotBlank() && user.isNotBlank() && password.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Speichern und jetzt synchronisieren") }
            if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (status.isNotBlank()) Text("Letzter Abgleich: $status", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Abgleich erfolgt automatisch nach jeder Änderung, beim Öffnen der App und alle 15 Minuten im Hintergrund.",
                style = MaterialTheme.typography.bodySmall,
            )

            HorizontalDivider()
            SectionTitle("Datenschutz")
            Text(
                "Die App hat keine Werbung, kein Tracking und keine Analyse. Daten gehen nur an deinen WebDAV-Server " +
                    "und – wenn unten eingeschaltet – an die genannten Dienste.",
                style = MaterialTheme.typography.bodySmall,
            )
            LabeledSwitch("Tankstelle über OpenStreetMap suchen", useOverpass) { useOverpass = it; prefs.useOverpass = it }
            Text("Sendet beim Tippen auf „Aktuelle Position“ die Koordinaten an overpass-api.de.", style = MaterialTheme.typography.bodySmall)
            LabeledSwitch("Adresse über Android-Geocoder ermitteln", useGeocoder) { useGeocoder = it; prefs.useGeocoder = it }
            Text("Auf Handys mit Google-Diensten gehen die Koordinaten dabei an Google.", style = MaterialTheme.typography.bodySmall)
            LabeledSwitch("Netzwerk-Ortung (WLAN/Mobilfunk) erlauben", useNetworkLocation) {
                useNetworkLocation = it; prefs.useNetworkLocation = it
            }
            Text(
                "Aus = nur GPS, ohne Netzverkehr (braucht freie Sicht zum Himmel). " +
                    "An = schnellere Ortung auch unter Dächern; auf Handys mit Google-Diensten läuft sie über Google.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Die Karte lädt Kartenkacheln von tile.openstreetmap.org; dabei sieht der Server den angezeigten Kartenausschnitt.",
                style = MaterialTheme.typography.bodySmall,
            )

            HorizontalDivider()
            SectionTitle("Standard-Erinnerungen")
            Text(
                "Werden für jedes Fahrzeug automatisch angelegt, sobald ein Kilometerstand bekannt ist, und mit allen Geräten synchronisiert. " +
                    "Als zuletzt erledigt gilt der Kilometerstand beim Anlegen, das lässt sich in der Erinnerung anpassen.",
                style = MaterialTheme.typography.bodySmall,
            )
            templates.forEachIndexed { i, (title, km) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, modifier = Modifier.weight(1f))
                    Text(km.km())
                    IconButton(onClick = {
                        templates = templates.toMutableList().also { it.removeAt(i) }
                        prefs.reminderTemplates = templates
                    }) { Icon(Icons.Default.Delete, contentDescription = "Entfernen") }
                }
            }
            SuggestField("Bezeichnung", newTitle, Options.reminderSuggestions) { newTitle = it }
            NumberField("Intervall", newKm, { newKm = it }, suffix = "km", decimal = false)
            OutlinedButton(
                onClick = {
                    val km = newKm.toIntKm()
                    if (newTitle.isNotBlank() && km != null && km > 0) {
                        templates = templates + (newTitle.trim() to km)
                        prefs.reminderTemplates = templates
                        newTitle = ""; newKm = ""
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Vorlage hinzufügen") }
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
