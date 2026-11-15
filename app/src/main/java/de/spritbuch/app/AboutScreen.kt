package de.spritbuch.app

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController

const val SOURCE_URL = "https://github.com/RidgebackAttack/FuelAndWrench"

/** Verwendete Open-Source-Bibliotheken (alle Apache License 2.0). */
private val libraries = listOf(
    "AndroidX (Core, Activity, Lifecycle, Navigation, Room, WorkManager, ExifInterface, SplashScreen)" to "© The Android Open Source Project",
    "Jetpack Compose & Material 3" to "© The Android Open Source Project",
    "Material Symbols / Icons" to "© Google LLC",
    "Kotlin & kotlinx.coroutines / kotlinx.serialization" to "© JetBrains s.r.o. and Kotlin contributors",
    "OkHttp" to "© Square, Inc.",
    "Coil" to "© Coil Contributors",
    "osmdroid" to "© osmdroid contributors",
)

@Composable
private fun Expandable(title: String, content: @Composable () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) { content() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(nav: NavController) {
    val ctx = LocalContext.current
    val version = remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
    }
    val apacheText = remember {
        ctx.resources.openRawResource(R.raw.apache_2_0).bufferedReader().use { it.readText() }
    }
    val privacyText = remember {
        ctx.resources.openRawResource(R.raw.privacy).bufferedReader().use { it.readText() }
    }
    fun open(url: String) = runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }

    Scaffold(topBar = { TopAppBar(title = { Text("Über Fuel & Wrench") }, navigationIcon = { BackButton(nav) }) }) { pad ->
        Column(
            Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Fuel & Wrench", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Version $version", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Tanken, Service und Reifen im Blick – ohne Werbung, ohne Tracking, ohne Google-Dienste. " +
                    "Synchronisation über deinen eigenen Nextcloud-/WebDAV-Server.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Lizenz: GNU General Public License v3.0 oder neuer. Diese App wurde mit KI-Unterstützung (Claude) entwickelt.",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = { open(SOURCE_URL) }) { Text("Quellcode auf GitHub") }

            Expandable("Datenschutzerklärung") {
                Text(privacyText, style = MaterialTheme.typography.bodySmall)
            }
            Expandable("Kartendaten") {
                Text(
                    "Karten und Tankstellendaten: © OpenStreetMap-Mitwirkende, verfügbar unter der Open Database License (ODbL). " +
                        "Kartenkacheln: OpenStreetMap Foundation. Tankstellensuche: Overpass API.",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { open("https://www.openstreetmap.org/copyright") }) { Text("openstreetmap.org/copyright") }
            }
            Expandable("Open-Source-Lizenzen") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Diese App verwendet folgende Bibliotheken, jeweils unter der Apache License, Version 2.0:",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    libraries.forEach { (name, holder) ->
                        Column {
                            Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text(holder, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    HorizontalDivider()
                    Text(apacheText, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 13.sp)
                }
            }
        }
    }
}
