package de.spritbuch.app

import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.consumeWindowInsets
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Ein Feld im Fahrzeug-Steckbrief: vordefinierter Schlüssel ([Specs.keys]) oder eigenes Feld mit [label]. */
@Serializable
data class SpecField(val k: String, val v: String, val label: String = "")

/** Fahrzeug-Steckbrief: Ölsorte, Reifendruck, Teilenummern, FIN … – griffbereit und per Antippen kopierbar. */
object Specs {
    const val CUSTOM = "custom"
    private val json = Json { ignoreUnknownKeys = true }

    private val names: Map<String, Int> = mapOf(
        "vin" to R.string.spec_vin,
        "hsn" to R.string.spec_hsn,
        "tsn" to R.string.spec_tsn,
        "engine_code" to R.string.spec_engine_code,
        "oil_spec" to R.string.spec_oil_spec,
        "oil_capacity" to R.string.spec_oil_capacity,
        "oil_filter" to R.string.spec_oil_filter,
        "air_filter" to R.string.spec_air_filter,
        "cabin_filter" to R.string.spec_cabin_filter,
        "spark_plugs" to R.string.spec_spark_plugs,
        "tire_size_front" to R.string.spec_tire_size_front,
        "tire_size_rear" to R.string.spec_tire_size_rear,
        "tire_pressure_front" to R.string.spec_tire_pressure_front,
        "tire_pressure_rear" to R.string.spec_tire_pressure_rear,
        "brake_fluid" to R.string.spec_brake_fluid,
        "coolant" to R.string.spec_coolant,
        "battery" to R.string.spec_battery,
        "wipers" to R.string.spec_wipers,
        "chain" to R.string.spec_chain,
        "sprockets" to R.string.spec_sprockets,
        "chain_slack" to R.string.spec_chain_slack,
        "brake_pads_front" to R.string.spec_brake_pads_front,
        "brake_pads_rear" to R.string.spec_brake_pads_rear,
        "fork_oil" to R.string.spec_fork_oil,
    )
    private val common = listOf(
        "vin", "hsn", "tsn", "engine_code", "oil_spec", "oil_capacity", "oil_filter", "air_filter", "spark_plugs",
        "tire_size_front", "tire_size_rear", "tire_pressure_front", "tire_pressure_rear",
    )
    private val car = common + listOf("cabin_filter", "brake_fluid", "coolant", "battery", "wipers")
    private val motorcycle = common + listOf(
        "chain", "sprockets", "chain_slack", "brake_pads_front", "brake_pads_rear", "fork_oil", "brake_fluid", "coolant", "battery",
    )

    /** Vordefinierte Felder passend zur Fahrzeugart (Motorrad mit Kette, Ritzel, Gabelöl …). */
    fun keys(vehicleType: String): List<String> = if (vehicleType == "Motorrad") motorcycle else car

    fun name(f: SpecField): String = if (f.k == CUSTOM) f.label else names[f.k]?.let { str(it) } ?: f.k
    fun name(key: String): String = names[key]?.let { str(it) } ?: key

    fun hint(key: String): String? = when (key) {
        "oil_spec" -> str(R.string.specs_hint_oil)
        "tire_pressure_front", "tire_pressure_rear" -> str(R.string.specs_hint_pressure)
        else -> null
    }

    fun parse(s: String): List<SpecField> =
        if (s.isBlank()) emptyList() else runCatching { json.decodeFromString<List<SpecField>>(s) }.getOrDefault(emptyList())

    fun encode(l: List<SpecField>): String = l.filter { it.v.isNotBlank() }.let { if (it.isEmpty()) "" else json.encodeToString(it) }

    /** Ausgefüllte Felder in fester Reihenfolge: vordefinierte zuerst (auch die der anderen Fahrzeugart), dann eigene. */
    fun of(v: Vehicle): List<SpecField> {
        val all = parse(v.specs).filter { it.v.isNotBlank() }
        val order = keys(v.vehicleType) + (car + motorcycle).distinct()
        return all.filter { it.k != CUSTOM }.sortedBy { order.indexOf(it.k).let { i -> if (i < 0) Int.MAX_VALUE else i } } +
            all.filter { it.k == CUSTOM }
    }

    fun value(v: Vehicle, key: String): String? = parse(v.specs).firstOrNull { it.k == key }?.v?.takeIf { it.isNotBlank() }
}

/** Text in die Zwischenablage; ab Android 13 zeigt das System selbst einen Hinweis. */
fun copyToClipboard(ctx: android.content.Context, label: String, text: String) {
    ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(ctx, str(R.string.copied, label), Toast.LENGTH_SHORT).show()
}

private const val SPECS_PREVIEW = 6

/** Steckbrief in der Fahrzeugübersicht. */
fun LazyListScope.specItems(nav: NavController, v: Vehicle) {
    val fields = Specs.of(v)
    item(key = "specs-head") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { SectionTitle(str(R.string.specs)) }
            TextButton(onClick = { nav.navigate("specs/${v.id}") }) { Text(str(if (fields.isEmpty()) R.string.plus_new else R.string.edit)) }
        }
    }
    if (fields.isEmpty()) {
        item(key = "specs-empty") { Text(str(R.string.specs_empty), style = MaterialTheme.typography.bodySmall) }
        return
    }
    item(key = "specs") {
        val ctx = LocalContext.current
        var all by rememberSaveable(v.id) { mutableStateOf(false) }
        val shown = if (all) fields else fields.take(SPECS_PREVIEW)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 4.dp)) {
                shown.forEachIndexed { i, f ->
                    if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 12.dp))
                    val name = Specs.name(f)
                    Row(
                        Modifier.fillMaxWidth().clickable { copyToClipboard(ctx, name, f.v) }.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(f.v, style = MaterialTheme.typography.bodyLarge)
                        }
                        Icon(
                            Icons.Default.ContentCopy, contentDescription = str(R.string.copy),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
                if (fields.size > SPECS_PREVIEW) {
                    TextButton(onClick = { all = !all }, modifier = Modifier.padding(horizontal = 4.dp)) {
                        Text(if (all) str(R.string.specs_show_less) else str(R.string.specs_show_all, fields.size))
                    }
                }
            }
        }
    }
}

private const val SEP = "\u001F"

/** Steckbrief bearbeiten: vordefinierte Felder der Fahrzeugart plus beliebig viele eigene. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpecsEditScreen(nav: NavController, vehicleId: String) {
    val repo = LocalContext.current.repo
    val scope = rememberCoroutineScope()
    var vehicle by remember { mutableStateOf<Vehicle?>(null) }
    var loaded by rememberSaveable { mutableStateOf(false) }
    val values = rememberSaveable(saver = StringMapSaver) { mutableStateMapOf<String, String>() }
    // eigene Felder als „Bezeichnung␟Wert“, damit sie den Neuaufbau der Activity überstehen
    var custom by rememberSaveable { mutableStateOf(listOf<String>()) }
    LaunchedEffect(vehicleId) {
        val v = repo.getVehicle(vehicleId) ?: return@LaunchedEffect
        vehicle = v
        if (loaded) return@LaunchedEffect
        loaded = true
        val all = Specs.parse(v.specs)
        all.filter { it.k != Specs.CUSTOM }.forEach { values[it.k] = it.v }
        custom = all.filter { it.k == Specs.CUSTOM }.map { it.label + SEP + it.v }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(str(R.string.specs)) },
                navigationIcon = { BackButton(nav) },
                actions = {
                    IconButton(onClick = {
                        val v = vehicle ?: return@IconButton
                        val list = values.filter { it.value.isNotBlank() }.map { (k, x) -> SpecField(k, x.trim()) } +
                            custom.map { it.split(SEP, limit = 2) }
                                .filter { it.getOrElse(1) { "" }.isNotBlank() }
                                .map { SpecField(Specs.CUSTOM, it[1].trim(), it[0].trim().ifBlank { str(R.string.specs_custom) }) }
                        scope.launch {
                            // aktuellen Stand holen, damit parallel synchronisierte Änderungen am Fahrzeug erhalten bleiben
                            val cur = repo.getVehicle(vehicleId) ?: v
                            repo.saveVehicle(cur.copy(specs = Specs.encode(list)))
                            nav.popBackStack()
                        }
                    }) { Icon(Icons.Default.Check, contentDescription = str(R.string.save)) }
                },
            )
        },
    ) { pad ->
        val v = vehicle ?: return@Scaffold
        Column(
            Modifier.padding(pad).consumeWindowInsets(pad).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(str(R.string.specs_info), style = MaterialTheme.typography.bodySmall)
            // vordefinierte Felder; Werte der anderen Fahrzeugart (z. B. nach Umstellung) bleiben sichtbar
            val keys = Specs.keys(v.vehicleType) + values.keys.filter { it !in Specs.keys(v.vehicleType) }.sorted()
            keys.forEach { k ->
                val hint = Specs.hint(k)
                OutlinedTextField(
                    values[k] ?: "", { values[k] = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(Specs.name(k)) },
                    placeholder = if (hint != null) { { Text(hint) } } else null,
                )
            }
            SectionTitle(str(R.string.specs_custom))
            custom.forEachIndexed { i, entry ->
                val (label, value) = entry.split(SEP, limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedTextField(
                            label, { n -> custom = custom.toMutableList().also { it[i] = n.replace(SEP, "") + SEP + value } },
                            singleLine = true, modifier = Modifier.fillMaxWidth(),
                            label = { Text(str(R.string.specs_custom_label)) },
                        )
                        OutlinedTextField(
                            value, { n -> custom = custom.toMutableList().also { it[i] = label + SEP + n } },
                            singleLine = true, modifier = Modifier.fillMaxWidth(),
                            label = { Text(str(R.string.specs_value)) },
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    IconButton(onClick = { custom = custom.toMutableList().also { it.removeAt(i) } }) {
                        Icon(Icons.Default.Delete, contentDescription = str(R.string.delete))
                    }
                }
            }
            TextButton(onClick = { custom = custom + SEP }) { Text(str(R.string.specs_custom_add)) }
        }
    }
}
