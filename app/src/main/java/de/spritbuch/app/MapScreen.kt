package de.spritbuch.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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

/** Karte aller Tankvorgänge eines Fahrzeugs (OpenStreetMap, ohne Google-Dienste). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(nav: NavController, vehicleId: String) {
    val ctx = LocalContext.current
    val repo = ctx.repo
    val vehicle by repo.vehicle(vehicleId).collectAsStateWithLifecycle(null)
    val refuels by repo.refuels(vehicleId).collectAsStateWithLifecycle<List<Refuel>?>(null)

    remember {
        Configuration.getInstance().apply {
            userAgentValue = ctx.packageName
            osmdroidBasePath = File(ctx.cacheDir, "osmdroid")
            osmdroidTileCache = File(ctx.cacheDir, "osmdroid/tiles")
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Karte – ${vehicle?.name ?: ""}") }, navigationIcon = { BackButton(nav) }) },
    ) { pad ->
        val list = refuels
        if (list == null) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        val points = list.filter { it.latitude != null && it.longitude != null }
        if (points.isEmpty()) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Keine Tankvorgänge mit Position vorhanden.")
            }
            return@Scaffold
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

        val v = vehicle
        val geo = points.map { GeoPoint(it.latitude!!, it.longitude!!) }
        val zoomed = remember { booleanArrayOf(false) }

        AndroidView(
            factory = { mapView },
            modifier = Modifier.padding(pad).fillMaxSize(),
            update = { mv ->
                mv.overlays.clear()
                points.forEachIndexed { i, r ->
                    val unit = Units.amount(v, r.fuelType)
                    val cur = Units.cur(v)
                    Marker(mv).apply {
                        position = geo[i]
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        title = listOf(r.stationBrand.ifBlank { "Tankstelle" }, r.timestamp.date()).joinToString(" · ")
                        snippet = "${r.amount.fmt(2)} $unit · ${r.totalPrice.fmt(2)} $cur (${r.pricePerUnit.fmt(3)} $cur/$unit)"
                        subDescription = listOf(r.stationAddress, r.odometer.dist(Units.dist(v)))
                            .filter { it.isNotBlank() }.joinToString("<br>")
                        mv.overlays.add(this)
                    }
                }
                mv.invalidate()
                if (!zoomed[0]) {
                    zoomed[0] = true
                    val doZoom = {
                        if (geo.size == 1) {
                            mv.controller.setZoom(15.0)
                            mv.controller.setCenter(geo[0])
                        } else {
                            mv.zoomToBoundingBox(BoundingBox.fromGeoPointsSafe(geo).increaseByScale(1.15f), false)
                        }
                    }
                    if (mv.width > 0 && mv.height > 0) doZoom() else mv.addOnFirstLayoutListener { _, _, _, _, _ -> doZoom() }
                }
            },
        )
    }
}
