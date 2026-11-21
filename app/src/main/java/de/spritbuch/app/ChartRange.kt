package de.spritbuch.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Calendar
import java.util.TimeZone

/** Zeitraum für die Verlaufs-Diagramme (Verbrauch, Preise); gilt für alle Fahrzeuge und bleibt gespeichert. */
data class ChartRange(val kind: String = LAST30, val from: Long = 0, val to: Long = 0) {
    companion object {
        const val LAST30 = "last30"
        const val MONTH = "month"
        const val THIS_YEAR = "thisYear"
        const val LAST_YEAR = "lastYear"
        const val ALL = "all"
        const val CUSTOM = "custom"

        val state = MutableStateFlow(ChartRange())

        fun load(p: Prefs) {
            state.value = ChartRange(p.chartRange, p.chartFrom, p.chartTo)
        }

        fun save(p: Prefs, r: ChartRange) {
            p.chartRange = r.kind
            p.chartFrom = r.from
            p.chartTo = r.to
            state.value = r
        }

        private fun startOfYear(year: Int) = Calendar.getInstance().apply { clear(); set(year, 0, 1) }.timeInMillis
    }

    /** Zeitspanne [von, bis) – null bei „letzte 30 Betankungen“ und „alles“. */
    fun span(now: Long = now()): Pair<Long, Long>? {
        val year = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.YEAR)
        return when (kind) {
            MONTH -> (now - 30L * 86_400_000L) to Long.MAX_VALUE
            THIS_YEAR -> startOfYear(year) to Long.MAX_VALUE
            LAST_YEAR -> startOfYear(year - 1) to startOfYear(year)
            CUSTOM -> from to (to + 86_400_000L) // „bis“ einschließlich des ganzen Tages
            else -> null
        }
    }

    /** Werte (chronologisch sortiert, mit Zeitstempel) auf den Zeitraum zuschneiden. */
    fun <T> apply(sorted: List<T>, timestamp: (T) -> Long): List<T> {
        if (kind == ALL) return sorted
        val s = span() ?: return sorted.takeLast(30)
        return sorted.filter { timestamp(it) >= s.first && timestamp(it) < s.second }
    }

    fun label(): String = when (kind) {
        MONTH -> str(R.string.range_month)
        THIS_YEAR -> str(R.string.range_this_year)
        LAST_YEAR -> str(R.string.range_last_year)
        ALL -> str(R.string.range_all)
        CUSTOM -> from.date() + " – " + to.date()
        else -> str(R.string.range_last30)
    }
}

/** Ein Kalendertag aus dem Datumsdialog (UTC-Mitternacht) als lokale Mitternacht. */
private fun utcDayToLocal(utc: Long): Long {
    val u = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utc }
    return Calendar.getInstance().apply {
        clear(); set(u.get(Calendar.YEAR), u.get(Calendar.MONTH), u.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis
}

/** Auswahl des Zeitraums als Chips; „Zeitraum …“ öffnet einen Von-bis-Kalender. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChartRangeChips(modifier: Modifier = Modifier) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val range by ChartRange.state.collectAsStateWithLifecycle()
    var pick by remember { mutableStateOf(false) }
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(ChartRange.LAST30, ChartRange.MONTH, ChartRange.THIS_YEAR, ChartRange.LAST_YEAR, ChartRange.ALL).forEach { k ->
            FilterChip(
                selected = range.kind == k,
                onClick = { ChartRange.save(prefs, ChartRange(k)) },
                label = { Text(ChartRange(k).label()) },
            )
        }
        FilterChip(
            selected = range.kind == ChartRange.CUSTOM,
            onClick = { pick = true },
            label = { Text(if (range.kind == ChartRange.CUSTOM) range.label() else str(R.string.range_custom)) },
        )
    }
    if (pick) {
        val state = rememberDateRangePickerState(
            initialSelectedStartDateMillis = range.from.takeIf { range.kind == ChartRange.CUSTOM }?.let { toUtcDate(it) },
            initialSelectedEndDateMillis = range.to.takeIf { range.kind == ChartRange.CUSTOM }?.let { toUtcDate(it) },
        )
        DatePickerDialog(
            onDismissRequest = { pick = false },
            confirmButton = {
                TextButton(
                    enabled = state.selectedStartDateMillis != null,
                    onClick = {
                        val from = utcDayToLocal(state.selectedStartDateMillis!!)
                        val to = utcDayToLocal(state.selectedEndDateMillis ?: state.selectedStartDateMillis!!)
                        ChartRange.save(prefs, ChartRange(ChartRange.CUSTOM, from, to))
                        pick = false
                    },
                ) { Text(str(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { pick = false }) { Text(str(R.string.cancel)) } },
        ) {
            DateRangePicker(state = state, modifier = Modifier.height(500.dp))
        }
    }
}
