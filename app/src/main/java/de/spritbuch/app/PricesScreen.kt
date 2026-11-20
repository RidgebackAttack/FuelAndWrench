package de.spritbuch.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.io.File

/** Aktuelle Spritpreise in der Nähe: Karte oben, Liste (günstigste zuerst) unten. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PricesScreen(nav: NavController, vehicleId: String) {
    val ctx = LocalContext.current
    val vehicle by ctx.repo.vehicle(vehicleId).collectAsStateWithLifecycle(null)
    val fuel = vehicle?.defaultFuelType ?: ""
    val type = FuelPrices.typeFor(fuel)
    var stations by remember { mutableStateOf<List<PriceStation>?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var hasLocation by remember { mutableStateOf(LocationHelper.hasPermission(ctx)) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasLocation = it
        reload++
    }

    LaunchedEffect(type, reload, hasLocation) {
        if (type == null || !FuelPrices.enabled(ctx)) return@LaunchedEffect
        if (!hasLocation) {
            permission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            return@LaunchedEffect
        }
        loading = true
        info = null
        val loc = LocationHelper.current(ctx)
        if (loc == null) {
            info = str(R.string.prices_no_location)
        } else {
            runCatching { FuelPrices.nearby(ctx, loc.latitude, loc.longitude, type) }
                .onSuccess { stations = it; if (it.isEmpty()) info = str(R.string.prices_none) }
                .onFailure { info = str(R.string.error_prefix, it.message ?: str(R.string.prices_error)) }
        }
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(str(R.string.prices_title, lbl(fuel))) },
                navigationIcon = { BackButton(nav) },
                actions = {
                    if (type != null && FuelPrices.enabled(ctx)) IconButton(onClick = { reload++ }) {
                        Icon(Icons.Default.Refresh, contentDescription = str(R.string.prices_refresh))
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            when {
                !FuelPrices.enabled(ctx) -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(str(R.string.prices_need_key))
                    Button(onClick = { nav.navigate("settings/prices") }) { Text(str(R.string.settings)) }
                }
                type == null -> Text(str(R.string.prices_unsupported, lbl(fuel)), Modifier.padding(16.dp))
                else -> {
                    val list = stations.orEmpty().filter { it.price != null }
                    if (list.isNotEmpty()) PriceMap(list, Modifier.fillMaxWidth().height(260.dp))
                    if (loading) Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    info?.let { Text(it, Modifier.padding(16.dp)) }
                    LazyColumn(
                        Modifier.weight(1f),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(stations.orEmpty(), key = { it.id }) { s -> StationRow(s) { navigateTo(ctx, s) } }
                        item {
                            Text(
                                str(R.string.prices_attribution), style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun navigateTo(ctx: android.content.Context, s: PriceStation) {
    val uri = Uri.parse("geo:0,0?q=${s.lat},${s.lng}(${Uri.encode(s.label)})")
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
}

@Composable
private fun StationRow(s: PriceStation, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = if (!s.isOpen) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant) else CardDefaults.cardColors(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s.label, style = MaterialTheme.typography.titleSmall)
                Text(
                    listOf(s.street, s.place).filter { it.isNotBlank() }.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    str(R.string.prices_distance, s.dist.fmt(1)) + if (!s.isOpen) " · " + str(R.string.prices_closed) else "",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                s.price?.let { FuelPrices.price(it) } ?: "–",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun PriceMap(list: List<PriceStation>, modifier: Modifier) {
    val ctx = LocalContext.current
    remember {
        Configuration.getInstance().apply {
            userAgentValue = ctx.packageName
            osmdroidBasePath = File(ctx.cacheDir, "osmdroid")
            osmdroidTileCache = File(ctx.cacheDir, "osmdroid/tiles")
        }
    }
    val mapView = remember {
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            minZoomLevel = 3.0
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDetach()
        }
    }
    AndroidView(
        factory = { mapView },
        modifier = modifier,
        update = { mv ->
            mv.overlays.clear()
            val geo = list.map { GeoPoint(it.lat, it.lng) }
            list.forEachIndexed { i, s ->
                Marker(mv).apply {
                    position = geo[i]
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    title = (if (i == 0) "★ " else "") + FuelPrices.price(s.price ?: 0.0) + " · " + s.label
                    snippet = listOf(s.street, s.place).filter { it.isNotBlank() }.joinToString(", ")
                    mv.overlays.add(this)
                }
            }
            mv.overlays.add(org.osmdroid.views.overlay.CopyrightOverlay(ctx).apply { setTextSize(11) })
            mv.invalidate()
            val zoom = {
                if (geo.size == 1) {
                    mv.controller.setZoom(14.0)
                    mv.controller.setCenter(geo[0])
                } else {
                    mv.zoomToBoundingBox(BoundingBox.fromGeoPointsSafe(geo).increaseByScale(1.2f), false)
                }
            }
            if (mv.width > 0 && mv.height > 0) zoom() else mv.addOnFirstLayoutListener { _, _, _, _, _ -> zoom() }
        },
    )
}
