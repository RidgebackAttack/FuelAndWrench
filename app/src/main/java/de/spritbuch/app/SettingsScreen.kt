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
    var e2e by remember { mutableStateOf(prefs.syncEncrypt) }
    var e2ePass by remember { mutableStateOf(prefs.syncPassphrase) }
    val e2eOk = !e2e || e2ePass.length >= 8
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
            LabeledSwitch(str(R.string.e2e_title), e2e) { e2e = it }
            Text(str(R.string.e2e_info), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                e2ePass, { e2ePass = it }, label = { Text(str(R.string.e2e_password)) }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                isError = !e2eOk,
                supportingText = if (!e2eOk) ({ Text(str(R.string.e2e_too_short)) }) else null,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    scope.launch {
                        if (e2e != prefs.syncEncrypt) {
                            prefs.syncEncrypt = e2e
                            prefs.syncEncryptChanged = true
                        }
                        prefs.syncPassphrase = e2ePass
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
                enabled = !running && e2eOk && server.isNotBlank() && user.isNotBlank() && password.isNotBlank(),
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
            fun summaryText(s: Backup.Summary) = str(R.string.backup_summary, plural(R.plurals.pl_vehicles, s.vehicles), plural(R.plurals.pl_refuels, s.refuels), plural(R.plurals.pl_services, s.services), plural(R.plurals.pl_costs, s.expenses), plural(R.plurals.pl_files, s.files))
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
            var debugLog by remember { mutableStateOf(prefs.debugLog) }
            var logInfo by remember { mutableStateOf<String?>(null) }
            LabeledSwitch(str(R.string.debug_log), debugLog) { debugLog = it; DebugLog.setEnabled(ctx, it); logInfo = null }
            Text(str(R.string.debug_log_info), style = MaterialTheme.typography.bodySmall)
            if (debugLog) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    if (DebugLog.exists(ctx)) runCatching { DebugLog.share(ctx) }.onFailure { logInfo = it.message }
                    else logInfo = str(R.string.debug_log_empty)
                }) { Text(str(R.string.debug_log_share)) }
                TextButton(onClick = { DebugLog.clear(ctx); logInfo = str(R.string.debug_log_empty) }) { Text(str(R.string.debug_log_clear)) }
            }
            logInfo?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

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
