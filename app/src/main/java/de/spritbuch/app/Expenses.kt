package de.spritbuch.app

import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocalCarWash
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Toll
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import java.util.Calendar

fun expenseIcon(category: String): ImageVector = when (category) {
    "Versicherung" -> Icons.Default.Security
    "Steuer" -> Icons.Default.AccountBalance
    "Parken" -> Icons.Default.LocalParking
    "Maut" -> Icons.Default.Toll
    "Wäsche" -> Icons.Default.LocalCarWash
    "Finanzierung" -> Icons.Default.Payments
    "Gebühren" -> Icons.Default.ReceiptLong
    else -> Icons.Default.MoreHoriz
}

private fun yearOf(ts: Long) = Calendar.getInstance().apply { timeInMillis = ts }.get(Calendar.YEAR)

fun LazyListScope.expenseItems(nav: NavController, v: Vehicle, expenses: List<Expense>) {
    if (expenses.isEmpty()) {
        item {
            EmptyState(
                Icons.Default.Payments, str(R.string.empty_expenses_title), str(R.string.empty_expenses_text),
                action = str(R.string.empty_add_expense) to { nav.navigate("expenseEdit/${v.id}/new") },
            )
        }
        return
    }
    item {
        Text(
            str(R.string.services_summary, plural(R.plurals.pl_entries, expenses.size), expenses.sumOf { it.amount }.fmt() + " " + v.currency),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    expenses.groupBy { yearOf(it.timestamp) }.forEach { (year, list) ->
        item(key = "ey-$year") {
            Row(Modifier.padding(start = 4.dp, top = 8.dp)) {
                Text(year.toString(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f))
                Text("${list.sumOf { it.amount }.fmt()} ${v.currency}", style = MaterialTheme.typography.titleSmall)
            }
        }
        list.forEach { e ->
            item(key = "e-" + e.id) { ExpenseCard(v, e) { nav.navigate("expenseEdit/${v.id}/${e.id}") } }
        }
    }
}

@Composable
fun ExpenseCard(v: Vehicle, e: Expense, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(expenseIcon(e.category), contentDescription = lbl(e.category), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row {
                    Text(e.title.ifBlank { lbl(e.category) }, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text("${e.amount.fmt()} ${v.currency}", fontWeight = FontWeight.SemiBold)
                }
                Text(listOf(e.timestamp.date(), lbl(e.category)).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                if (e.note.isNotBlank()) Text(e.note, style = MaterialTheme.typography.bodySmall)
                if (e.attachments.isNotBlank()) {
                    Text("📎 " + plural(R.plurals.pl_receipts, e.attachmentList().size), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpenseEditScreen(nav: NavController, vehicleId: String, expenseId: String) {
    val ctx = LocalContext.current
    val repo = ctx.repo
    val scope = rememberCoroutineScope()
    val isNew = expenseId == "new"
    // Eingaben überstehen das Neuerstellen der Activity (z. B. während Kamera/Dateiauswahl)
    var loaded by rememberSaveable { mutableStateOf(false) }
    var base by remember { mutableStateOf<Expense?>(null) }
    var veh by remember { mutableStateOf<Vehicle?>(null) }
    var timestamp by rememberSaveable { mutableLongStateOf(now()) }
    var category by rememberSaveable { mutableStateOf("Versicherung") }
    var title by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var attachments by rememberSaveable { mutableStateOf(listOf<String>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var showDate by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(expenseId) {
        veh = repo.getVehicle(vehicleId)
        val e = if (isNew) Expense(vehicleId = vehicleId, category = "Versicherung") else repo.getExpense(expenseId) ?: return@LaunchedEffect
        base = e
        if (loaded) return@LaunchedEffect
        loaded = true
        timestamp = e.timestamp
        category = e.category
        title = e.title
        amount = if (e.amount > 0) e.amount.fmt(2) else ""
        note = e.note
        attachments = e.attachmentList()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(str(if (isNew) R.string.expense_new else R.string.expense)) },
                navigationIcon = { BackButton(nav) },
                actions = {
                    if (!isNew) IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Default.Delete, contentDescription = str(R.string.delete))
                    }
                    IconButton(onClick = {
                        val b = base ?: return@IconButton
                        val a = amount.toDoubleDe()
                        if (a == null || a <= 0) { error = str(R.string.err_enter_amount_money); return@IconButton }
                        scope.launch {
                            repo.saveExpense(
                                b.copy(
                                    timestamp = timestamp, category = category, title = title.trim(), amount = a,
                                    note = note.trim(), attachments = attachments.joinToString("|"),
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
            Modifier.padding(pad).consumeWindowInsets(pad).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedButton(onClick = { showDate = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.CalendarToday, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(timestamp.date())
            }
            SingleChips(Options.expenseCategories, category) { if (it.isNotEmpty()) category = it }
            OutlinedTextField(
                title, { title = it }, label = { Text(str(R.string.expense_title_hint)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            NumberField(str(R.string.cost), amount, { amount = it }, suffix = Units.cur(veh))
            OutlinedTextField(note, { note = it }, label = { Text(str(R.string.note)) }, minLines = 2, modifier = Modifier.fillMaxWidth())
            AttachmentSection(attachments) { attachments = it }
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

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(str(R.string.expense_delete_q)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch { repo.deleteExpense(expenseId); nav.popBackStack() }
                }) { Text(str(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(str(R.string.cancel)) } },
        )
    }
}
