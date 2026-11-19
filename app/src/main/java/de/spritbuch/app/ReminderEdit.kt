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
fun ReminderEditScreen(nav: NavController, vehicleId: String, reminderId: String) {
    val ctx = LocalContext.current
    val repo = ctx.repo
    val scope = rememberCoroutineScope()
    val isNew = reminderId == "new"
    // Eingaben überstehen das Neuerstellen der Activity (z. B. während Kamera/Dateiauswahl)
    var loaded by rememberSaveable { mutableStateOf(false) }
    var base by remember { mutableStateOf<Reminder?>(null) }
    var title by rememberSaveable { mutableStateOf("") }
    var interval by rememberSaveable { mutableStateOf("") }
    var lastDone by rememberSaveable { mutableStateOf("") }
    var warn by rememberSaveable { mutableStateOf("") }
    var months by rememberSaveable { mutableStateOf("") }
    var lastDate by rememberSaveable { mutableLongStateOf(0L) }
    var showDate by remember { mutableStateOf(false) }
    var du by remember { mutableStateOf("km") }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(reminderId) {
        du = Units.dist(repo.getVehicle(vehicleId))
        val r = if (isNew) Reminder(vehicleId = vehicleId, lastDoneKm = repo.currentOdometer(vehicleId), lastDoneDate = now())
        else repo.getReminder(reminderId) ?: return@LaunchedEffect
        base = r
        if (loaded) return@LaunchedEffect
        loaded = true
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
