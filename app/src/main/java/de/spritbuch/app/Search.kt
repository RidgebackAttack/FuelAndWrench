package de.spritbuch.app

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.shape.RoundedCornerShape

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

/** Suchzeile über einer Liste; das Kreuz leert sie, „Fertig“ auf der Tastatur schließt die Tastatur. */
@Composable
fun SearchField(query: String, onQuery: (String) -> Unit, modifier: Modifier = Modifier) {
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(50),
        placeholder = { Text(str(R.string.search_hint)) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) IconButton(onClick = { onQuery(""); focus.clearFocus() }) {
                Icon(Icons.Default.Close, contentDescription = str(R.string.search_clear))
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
    )
}
