package de.spritbuch.app

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction

/**
 * Freitextsuche für Listen: jedes Wort der Eingabe muss irgendwo in den Feldern vorkommen (Groß-/Kleinschreibung
 * egal). Zahlen werden zusätzlich ohne Tausenderpunkte verglichen, damit „153931“ auch „153.931 km“ findet.
 */
object Search {
    fun hit(query: String, vararg fields: String?): Boolean {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        val hay = fields.filterNotNull().joinToString("\n").lowercase()
        val plain = hay.replace(Regex("(?<=\\d)[.\u00A0\u202F ](?=\\d{3})"), "")
        return words.all { w -> hay.contains(w) || plain.contains(w) }
    }

    fun service(s: ServiceEntry, q: String, currency: String): Boolean = hit(
        q, s.title, s.note, s.workshop, lbl(s.category), s.category,
        s.tireBrand, s.tireModel, s.tireSize, s.huResult.takeIf { it.isNotEmpty() }?.let { lbl(it) },
        s.timestamp.date(), s.odometer.takeIf { it > 0 }?.toString(), s.cost.takeIf { it > 0 }?.let { it.fmt() + " " + currency },
    )

    fun expense(e: Expense, q: String, currency: String): Boolean = hit(
        q, e.title, e.note, lbl(e.category), e.category, e.timestamp.date(), e.amount.takeIf { it > 0 }?.let { it.fmt() + " " + currency },
    )
}

/**
 * Suchfeld für die Kopfzeile: erscheint nach Tippen auf die Lupe anstelle des Titels und holt sich sofort den
 * Fokus, damit die Tastatur aufgeht. „Suchen“ auf der Tastatur schließt nur die Tastatur, der Begriff bleibt.
 */
@Composable
fun TopBarSearchField(query: String, onQuery: (String) -> Unit) {
    val focus = LocalFocusManager.current
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { requester.requestFocus() }
    TextField(
        value = query,
        onValueChange = onQuery,
        modifier = Modifier.fillMaxWidth().focusRequester(requester),
        singleLine = true,
        placeholder = { Text(str(R.string.search_hint)) },
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
    )
}
