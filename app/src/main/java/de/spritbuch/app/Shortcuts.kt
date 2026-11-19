package de.spritbuch.app

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Einträge im Kontextmenü des App-Icons (lange drücken).
 * Standard: „Neue Betankung“ und „Neue Ausgabe“ mit Fahrzeugauswahl.
 * Optional: je Fahrzeug ein Eintrag (die bis zu drei zuletzt genutzten) plus „Neue Ausgabe“.
 */
object Shortcuts {
    const val EXTRA_ACTION = "fw_action"
    const val EXTRA_VEHICLE = "fw_vehicle"
    const val REFUEL = "refuel"
    const val EXPENSE = "expense"

    private fun intent(ctx: Context, action: String, vehicleId: String?) =
        Intent(ctx, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra(EXTRA_ACTION, action)
            .apply { if (vehicleId != null) putExtra(EXTRA_VEHICLE, vehicleId) }

    suspend fun update(ctx: Context) = withContext(Dispatchers.IO) {
        runCatching {
            val repo = ctx.repo
            val prefs = Prefs(ctx)
            val vehicles = repo.db.vehicles().getAllRaw().filter { !it.deleted && !it.archived && prefs.shows(it.id) }
            if (vehicles.isEmpty()) {
                ShortcutManagerCompat.removeAllDynamicShortcuts(ctx)
                return@runCatching
            }
            val list = mutableListOf<ShortcutInfoCompat>()
            if (Prefs(ctx).shortcutMode == "perVehicle") {
                val lastUse = repo.db.refuels().lastUse().associate { it.vehicleId to it.last }
                vehicles.sortedByDescending { lastUse[it.id] ?: it.updatedAt }.take(3).forEachIndexed { i, v ->
                    val label = str(if (v.isElectric) R.string.charge else R.string.refuel)
                    list += ShortcutInfoCompat.Builder(ctx, "refuel-${v.id}")
                        .setShortLabel(v.name.take(12))
                        .setLongLabel("${v.name}: $label".take(25))
                        .setIcon(IconCompat.createWithResource(ctx, if (v.isElectric) R.drawable.ic_shortcut_charge else R.drawable.ic_shortcut_fuel))
                        .setIntent(intent(ctx, REFUEL, v.id))
                        .setRank(i)
                        .build()
                }
            } else {
                val allElectric = vehicles.all { it.isElectric }
                list += ShortcutInfoCompat.Builder(ctx, "refuel")
                    .setShortLabel(str(if (allElectric) R.string.charge else R.string.refuel))
                    .setLongLabel(str(if (allElectric) R.string.charge_new else R.string.refuel_new))
                    .setIcon(IconCompat.createWithResource(ctx, if (allElectric) R.drawable.ic_shortcut_charge else R.drawable.ic_shortcut_fuel))
                    .setIntent(intent(ctx, REFUEL, vehicles.singleOrNull()?.id))
                    .setRank(0)
                    .build()
            }
            list += ShortcutInfoCompat.Builder(ctx, "expense")
                .setShortLabel(str(R.string.expense))
                .setLongLabel(str(R.string.expense_new))
                .setIcon(IconCompat.createWithResource(ctx, R.drawable.ic_shortcut_expense))
                .setIntent(intent(ctx, EXPENSE, vehicles.singleOrNull()?.id))
                .setRank(list.size)
                .build()
            ShortcutManagerCompat.setDynamicShortcuts(ctx, list)
        }
    }

    /** Ziel-Route für einen Kontextmenü-Aufruf. */
    suspend fun route(ctx: Context, action: String, vehicleId: String?): String? {
        if (action != REFUEL && action != EXPENSE) return null
        val prefs = Prefs(ctx)
        val active = ctx.repo.db.vehicles().getAllRaw().filter { !it.deleted && !it.archived && prefs.shows(it.id) }
        val id = vehicleId?.takeIf { v -> active.any { it.id == v } } ?: active.singleOrNull()?.id
        return when {
            id != null && action == REFUEL -> "refuelEdit/$id/new"
            id != null -> "expenseEdit/$id/new"
            active.isEmpty() -> null
            else -> "pick/$action"
        }
    }
}

/** Fahrzeugauswahl für „Neue Betankung“ bzw. „Neue Ausgabe“ aus dem Kontextmenü. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VehiclePickScreen(nav: NavController, action: String) {
    val ctx = LocalContext.current
    val repo = ctx.repo
    var vehicles by remember { mutableStateOf<List<Vehicle>>(emptyList()) }
    LaunchedEffect(Unit) {
        val lastUse = repo.db.refuels().lastUse().associate { it.vehicleId to it.last }
        val prefs = Prefs(ctx)
        vehicles = repo.db.vehicles().getAllRaw().filter { !it.deleted && !it.archived && prefs.shows(it.id) }
            .sortedByDescending { lastUse[it.id] ?: it.updatedAt }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(str(if (action == Shortcuts.EXPENSE) R.string.expense_new else R.string.refuel_new)) },
                navigationIcon = { BackButton(nav) },
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { Text(str(R.string.pick_vehicle), style = MaterialTheme.typography.titleMedium) }
            items(vehicles, key = { it.id }) { v ->
                Card(
                    onClick = {
                        val target = if (action == Shortcuts.EXPENSE) "expenseEdit/${v.id}/new" else "refuelEdit/${v.id}/new"
                        nav.navigate(target) { popUpTo("pick/{action}") { inclusive = true } }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        VehiclePhoto(v, Modifier.size(48.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(v.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        if (v.plate.isNotBlank()) Text(v.plate, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
