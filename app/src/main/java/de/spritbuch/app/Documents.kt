package de.spritbuch.app

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
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Ein Fahrzeugdokument mit einer oder mehreren Seiten (Fotos oder PDF), z. B. Fahrzeugschein in Einzelseiten. */
@Serializable
data class VehicleDoc(
    val id: String = newId(),
    val type: String = TYPE_REGISTRATION,
    val title: String = "",
    /** Datum des Dokuments (bei TÜV-Berichten das Prüfdatum – daraus entsteht die Historie). */
    val date: Long? = null,
    val files: List<String> = emptyList(),
) {
    companion object {
        const val TYPE_REGISTRATION = "Fahrzeugschein"
        const val TYPE_INSURANCE = "Versicherungskarte"
        const val TYPE_INSPECTION = "TÜV-Bericht"
        const val TYPE_OTHER = "Sonstiges"
        val types = listOf(TYPE_REGISTRATION, TYPE_INSURANCE, TYPE_INSPECTION, TYPE_OTHER)

        private val json = Json { ignoreUnknownKeys = true }

        fun parseList(s: String): List<VehicleDoc> =
            if (s.isBlank()) emptyList() else runCatching { json.decodeFromString<List<VehicleDoc>>(s) }.getOrDefault(emptyList())

        fun encodeList(l: List<VehicleDoc>): String = if (l.isEmpty()) "" else json.encodeToString(l)
    }

    fun label(): String = when (type) {
        TYPE_OTHER -> title.ifBlank { lbl(type) }
        else -> lbl(type) + if (title.isNotBlank()) " – $title" else ""
    }
}

/** Dokumente sortiert: feste Dokumente zuerst, TÜV-Berichte als Historie (neueste oben). */
fun Vehicle.documentList(): List<VehicleDoc> =
    VehicleDoc.parseList(documents).sortedWith(compareBy({ VehicleDoc.types.indexOf(it.type) }, { -(it.date ?: 0L) }))

suspend fun Repository.saveDocument(vehicleId: String, doc: VehicleDoc) {
    val v = getVehicle(vehicleId) ?: return
    val list = VehicleDoc.parseList(v.documents).filter { it.id != doc.id } + doc
    saveVehicle(v.copy(documents = VehicleDoc.encodeList(list)))
}

suspend fun Repository.deleteDocument(vehicleId: String, docId: String) {
    val v = getVehicle(vehicleId) ?: return
    saveVehicle(v.copy(documents = VehicleDoc.encodeList(VehicleDoc.parseList(v.documents).filter { it.id != docId })))
}

private fun docIcon(type: String): ImageVector = when (type) {
    VehicleDoc.TYPE_REGISTRATION -> Icons.Default.Badge
    VehicleDoc.TYPE_INSURANCE -> Icons.Default.Security
    VehicleDoc.TYPE_INSPECTION -> Icons.Default.FactCheck
    else -> Icons.Default.Description
}

/** Dokumente in der Fahrzeugübersicht. */
fun LazyListScope.documentItems(nav: NavController, v: Vehicle) {
    val docs = v.documentList()
    item(key = "docs-head") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { SectionTitle(str(R.string.documents)) }
            TextButton(onClick = { nav.navigate("docEdit/${v.id}/new") }) { Text(str(R.string.plus_new)) }
        }
    }
    if (docs.isEmpty()) item(key = "docs-empty") {
        Text(str(R.string.documents_empty), style = MaterialTheme.typography.bodySmall)
    }
    docs.forEach { d ->
        item(key = "doc-" + d.id) {
            Card(Modifier.fillMaxWidth().clickable { nav.navigate("docEdit/${v.id}/${d.id}") }) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(docIcon(d.type), contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(d.label(), style = MaterialTheme.typography.titleSmall)
                        Text(
                            listOfNotNull(d.date?.date(), plural(R.plurals.pl_pages, d.files.size)).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

/** Dokument anlegen/bearbeiten: Art, Bezeichnung, Datum und beliebig viele Seiten. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocEditScreen(nav: NavController, vehicleId: String, docId: String) {
    val ctx = LocalContext.current
    val repo = ctx.repo
    val scope = rememberCoroutineScope()
    val isNew = docId == "new"
    // Eingaben überstehen das Neuerstellen der Activity (z. B. während Kamera/Dateiauswahl)
    var loaded by rememberSaveable { mutableStateOf(false) }
    var base by remember { mutableStateOf<VehicleDoc?>(null) }
    var type by rememberSaveable { mutableStateOf(VehicleDoc.TYPE_REGISTRATION) }
    var title by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableLongStateOf(0L) }
    var files by rememberSaveable { mutableStateOf(listOf<String>()) }
    var showDate by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(docId) {
        val d = if (isNew) VehicleDoc() else repo.getVehicle(vehicleId)?.documentList()?.firstOrNull { it.id == docId } ?: return@LaunchedEffect
        base = d
        if (loaded) return@LaunchedEffect
        loaded = true
        type = d.type
        title = d.title
        date = d.date ?: 0L
        files = d.files
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(str(if (isNew) R.string.document_new else R.string.document)) },
                navigationIcon = { BackButton(nav) },
                actions = {
                    if (!isNew) IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Default.Delete, contentDescription = str(R.string.delete))
                    }
                    IconButton(onClick = {
                        val b = base ?: return@IconButton
                        if (files.isEmpty()) { error = str(R.string.document_need_page); return@IconButton }
                        scope.launch {
                            repo.saveDocument(vehicleId, b.copy(type = type, title = title.trim(), date = date.takeIf { it > 0 }, files = files))
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
            SingleChips(VehicleDoc.types.map { lbl(it) }, lbl(type)) { s ->
                VehicleDoc.types.firstOrNull { lbl(it) == s }?.let {
                    type = it
                    // TÜV-Berichte brauchen ein Datum für die Historie
                    if (it == VehicleDoc.TYPE_INSPECTION && date == 0L) date = now()
                }
            }
            OutlinedTextField(
                title, { title = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text(str(if (type == VehicleDoc.TYPE_OTHER) R.string.document_title else R.string.document_title_optional)) },
            )
            OutlinedButton(onClick = { showDate = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.CalendarToday, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (date > 0) date.date() else str(R.string.document_date))
            }
            Text(str(R.string.document_pages_info), style = MaterialTheme.typography.bodySmall)
            AttachmentSection(files) { files = it; error = null }
        }
    }
    if (showDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = toUtcDate(if (date > 0) date else now()))
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { date = combineDate(it, if (date > 0) date else now()) }
                    showDate = false
                }) { Text(str(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text(str(R.string.cancel)) } },
        ) { DatePicker(state = state) }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(str(R.string.document_delete_q)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch { repo.deleteDocument(vehicleId, docId); nav.popBackStack() }
                }) { Text(str(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(str(R.string.cancel)) } },
        )
    }
}
