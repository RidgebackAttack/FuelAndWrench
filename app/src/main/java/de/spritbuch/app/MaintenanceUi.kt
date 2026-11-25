package de.spritbuch.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import kotlinx.coroutines.launch

/** Auffälligkeiten in den Betankungen aller Fahrzeuge. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChecksScreen(nav: NavController) {
    val ctx = LocalContext.current
    val repo = ctx.repo
    val vehicles by repo.vehicles.collectAsStateWithLifecycle(emptyList())
    val refuels by repo.allRefuels.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    val issues = remember(vehicles, refuels) {
        vehicles.filter { Prefs(ctx).shows(it.id) }.flatMap { v -> DataCheck.open(ctx, v, refuels).map { v to it } }
    }
    Scaffold(topBar = { TopAppBar(title = { Text(str(R.string.check_title)) }, navigationIcon = { BackButton(nav) }) }) { pad ->
        LazyColumn(
            Modifier.padding(pad).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Text(str(R.string.check_info), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
            if (issues.isEmpty()) item { Text(str(R.string.check_none), style = MaterialTheme.typography.bodyLarge) }
            items(issues, key = { it.second.key }) { (v, i) ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${v.name} · ${i.refuel.timestamp.dateTime()}", style = MaterialTheme.typography.titleSmall)
                        Text(i.text, style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { nav.navigate("refuelEdit/${v.id}/${i.refuel.id}") }) { Text(str(R.string.edit)) }
                            TextButton(onClick = { scope.launch { DataCheck.ignore(ctx, i) } }) { Text(str(R.string.check_ignore)) }
                        }
                    }
                }
            }
        }
    }
}

private fun Long.mb(): String = (this / 1024.0 / 1024.0).fmt(1) + " MB"

/** Einstellungen → Datenpflege: Prüfung, Speicher aufräumen, Sync-Protokoll, Absturzberichte. */
@Composable
fun MaintenanceSection(nav: NavController) {
    val ctx = LocalContext.current
    val repo = ctx.repo
    val scope = rememberCoroutineScope()
    val vehicles by repo.vehicles.collectAsStateWithLifecycle(emptyList())
    val refuels by repo.allRefuels.collectAsStateWithLifecycle(emptyList())
    val issues = remember(vehicles, refuels) { vehicles.filter { Prefs(ctx).shows(it.id) }.sumOf { DataCheck.open(ctx, it, refuels).size } }
    var busy by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<StorageCleanup.Found?>(null) }
    var cleanupInfo by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Datenprüfung
        SectionTitle(str(R.string.check_title))
        Text(
            if (issues == 0) str(R.string.check_none) else plural(R.plurals.pl_check_issues, issues),
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(onClick = { nav.navigate("checks") }) { Text(str(R.string.check_open)) }
        HorizontalDivider()

        // Speicher aufräumen
        SectionTitle(str(R.string.cleanup_title))
        Text(str(R.string.cleanup_info), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(enabled = !busy, onClick = {
            busy = true
            cleanupInfo = null
            scope.launch {
                val f = runCatching { SyncManager.findOrphans(ctx) }.getOrNull()
                busy = false
                if (f == null || f.isEmpty) cleanupInfo = str(R.string.cleanup_nothing) else found = f
            }
        }) { Text(str(R.string.cleanup_scan)) }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        cleanupInfo?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        HorizontalDivider()

        // Löschvermerke
        Text(str(R.string.purge_info), style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()

        // Sync-Protokoll
        SectionTitle(str(R.string.sync_log_title))
        var log by remember { mutableStateOf(SyncLog.entries(ctx)) }
        var all by remember { mutableStateOf(false) }
        if (log.isEmpty()) Text("–") else {
            (if (all) log else log.take(5)).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (log.size > 5) TextButton(onClick = { all = !all }) { Text(if (all) str(R.string.specs_show_less) else str(R.string.specs_show_all, log.size)) }
                TextButton(onClick = { SyncLog.clear(ctx); log = emptyList() }) { Text(str(R.string.debug_log_clear)) }
            }
        }
        HorizontalDivider()

        // Absturzberichte
        SectionTitle(str(R.string.crash_title))
        var crashes by remember { mutableIntStateOf(CrashLog.entries(ctx).size) }
        var crashInfo by remember { mutableStateOf<String?>(null) }
        Text(
            if (crashes == 0) str(R.string.crash_none) else plural(R.plurals.pl_crashes, crashes),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(str(R.string.crash_info), style = MaterialTheme.typography.bodySmall)
        if (crashes > 0) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { runCatching { DebugLog.shareCrashes(ctx) }.onFailure { crashInfo = it.message } }) { Text(str(R.string.debug_log_share)) }
            TextButton(onClick = { CrashLog.clear(ctx); crashes = 0 }) { Text(str(R.string.debug_log_clear)) }
        }
        crashInfo?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }

    found?.let { f ->
        AlertDialog(
            onDismissRequest = { found = null },
            title = { Text(str(R.string.cleanup_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (f.local.isNotEmpty()) Text(str(R.string.cleanup_local, plural(R.plurals.pl_files, f.local.size), f.localBytes.mb()))
                    if (f.remote.isNotEmpty()) Text(str(R.string.cleanup_remote, plural(R.plurals.pl_files, f.remote.size), f.remoteBytes.mb()))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    found = null
                    busy = true
                    scope.launch {
                        runCatching { SyncManager.deleteOrphans(ctx, f) }
                        busy = false
                        cleanupInfo = str(R.string.cleanup_done, (f.localBytes + f.remoteBytes).mb())
                    }
                }) { Text(str(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { found = null }) { Text(str(R.string.cancel)) } },
        )
    }
}

/** Kurzer Gesamtüberblick über alle Kosten für die Statistik. */
@Composable
fun TotalCostCard(v: Vehicle, fuel: Double, service: Double, other: Double, distance: Int, firstTs: Long?) {
    val total = fuel + service + other
    if (total <= 0) return
    val cur = v.currency
    val du = v.distanceUnit
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(str(R.string.total_cost_title), style = MaterialTheme.typography.titleSmall)
            Text("${total.fmt(0)} $cur", style = MaterialTheme.typography.headlineSmall)
            Text(
                listOfNotNull(
                    str(R.string.legend_fuel) + " " + fuel.fmt(0),
                    str(R.string.tab_service) + " " + service.fmt(0),
                    if (other > 0) str(R.string.legend_other) + " " + other.fmt(0) else null,
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
            )
            if (distance > 0) {
                Text(str(R.string.total_cost_per_km, du, (total / distance).fmt(3) + " " + cur), style = MaterialTheme.typography.bodyMedium)
                Text(str(R.string.total_cost_per_100, du, (total / distance * 100).fmt(2) + " " + cur), style = MaterialTheme.typography.bodySmall)
            }
            firstTs?.let { start ->
                val months = ((now() - start) / (30.44 * 24 * 3600 * 1000)).coerceAtLeast(1.0)
                Text(str(R.string.total_cost_per_month, (total / months).fmt(0) + " " + cur), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
