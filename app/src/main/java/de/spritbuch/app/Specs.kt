package de.spritbuch.app

import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.consumeWindowInsets
import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.zIndex
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

    /** Markiert eine selbst festgelegte Reihenfolge (ältere Stände werden nach der Standardreihenfolge sortiert). */
    private const val ORDERED = "#order"

    /** Speichert in der Reihenfolge der Liste – so erscheinen die Felder auch in der Übersicht. */
    fun encode(l: List<SpecField>): String = l.filter { it.v.isNotBlank() && it.k != ORDERED }
        .let { if (it.isEmpty()) "" else json.encodeToString(listOf(SpecField(ORDERED, "1")) + it) }

    /** Standardreihenfolge: vordefinierte Felder der Fahrzeugart, dann die übrigen, dann eigene. */
    fun defaultOrder(v: Vehicle, fields: List<SpecField>): List<SpecField> {
        val order = keys(v.vehicleType) + (car + motorcycle).distinct()
        return fields.filter { it.k != CUSTOM }.sortedBy { order.indexOf(it.k).let { i -> if (i < 0) Int.MAX_VALUE else i } } +
            fields.filter { it.k == CUSTOM }
    }

    /** Ausgefüllte Felder in der gespeicherten Reihenfolge (frei verschiebbar). */
    fun of(v: Vehicle): List<SpecField> {
        val raw = parse(v.specs)
        val all = raw.filter { it.v.isNotBlank() && it.k != ORDERED }
        return if (raw.any { it.k == ORDERED }) all else defaultOrder(v, all)
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

/**
 * Steckbrief bearbeiten: vordefinierte Felder der Fahrzeugart plus beliebig viele eigene.
 * Oben lassen sich die ausgefüllten Einträge per Ziehen am Griff frei anordnen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpecsEditScreen(nav: NavController, vehicleId: String) {
    val repo = LocalContext.current.repo
    val scope = rememberCoroutineScope()
    var vehicle by remember { mutableStateOf<Vehicle?>(null) }
    var loaded by rememberSaveable { mutableStateOf(false) }
    val values = rememberSaveable(saver = StringMapSaver) { mutableStateMapOf<String, String>() }
    // eigene Felder als „Kennung␟Bezeichnung␟Wert“, damit sie den Neuaufbau der Activity überstehen
    var custom by rememberSaveable { mutableStateOf(listOf<String>()) }
    // Reihenfolge als Kennungen: Schlüssel der vordefinierten Felder bzw. „c:…“ für eigene
    var order by rememberSaveable { mutableStateOf(listOf<String>()) }
    LaunchedEffect(vehicleId) {
        val v = repo.getVehicle(vehicleId) ?: return@LaunchedEffect
        vehicle = v
        if (loaded) return@LaunchedEffect
        loaded = true
        val ids = mutableListOf<String>()
        val cs = mutableListOf<String>()
        Specs.of(v).forEach { f ->
            if (f.k == Specs.CUSTOM) {
                val id = "c:" + java.util.UUID.randomUUID().toString().take(8)
                cs += id + SEP + f.label + SEP + f.v
                ids += id
            } else {
                values[f.k] = f.v
                ids += f.k
            }
        }
        custom = cs
        order = ids
    }
    fun customParts(e: String) = e.split(SEP, limit = 3).let { Triple(it[0], it.getOrElse(1) { "" }, it.getOrElse(2) { "" }) }
    val v = vehicle
    // Ausgefüllte Einträge in Anzeigereihenfolge: bekannte Reihenfolge, neu ausgefüllte hinten an
    val filled: List<String> = run {
        val keys = (if (v != null) Specs.keys(v.vehicleType) else emptyList()) + values.keys.sorted()
        val all = keys.distinct().filter { (values[it] ?: "").isNotBlank() } +
            custom.map { customParts(it) }.filter { it.third.isNotBlank() }.map { it.first }
        order.filter { it in all } + all.filter { it !in order }
    }
    fun labelOf(id: String): String =
        if (id.startsWith("c:")) custom.map { customParts(it) }.firstOrNull { it.first == id }?.second?.ifBlank { str(R.string.specs_custom) } ?: ""
        else Specs.name(id)
    fun valueOf(id: String): String =
        if (id.startsWith("c:")) custom.map { customParts(it) }.firstOrNull { it.first == id }?.third ?: "" else values[id] ?: ""

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(str(R.string.specs)) },
                navigationIcon = { BackButton(nav) },
                actions = {
                    IconButton(onClick = {
                        val cur0 = vehicle ?: return@IconButton
                        val list = filled.map { id ->
                            if (id.startsWith("c:")) {
                                val (_, label, value) = customParts(custom.first { customParts(it).first == id })
                                SpecField(Specs.CUSTOM, value.trim(), label.trim().ifBlank { str(R.string.specs_custom) })
                            } else SpecField(id, (values[id] ?: "").trim())
                        }
                        scope.launch {
                            // aktuellen Stand holen, damit parallel synchronisierte Änderungen am Fahrzeug erhalten bleiben
                            val cur = repo.getVehicle(vehicleId) ?: cur0
                            repo.saveVehicle(cur.copy(specs = Specs.encode(list), specsUpdatedAt = now()))
                            nav.popBackStack()
                        }
                    }) { Icon(Icons.Default.Check, contentDescription = str(R.string.save)) }
                },
            )
        },
    ) { pad ->
        if (v == null) return@Scaffold
        Column(
            Modifier.padding(pad).consumeWindowInsets(pad).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(str(R.string.specs_info), style = MaterialTheme.typography.bodySmall)
            if (filled.size > 1) {
                SectionTitle(str(R.string.specs_order))
                Text(str(R.string.specs_order_info), style = MaterialTheme.typography.bodySmall)
                ReorderList(filled, { labelOf(it) }, { valueOf(it) }) { order = it }
                HorizontalDivider()
            }
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
                val (id, label, value) = customParts(entry)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedTextField(
                            label, { n -> custom = custom.toMutableList().also { it[i] = id + SEP + n.replace(SEP, "") + SEP + value } },
                            singleLine = true, modifier = Modifier.fillMaxWidth(),
                            label = { Text(str(R.string.specs_custom_label)) },
                        )
                        OutlinedTextField(
                            value, { n -> custom = custom.toMutableList().also { it[i] = id + SEP + label + SEP + n } },
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
            TextButton(onClick = { custom = custom + ("c:" + java.util.UUID.randomUUID().toString().take(8) + SEP + SEP) }) {
                Text(str(R.string.specs_custom_add))
            }
        }
    }
}

/**
 * Einträge per Ziehen am Griff (oder lange drücken) verschieben. Keine Lazy-Liste, weil sie in einer
 * scrollbaren Spalte steckt; der Steckbrief hat nur wenige Dutzend Einträge.
 */
@Composable
private fun ReorderList(ids: List<String>, label: (String) -> String, value: (String) -> String, onMove: (List<String>) -> Unit) {
    var dragging by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    val heights = remember { mutableStateMapOf<String, Int>() }
    // aktuelle Liste für die Gesten (die Lambdas überleben Neukompositionen)
    val current by rememberUpdatedState(ids)
    Card(Modifier.fillMaxWidth()) {
        Column {
            ids.forEachIndexed { i, id -> key(id) {
                val isDragged = dragging == id
                Row(
                    Modifier
                        .fillMaxWidth()
                        .zIndex(if (isDragged) 1f else 0f)
                        .graphicsLayer { translationY = if (isDragged) offset else 0f }
                        .background(if (isDragged) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                        .onGloballyPositioned { heights[id] = it.size.height }
                        .pointerInput(id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { dragging = id; offset = 0f },
                                onDragEnd = { dragging = null; offset = 0f },
                                onDragCancel = { dragging = null; offset = 0f },
                            ) { change, drag ->
                                change.consume()
                                offset += drag.y
                                val list = current.toMutableList()
                                val idx = list.indexOf(id)
                                val next = list.getOrNull(idx + 1)
                                val prev = list.getOrNull(idx - 1)
                                if (next != null && offset > (heights[next] ?: 0) / 2f) {
                                    offset -= heights[next] ?: 0
                                    list.removeAt(idx); list.add(idx + 1, id); onMove(list)
                                } else if (prev != null && offset < -(heights[prev] ?: 0) / 2f) {
                                    offset += heights[prev] ?: 0
                                    list.removeAt(idx); list.add(idx - 1, id); onMove(list)
                                }
                            }
                        }
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.DragHandle, contentDescription = str(R.string.specs_move),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(label(id), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(value(id), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    }
                    // Alternativ ohne Ziehen: Pfeile
                    IconButton(enabled = i > 0, onClick = {
                        onMove(ids.toMutableList().also { it.removeAt(i); it.add(i - 1, id) })
                    }) { Icon(Icons.Default.KeyboardArrowUp, contentDescription = str(R.string.move_up)) }
                    IconButton(enabled = i < ids.size - 1, onClick = {
                        onMove(ids.toMutableList().also { it.removeAt(i); it.add(i + 1, id) })
                    }) { Icon(Icons.Default.KeyboardArrowDown, contentDescription = str(R.string.move_down)) }
                }
            } }
        }
    }
}
