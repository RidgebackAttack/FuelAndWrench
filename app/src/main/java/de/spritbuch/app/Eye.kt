package de.spritbuch.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController

/** Zahl, die beim Erscheinen von 0 hochzählt und bei Änderungen weich nachzieht. */
@Composable
fun countUp(target: Double): Double {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(target) { anim.animateTo(target.toFloat(), tween(900, easing = FastOutSlowInEasing)) }
    return anim.value.toDouble()
}

private val ScrimBrush = Brush.verticalGradient(
    0f to Color.Transparent, 0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.75f),
)

/** Großes Fahrzeugbild mit Farbverlauf; ohne Foto ein farbiger Verlauf mit Symbol. */
@Composable
fun VehicleHero(v: Vehicle, modifier: Modifier = Modifier, overlay: @Composable () -> Unit = {}) {
    Box(modifier.fillMaxWidth().aspectRatio(LARGE_PHOTO_ASPECT).clip(RoundedCornerShape(16.dp))) {
        if (v.photoVersion > 0) {
            VehiclePhoto(v, Modifier.fillMaxSize(), large = true)
        } else {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.linearGradient(
                        listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer),
                    )
                ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (v.vehicleType == "Motorrad") Icons.Default.TwoWheeler else Icons.Default.DirectionsCar,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.5f),
                    modifier = Modifier.size(72.dp),
                )
            }
        }
        Box(Modifier.fillMaxSize().background(ScrimBrush))
        Box(Modifier.align(Alignment.BottomStart).padding(14.dp)) { overlay() }
    }
}

@Composable
fun StatChip(icon: ImageVector, text: String, warn: Boolean = false) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (warn) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (warn) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

/** Fahrzeugkarte für die Startliste. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VehicleHeroCard(nav: NavController, v: Vehicle, refuels: List<Refuel>, reminders: List<Reminder>) {
    val mine = remember(refuels, v.id) { refuels.filter { it.vehicleId == v.id } }
    val stats = remember(mine, v.batteryCapacity) { computeStats(mine, v.batteryCapacity) }
    val odo = maxOf(v.initialOdometer, v.manualOdometer, mine.maxOfOrNull { it.odometer } ?: 0)
    val next = reminders.filter { it.vehicleId == v.id }.minByOrNull { it.state(odo).urgency }
    Card(
        onClick = { nav.navigate("vehicle/${v.id}") },
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = if (v.archived) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        else CardDefaults.elevatedCardColors(),
    ) {
        VehicleHero(v) {
            Column {
                Text(
                    v.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                    color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                val sub = listOf(v.plate, if (v.archived) str(R.string.archived_lower) else "").filter { it.isNotBlank() }.joinToString(" · ")
                if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f))
            }
        }
        FlowRow(
            Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatChip(Icons.Default.LocalGasStation, stats.avg?.let { "Ø " + Units.consumption(v, v.defaultFuelType, it) } ?: "Ø –")
            if (odo > 0) StatChip(Icons.Default.Speed, odo.dist(v.distanceUnit))
            if (next != null && !v.archived) {
                val st = next.state(odo)
                val warn = st.overdue || st.warn
                // Was zuerst eintritt: km oder Tage
                val kmFirst = st.remainingDays == null || (st.remainingKm != null && st.remainingKm * 1.0 <= st.remainingDays * 50.0)
                StatChip(
                    if (warn) Icons.Default.WarningAmber else Icons.Default.Build,
                    when {
                        st.overdue -> str(R.string.chip_due, next.title)
                        kmFirst && st.remainingKm != null -> str(R.string.chip_due_in, next.title, st.remainingKm.dist(v.distanceUnit))
                        else -> str(R.string.chip_due_in, next.title, plural(R.plurals.pl_days_in, st.remainingDays ?: 0))
                    },
                    warn = warn,
                )
            }
        }
    }
}

/** Kennzahl-Kachel mit Symbol. */
@Composable
fun KpiTile(icon: ImageVector, label: String, value: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Card(
        modifier = modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
            }
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 2×2-Kacheln mit animierten Werten und aufklappbarer Gesamtstatistik. */
@Composable
fun KpiSection(
    v: Vehicle,
    stats: Stats,
    serviceCost: Double,
    currentOdo: Int,
    onEditOdometer: () -> Unit,
    details: @Composable () -> Unit,
) {
    val fuel = v.defaultFuelType
    val cur = v.currency
    val avg = countUp(stats.avg ?: 0.0)
    val per100 = countUp(stats.costPer100 ?: 0.0)
    val odo = countUp(currentOdo.toDouble())
    val total = countUp(stats.totalCost + serviceCost)
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KpiTile(Icons.Default.LocalGasStation, str(R.string.stat_avg),
                if (stats.avg != null) Units.consumption(v, fuel, avg) else "–", Modifier.weight(1f))
            KpiTile(Icons.Default.Payments, str(R.string.stat_cost_per100, v.distanceUnit),
                if (stats.costPer100 != null) "${per100.fmt()} $cur" else "–", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KpiTile(Icons.Default.Speed, str(R.string.kpi_odometer_tap), odo.toInt().dist(v.distanceUnit), Modifier.weight(1f), onEditOdometer)
            KpiTile(Icons.Default.Payments, str(R.string.kpi_total_cost), "${total.fmt(0)} $cur", Modifier.weight(1f))
        }
        Card(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(str(R.string.all_figures), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
            }
            AnimatedVisibility(expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) { details() }
            }
        }
    }
}

/** Verbrauchs-Plakette: grün unter, rot über dem eigenen Durchschnitt. */
@Composable
fun ConsumptionBadge(text: String, value: Double, avg: Double?) {
    val good = Color(0xFF2E7D32)
    val bad = Color(0xFFC62828)
    val color = when {
        avg == null || avg <= 0 -> MaterialTheme.colorScheme.primary
        value <= avg * 0.97 -> good
        value >= avg * 1.03 -> bad
        else -> MaterialTheme.colorScheme.primary
    }
    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.15f), contentColor = color) {
        Text(
            text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** Leerer Zustand mit großem Symbol. */
@Composable
fun EmptyState(icon: ImageVector, title: String, text: String) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.size(84.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(44.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}
