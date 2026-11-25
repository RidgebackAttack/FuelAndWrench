package de.spritbuch.app

import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.consumeWindowInsets
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
                Text(lbl(set.season).ifBlank { str(R.string.tire_set) }, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(
                    str(if (mounted) R.string.mounted_lower else R.string.stored_lower),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (set.sameAxles) {
                if (set.frontTire.isNotBlank() || set.sizeFront.isNotBlank()) {
                    Text(listOf(set.frontTire, set.sizeFront).filter { it.isNotBlank() }.joinToString(" · "))
                }
            } else {
                Text(str(R.string.front_prefix) + listOf(set.frontTire, set.sizeFront).filter { it.isNotBlank() }.joinToString(" · "))
                Text(str(R.string.rear_prefix) + listOf(set.rearTire, set.sizeRear).filter { it.isNotBlank() }.joinToString(" · "))
            }
            TireAge.cardLine(set)?.let { (text, level) ->
                Text(
                    text, style = MaterialTheme.typography.bodySmall,
                    color = when (level) {
                        2 -> MaterialTheme.colorScheme.error
                        1 -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (tread != null) {
                Text(
                    str(
                        R.string.tread_card,
                        tread.values.entries.joinToString(" · ") { (k, mm) -> "${posShort(k)} ${mm.fmt(1)}" },
                        str(if (tread.stored) R.string.stored_lower else R.string.mounted_lower),
                        tread.entry.timestamp.date(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Text(str(R.string.no_tread_yet), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun posShort(code: String) = when (code) {
    "VL" -> str(R.string.pos_s_fl); "VR" -> str(R.string.pos_s_fr); "HL" -> str(R.string.pos_s_rl); "HR" -> str(R.string.pos_s_rr)
    "V" -> str(R.string.pos_s_f); "H" -> str(R.string.pos_s_r); else -> code
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TireSetEditScreen(nav: NavController, vehicleId: String, setId: String) {
    val repo = LocalContext.current.repo
    val scope = rememberCoroutineScope()
    val isNew = setId == "new"
    // Eingaben überstehen das Neuerstellen der Activity (z. B. während Kamera/Dateiauswahl)
    var loaded by rememberSaveable { mutableStateOf(false) }
    var base by remember { mutableStateOf<TireSet?>(null) }
    var veh by remember { mutableStateOf<Vehicle?>(null) }
    var season by rememberSaveable { mutableStateOf("") }
    var brandF by rememberSaveable { mutableStateOf("") }
    var modelF by rememberSaveable { mutableStateOf("") }
    var sizeF by rememberSaveable { mutableStateOf("") }
    var brandR by rememberSaveable { mutableStateOf("") }
    var modelR by rememberSaveable { mutableStateOf("") }
    var sizeR by rememberSaveable { mutableStateOf("") }
    var same by rememberSaveable { mutableStateOf(true) }
    var note by rememberSaveable { mutableStateOf("") }
    var dotF by rememberSaveable { mutableStateOf("") }
    var dotR by rememberSaveable { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(setId) {
        val v = repo.getVehicle(vehicleId)
        veh = v
        val t = if (isNew) TireSet(vehicleId = vehicleId) else repo.getTireSet(setId) ?: return@LaunchedEffect
        base = t
        if (loaded) return@LaunchedEffect
        loaded = true
        season = t.season
        brandF = t.brandFront; modelF = t.modelFront; sizeF = t.sizeFront
        brandR = t.brandRear; modelR = t.modelRear; sizeR = t.sizeRear
        note = t.note
        dotF = t.dotFront; dotR = t.dotRear
        // Motorrad: Vorder- und Hinterreifen sind praktisch immer verschieden
        same = if (isNew) v?.vehicleType != "Motorrad" else t.sameAxles
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(str(if (isNew) R.string.tire_set_new else R.string.tire_set)) },
                navigationIcon = { BackButton(nav) },
                actions = {
                    if (!isNew) IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Default.Delete, contentDescription = str(R.string.delete))
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
                                    dotFront = dotF.filter { it.isDigit() }, dotRear = dotR.filter { it.isDigit() },
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
        val moto = veh?.vehicleType == "Motorrad"
        Column(
            Modifier.padding(pad).consumeWindowInsets(pad).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionTitle(str(R.string.tire_kind))
            SingleChips(Options.tireTypes, season) { season = it }
            LabeledSwitch(str(R.string.different_axles), !same) { same = !it }
            Text(
                str(if (same) R.string.same_tires_info else R.string.axle_tires_info),
                style = MaterialTheme.typography.bodySmall,
            )
            SectionTitle(str(if (same) R.string.tires else if (moto) R.string.front_wheel else R.string.front_axle))
            SuggestField(str(R.string.manufacturer), brandF, Options.tireBrands) { brandF = it }
            OutlinedTextField(modelF, { modelF = it }, label = { Text(str(R.string.tire_name_hint)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(sizeF, { sizeF = it }, label = { Text(str(R.string.tire_size_hint)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (!same) {
                SectionTitle(str(if (moto) R.string.rear_wheel else R.string.rear_axle))
                SuggestField(str(R.string.manufacturer), brandR, Options.tireBrands) { brandR = it }
                OutlinedTextField(modelR, { modelR = it }, label = { Text(str(R.string.tire_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(sizeR, { sizeR = it }, label = { Text(str(R.string.tire_size_hint_rear)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            // Reifenalter: DOT je Achse bzw. Rad – auch bei gleichen Reifen können sie unterschiedlich alt sein
            SectionTitle(str(R.string.tire_dot_title))
            Text(str(R.string.tire_dot_info), style = MaterialTheme.typography.bodySmall)
            DotField(str(if (moto) R.string.front_wheel else R.string.front_axle), dotF) { dotF = it }
            DotField(str(if (moto) R.string.rear_wheel else R.string.rear_axle), dotR) { dotR = it }
            OutlinedTextField(
                note, { note = it }, label = { Text(str(R.string.tire_note_hint)) }, minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(str(R.string.tire_set_delete_q)) },
            text = { Text(str(R.string.tire_set_delete_info)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch { repo.deleteTireSet(setId); nav.popBackStack() }
                }) { Text(str(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(str(R.string.cancel)) } },
        )
    }
}

/** Reifenalter aus der DOT-Nummer: ab 6 Jahren Hinweis, ab 8 Jahren Warnung. */
object TireAge {
    const val WARN_YEARS = 6.0
    const val OLD_YEARS = 8.0

    /** „WWJJ“ (die letzten 4 Ziffern der DOT-Nummer) → Montag der Herstellungswoche, sonst null. */
    fun produced(dot: String, now: Long = now()): Long? {
        val d = dot.filter { it.isDigit() }
        if (d.length != 4) return null
        val week = d.take(2).toInt()
        val year = 2000 + d.drop(2).toInt()
        if (week !in 1..53) return null
        val cal = java.util.Calendar.getInstance().apply {
            clear()
            firstDayOfWeek = java.util.Calendar.MONDAY
            minimalDaysInFirstWeek = 4
            set(java.util.Calendar.YEAR, year)
            set(java.util.Calendar.WEEK_OF_YEAR, week)
            set(java.util.Calendar.DAY_OF_WEEK, java.util.Calendar.MONDAY)
        }
        return cal.timeInMillis.takeIf { it <= now }
    }

    fun years(dot: String, now: Long = now()): Double? =
        produced(dot, now)?.let { (now - it) / (365.25 * 24 * 3600 * 1000) }

    fun level(years: Double): Int = when {
        years >= OLD_YEARS -> 2
        years >= WARN_YEARS -> 1
        else -> 0
    }

    /** Höchstes Alter eines Satzes (vorne/hinten) oder null ohne gültige DOT. */
    fun max(set: TireSet, now: Long = now()): Double? = listOfNotNull(years(set.dotFront, now), years(set.dotRear, now)).maxOrNull()

    private fun fmt(y: Double) = str(R.string.tire_age_years, y.fmt(1))

    /** Zeile für die Reifensatz-Karte, z. B. „Alter: vorne 3,1 J. · hinten 6,4 J. – Austausch erwägen“, und die Stufe. */
    fun cardLine(set: TireSet, now: Long = now()): Pair<String, Int>? {
        val f = years(set.dotFront, now)
        val r = years(set.dotRear, now)
        if (f == null && r == null) return null
        val parts = if (f != null && r != null && kotlin.math.abs(f - r) < 0.05) listOf(fmt(f))
        else listOfNotNull(f?.let { str(R.string.front_prefix) + fmt(it) }, r?.let { str(R.string.rear_prefix) + fmt(it) })
        val lv = level(listOfNotNull(f, r).max())
        val suffix = when (lv) {
            2 -> " – " + str(R.string.tire_age_old)
            1 -> " – " + str(R.string.tire_age_warn)
            else -> ""
        }
        return str(R.string.tire_age, parts.joinToString(" · ")) + suffix to lv
    }
}

/** DOT-Eingabe mit sofortiger Anzeige von Herstellungswoche und Alter. */
@Composable
private fun DotField(label: String, value: String, onChange: (String) -> Unit) {
    val digits = value.filter { it.isDigit() }
    val years = TireAge.years(digits)
    OutlinedTextField(
        value, { onChange(it.filter { c -> c.isDigit() }.take(4)) },
        label = { Text(label) },
        placeholder = { Text("2321") },
        singleLine = true,
        isError = digits.isNotEmpty() && years == null && digits.length == 4,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
        supportingText = when {
            years != null -> { { Text(str(R.string.tire_dot_parsed, digits.take(2), "20" + digits.drop(2), years.fmt(1))) } }
            digits.length == 4 -> { { Text(str(R.string.tire_dot_invalid)) } }
            else -> null
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Warnung in der Übersicht, wenn ein Reifensatz 6 Jahre oder älter ist. */
@Composable
fun TireAgeWarning(v: Vehicle) {
    val sets by LocalContext.current.repo.tireSets(v.id).collectAsStateWithLifecycle(emptyList())
    val old = sets.filter { !it.deleted }.mapNotNull { s -> TireAge.max(s)?.takeIf { it >= TireAge.WARN_YEARS }?.let { s to it } }
    if (old.isEmpty()) return
    val worst = old.maxOf { it.second }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (TireAge.level(worst) == 2) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            old.forEach { (s, y) ->
                Text(str(R.string.tire_age_overview, s.label(), y.fmt(1)), style = MaterialTheme.typography.bodyMedium)
            }
            Text(str(if (TireAge.level(worst) == 2) R.string.tire_age_old else R.string.tire_age_warn), style = MaterialTheme.typography.bodySmall)
        }
    }
}
