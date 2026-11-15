package de.spritbuch.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.launch

/** Letzte bekannte Profiltiefe eines Radsatzes (beim Einlagern gemessen oder beim Montieren). */
data class SetTread(val entry: ServiceEntry, val values: Map<String, Double>, val stored: Boolean)

fun lastTreadOf(set: TireSet, tireHistory: List<ServiceEntry>): SetTread? {
    for (s in tireHistory.sortedByDescending { it.timestamp }) {
        val t = TreadCodec.parse(s.treadDepth)
        if (s.removedTireSetId == set.id) {
            val m = t.mapNotNull { (k, v) -> v.old?.let { k to it } }.toMap()
            if (m.isNotEmpty()) return SetTread(s, m, stored = true)
        }
        if (s.tireSetId == set.id) {
            val m = t.mapNotNull { (k, v) -> v.new?.let { k to it } }.toMap()
            if (m.isNotEmpty()) return SetTread(s, m, stored = false)
        }
    }
    return null
}

/** Radsatz-Karte für den Service-Reiter. */
@Composable
fun TireSetCard(v: Vehicle, set: TireSet, mounted: Boolean, tread: SetTread?, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = if (mounted) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        else CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(set.season.ifBlank { "Radsatz" }, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(
                    if (mounted) "montiert" else "eingelagert",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (set.sameAxles) {
                if (set.frontTire.isNotBlank() || set.sizeFront.isNotBlank()) {
                    Text(listOf(set.frontTire, set.sizeFront).filter { it.isNotBlank() }.joinToString(" · "))
                }
            } else {
                Text("Vorne: " + listOf(set.frontTire, set.sizeFront).filter { it.isNotBlank() }.joinToString(" · "))
                Text("Hinten: " + listOf(set.rearTire, set.sizeRear).filter { it.isNotBlank() }.joinToString(" · "))
            }
            if (tread != null) {
                val labels = Options.tirePositions(v.vehicleType).toMap()
                Text(
                    "Profil " + tread.values.entries.joinToString(" · ") { (k, mm) -> "${labels[k]?.let { short(it) } ?: k} ${mm.fmt(1)}" } +
                        " mm (${if (tread.stored) "eingelagert" else "montiert"} ${tread.entry.timestamp.date()})",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Text("Noch keine Profiltiefe erfasst", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun short(label: String) = when (label) {
    "Vorne links" -> "VL"; "Vorne rechts" -> "VR"; "Hinten links" -> "HL"; "Hinten rechts" -> "HR"
    "Vorne" -> "v"; "Hinten" -> "h"; else -> label
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TireSetEditScreen(nav: NavController, vehicleId: String, setId: String) {
    val repo = LocalContext.current.repo
    val scope = rememberCoroutineScope()
    val isNew = setId == "new"
    var base by remember { mutableStateOf<TireSet?>(null) }
    var veh by remember { mutableStateOf<Vehicle?>(null) }
    var season by remember { mutableStateOf("") }
    var brandF by remember { mutableStateOf("") }
    var modelF by remember { mutableStateOf("") }
    var sizeF by remember { mutableStateOf("") }
    var brandR by remember { mutableStateOf("") }
    var modelR by remember { mutableStateOf("") }
    var sizeR by remember { mutableStateOf("") }
    var same by remember { mutableStateOf(true) }
    var note by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(setId) {
        val v = repo.getVehicle(vehicleId)
        veh = v
        val t = if (isNew) TireSet(vehicleId = vehicleId) else repo.getTireSet(setId) ?: return@LaunchedEffect
        base = t
        season = t.season
        brandF = t.brandFront; modelF = t.modelFront; sizeF = t.sizeFront
        brandR = t.brandRear; modelR = t.modelRear; sizeR = t.sizeRear
        note = t.note
        // Motorrad: Vorder- und Hinterreifen sind praktisch immer verschieden
        same = if (isNew) v?.vehicleType != "Motorrad" else t.sameAxles
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "Neuer Radsatz" else "Radsatz") },
                navigationIcon = { BackButton(nav) },
                actions = {
                    if (!isNew) IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "Löschen")
                    }
                    IconButton(onClick = {
                        val b = base ?: return@IconButton
                        scope.launch {
                            repo.saveTireSet(
                                b.copy(
                                    season = season,
                                    brandFront = brandF.trim(), modelFront = modelF.trim(), sizeFront = sizeF.trim(),
                                    brandRear = if (same) brandF.trim() else brandR.trim(),
                                    modelRear = if (same) modelF.trim() else modelR.trim(),
                                    sizeRear = if (same) sizeF.trim() else sizeR.trim(),
                                    note = note.trim(),
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
        val moto = veh?.vehicleType == "Motorrad"
        Column(
            Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionTitle("Reifenart")
            SingleChips(Options.tireTypes, season) { season = it }
            LabeledSwitch("Unterschiedliche Reifen je Achse", !same) { same = !it }
            Text(
                if (same) "Alle Räder haben dieselben Reifen." else "Hersteller, Reifenname und Größe je Achse eintragen (z. B. Mischbereifung).",
                style = MaterialTheme.typography.bodySmall,
            )
            SectionTitle(if (same) "Reifen" else if (moto) "Vorderrad" else "Vorderachse")
            SuggestField("Hersteller", brandF, Options.tireBrands) { brandF = it }
            OutlinedTextField(modelF, { modelF = it }, label = { Text("Reifenname (z. B. Pilot Sport 5)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(sizeF, { sizeF = it }, label = { Text("Größe (z. B. 245/40 R19 98Y)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (!same) {
                SectionTitle(if (moto) "Hinterrad" else "Hinterachse")
                SuggestField("Hersteller", brandR, Options.tireBrands) { brandR = it }
                OutlinedTextField(modelR, { modelR = it }, label = { Text("Reifenname") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(sizeR, { sizeR = it }, label = { Text("Größe (z. B. 275/35 R19 100Y)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            OutlinedTextField(
                note, { note = it }, label = { Text("Notiz (Felgen, DOT, Lagerort …)") }, minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Radsatz löschen?") },
            text = { Text("Bisherige Radwechsel-Einträge bleiben erhalten.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch { repo.deleteTireSet(setId); nav.popBackStack() }
                }) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen") } },
        )
    }
}
