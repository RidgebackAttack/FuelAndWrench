package de.spritbuch.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import coil.compose.AsyncImage

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        ThemeSettings.load(this)
        enableEdgeToEdge()
        setContent { AppTheme { AppNav() } }
    }

    override fun onResume() {
        super.onResume()
        SyncScheduler.syncSoon(this, delaySeconds = 0)
    }
}

@Composable
fun AppNav() {
    val nav = rememberNavController()
    val ctx = LocalContext.current
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    NavHost(
        navController = nav,
        startDestination = "vehicles",
        enterTransition = {
            androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(280)) { it / 5 } +
                androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(280))
        },
        exitTransition = { androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(200)) },
        popEnterTransition = { androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(280)) },
        popExitTransition = {
            androidx.compose.animation.slideOutHorizontally(androidx.compose.animation.core.tween(280)) { it / 5 } +
                androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(220))
        },
    ) {
        composable("vehicles") { VehicleListScreen(nav) }
        composable("vehicle/{id}") { VehicleDetailScreen(nav, it.arguments?.getString("id") ?: "") }
        composable("vehicleEdit/{id}") { VehicleEditScreen(nav, it.arguments?.getString("id") ?: "new") }
        composable("refuelEdit/{vehicleId}/{id}") {
            RefuelEditScreen(nav, it.arguments?.getString("vehicleId") ?: "", it.arguments?.getString("id") ?: "new")
        }
        composable("reminderEdit/{vehicleId}/{id}") {
            ReminderEditScreen(nav, it.arguments?.getString("vehicleId") ?: "", it.arguments?.getString("id") ?: "new")
        }
        composable("settings") { SettingsScreen(nav) }
        composable("about") { AboutScreen(nav) }
        composable("map/{vehicleId}") { MapScreen(nav, it.arguments?.getString("vehicleId") ?: "") }
        composable("services/{vehicleId}") { ServiceTimelineScreen(nav, it.arguments?.getString("vehicleId") ?: "") }
        composable("tireSetEdit/{vehicleId}/{id}") {
            TireSetEditScreen(nav, it.arguments?.getString("vehicleId") ?: "", it.arguments?.getString("id") ?: "new")
        }
        composable("serviceEdit/{vehicleId}/{id}") {
            ServiceEditScreen(nav, it.arguments?.getString("vehicleId") ?: "", it.arguments?.getString("id") ?: "new")
        }
    }
}

@Composable
fun BackButton(nav: NavController) {
    IconButton(onClick = { nav.popBackStack() }) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
fun ChoiceField(label: String, value: String, options: List<String>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
            modifier = Modifier.fillMaxWidth(),
        )
        Box(Modifier.matchParentSize().clickable { expanded = true })
        // Beim Tippen nur passende Einträge zeigen; bei leerem Feld oder exaktem Treffer alle
        val shown = options.filter { value.isBlank() || it.contains(value.trim(), ignoreCase = true) }
            .takeIf { it.isNotEmpty() && options.none { o -> o.equals(value.trim(), ignoreCase = true) } } ?: options
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 360.dp)) {
            shown.forEach { o ->
                DropdownMenuItem(text = { Text(o) }, onClick = { onSelect(o); expanded = false })
            }
        }
    }
}

/** Freitextfeld mit Vorschlagsliste. */
@Composable
fun SuggestField(label: String, value: String, options: List<String>, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            singleLine = true,
            trailingIcon = {
                IconButton(onClick = { expanded = true }) {
                    Icon(Icons.Default.ArrowDropDown, contentDescription = "Auswahl")
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        // Beim Tippen nur passende Einträge zeigen; bei leerem Feld oder exaktem Treffer alle
        val shown = options.filter { value.isBlank() || it.contains(value.trim(), ignoreCase = true) }
            .takeIf { it.isNotEmpty() && options.none { o -> o.equals(value.trim(), ignoreCase = true) } } ?: options
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 360.dp)) {
            shown.forEach { o ->
                DropdownMenuItem(text = { Text(o) }, onClick = { onChange(o); expanded = false })
            }
        }
    }
}

@Composable
fun NumberField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    suffix: String? = null,
    decimal: Boolean = true,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    val suffixContent: (@Composable () -> Unit)? = if (suffix == null) null else { { Text(suffix) } }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        suffix = suffixContent,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        modifier = modifier,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SingleChips(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o ->
            FilterChip(
                selected = o == selected,
                onClick = { onSelect(if (o == selected) "" else o) },
                label = { Text(o) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MultiChips(options: List<String>, selected: Set<String>, onChange: (Set<String>) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o ->
            FilterChip(
                selected = o in selected,
                onClick = { onChange(if (o in selected) selected - o else selected + o) },
                label = { Text(o) },
            )
        }
    }
}

@Composable
fun LabeledSwitch(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
