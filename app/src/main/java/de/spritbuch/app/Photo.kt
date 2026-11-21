package de.spritbuch.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import java.io.File

/** Bildausschnitt: Verschiebung x/y als Anteil des möglichen Spielraums (-1..1) und Zoom (1..5). */
data class PhotoPos(val x: Float = 0f, val y: Float = 0f, val zoom: Float = 1f) {
    fun encode() = "$x,$y,$zoom"

    companion object {
        fun parse(s: String?): PhotoPos {
            val p = s?.split(",")?.mapNotNull { it.trim().toFloatOrNull() }
            return if (p != null && p.size == 3) {
                PhotoPos(p[0].coerceIn(-1f, 1f), p[1].coerceIn(-1f, 1f), p[2].coerceIn(1f, 5f))
            } else PhotoPos()
        }
    }
}

const val LARGE_PHOTO_ASPECT = 2f

/**
 * Zeigt ein Foto im gewählten Ausschnitt. Mit [onChange] lässt es sich per Finger
 * verschieben (ein Finger) und zoomen (zwei Finger).
 */
@Composable
fun PositionedPhoto(file: File, pos: PhotoPos, modifier: Modifier, onChange: ((PhotoPos) -> Unit)? = null) {
    val painter = rememberAsyncImagePainter(file)
    BoxWithConstraints(modifier.clipToBounds()) {
        val bw = constraints.maxWidth.toFloat()
        val bh = constraints.maxHeight.toFloat()
        // Bildgröße direkt aus der Datei lesen (zuverlässiger als über den Bildlader)
        val dims = remember(file) {
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(file.path, o)
            o.outWidth.toFloat() to o.outHeight.toFloat()
        }
        val (iw, ih) = dims
        fun limits(zoom: Float): Pair<Float, Float> {
            if (iw <= 0f || ih <= 0f || bw <= 0f || bh <= 0f) return 0f to 0f
            val s = maxOf(bw / iw, bh / ih)
            val cw = iw * s * zoom
            val ch = ih * s * zoom
            return ((cw - bw) / 2f).coerceAtLeast(0f) to ((ch - bh) / 2f).coerceAtLeast(0f)
        }
        val (maxTx, maxTy) = limits(pos.zoom)

        val latest = remember { object { var p = pos } }
        SideEffect { latest.p = pos }
        val limitsFn by rememberUpdatedState(::limits)
        val callback by rememberUpdatedState(onChange)

        val gestures = if (onChange != null) {
            Modifier.pointerInput(Unit) {
                detectTransformGestures { _, pan, zoomChange, _ ->
                    val p = latest.p
                    val z = (p.zoom * zoomChange).coerceIn(1f, 5f)
                    val (mx, my) = limitsFn(z)
                    val nx = if (mx > 0f) (p.x * limitsFn(p.zoom).first / mx + pan.x / mx).coerceIn(-1f, 1f) else 0f
                    val ny = if (my > 0f) (p.y * limitsFn(p.zoom).second / my + pan.y / my).coerceIn(-1f, 1f) else 0f
                    val np = PhotoPos(nx, ny, z)
                    latest.p = np
                    callback?.invoke(np)
                }
            }
        } else Modifier

        Box(Modifier.fillMaxSize().then(gestures)) {
            Image(
                painter = painter,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = pos.zoom
                    scaleY = pos.zoom
                    translationX = pos.x * maxTx
                    translationY = pos.y * maxTy
                },
            )
        }
    }
}

/**
 * Welches Bild eines Fahrzeugs gerade gezeigt wird. Bei jedem Öffnen der App ([next]) kommt ein anderes –
 * je nach Einstellung des Fahrzeugs zufällig oder der Reihe nach. Innerhalb einer Sitzung bleibt es gleich,
 * damit Liste, Übersicht und Auswahl dasselbe Bild zeigen.
 */
object PhotoRotation {
    private val generation = kotlinx.coroutines.flow.MutableStateFlow(0)
    private val chosen = mutableMapOf<String, Int>()

    /** Neues Öffnen der App: beim nächsten Anzeigen neu wählen. */
    fun next() {
        synchronized(chosen) { chosen.clear() }
        generation.value++
    }

    fun pick(ctx: android.content.Context, v: Vehicle): VehicleImage? {
        val imgs = v.images()
        if (imgs.size <= 1) return imgs.firstOrNull()
        val idx = synchronized(chosen) {
            chosen.getOrPut(v.id) {
                val sp = ctx.getSharedPreferences("photo_rotation", android.content.Context.MODE_PRIVATE)
                val last = sp.getInt(v.id, -1)
                val n = if (v.photoOrder == "sequence") (last + 1) % imgs.size
                else (imgs.indices - last).random()
                sp.edit().putInt(v.id, n).apply()
                n
            }
        }
        return imgs[idx.coerceIn(0, imgs.lastIndex)]
    }

    @Composable
    fun current(v: Vehicle): VehicleImage? {
        val ctx = LocalContext.current
        val gen by generation.collectAsState()
        return androidx.compose.runtime.remember(v.id, v.photoVersion, v.extraPhotos, v.photoOrder, gen) { pick(ctx, v) }
    }
}

@Composable
fun VehiclePhoto(v: Vehicle, modifier: Modifier, large: Boolean = false) {
    val ctx = LocalContext.current
    val img = PhotoRotation.current(v)
    val file = img?.let { PhotoStore.file(ctx, v.id, it.version) }
    val shaped = modifier.clip(RoundedCornerShape(12.dp))
    if (file != null && file.exists()) {
        PositionedPhoto(file, PhotoPos.parse(if (large) img.largePos else img.thumbPos), shaped)
    } else {
        Box(shaped.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.DirectionsCar, contentDescription = null, modifier = Modifier.size(40.dp))
        }
    }
}

/** Editor für beide Ausschnitte nebeneinander bzw. untereinander. */
@Composable
fun PhotoPositionEditor(
    file: File,
    thumb: PhotoPos,
    large: PhotoPos,
    onThumb: (PhotoPos) -> Unit,
    onLarge: (PhotoPos) -> Unit,
) {
    Column {
        Text(
            str(R.string.photo_crop_info),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(str(R.string.photo_large), style = MaterialTheme.typography.labelLarge)
        PositionedPhoto(
            file, large,
            Modifier.fillMaxWidth().aspectRatio(LARGE_PHOTO_ASPECT).clip(RoundedCornerShape(12.dp)),
            onChange = onLarge,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(140.dp)) {
                Text(str(R.string.photo_thumb), style = MaterialTheme.typography.labelLarge)
                PositionedPhoto(
                    file, thumb,
                    Modifier.size(120.dp).clip(RoundedCornerShape(12.dp)),
                    onChange = onThumb,
                )
            }
            TextButton(onClick = { onThumb(PhotoPos()); onLarge(PhotoPos()) }) { Text(str(R.string.reset)) }
        }
    }
}
