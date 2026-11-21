package de.spritbuch.app

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

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
 * Fotos einmal passend verkleinert laden und im Speicher halten. Die Maße kommen aus dem geladenen
 * Bild selbst – so stimmen Ausschnitt-Berechnung und Anzeige immer überein (auch bei gedrehten Fotos).
 */
object PhotoCache {
    private val cache = object : LruCache<String, ImageBitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    /** Zielgröße in festen Stufen, damit dieselbe Datei nicht in vielen Größen geladen wird. */
    fun bucket(px: Float) = listOf(256, 512, 1024, 1600).firstOrNull { it >= px } ?: 1600

    private fun key(file: File, side: Int) = "${file.path}@$side@${file.lastModified()}"

    fun peek(file: File, side: Int): ImageBitmap? = cache.get(key(file, side))

    suspend fun load(file: File, side: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        val k = key(file, side)
        cache.get(k)?.let { return@withContext it }
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, b)
        if (b.outWidth <= 0 || b.outHeight <= 0) return@withContext null
        var sample = 1
        while (maxOf(b.outWidth, b.outHeight) / (sample * 2) >= side) sample *= 2
        val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return@withContext null
        bmp.asImageBitmap().also { cache.put(k, it) }
    }
}

/** Wo das Bild bei diesem Ausschnitt in einem Rahmen der Größe [bw]×[bh] liegt. */
fun photoRect(iw: Float, ih: Float, bw: Float, bh: Float, pos: PhotoPos): Rect {
    if (iw <= 0f || ih <= 0f || bw <= 0f || bh <= 0f) return Rect(0f, 0f, bw, bh)
    val s = maxOf(bw / iw, bh / ih) * pos.zoom
    val cw = iw * s
    val ch = ih * s
    val maxTx = ((cw - bw) / 2f).coerceAtLeast(0f)
    val maxTy = ((ch - bh) / 2f).coerceAtLeast(0f)
    val left = (bw - cw) / 2f + pos.x * maxTx
    val top = (bh - ch) / 2f + pos.y * maxTy
    return Rect(left, top, left + cw, top + ch)
}

/** Zeigt ein Foto im festgelegten Ausschnitt (ohne Gesten; ausgerichtet wird im [PhotoCropDialog]). */
@Composable
fun PositionedPhoto(file: File, pos: PhotoPos, modifier: Modifier) {
    BoxWithConstraints(modifier.clipToBounds()) {
        val side = PhotoCache.bucket(maxOf(constraints.maxWidth, constraints.maxHeight) * 1.5f * pos.zoom)
        val bmp by produceState(PhotoCache.peek(file, side), file, side, file.lastModified()) { value = PhotoCache.load(file, side) }
        val b = bmp ?: return@BoxWithConstraints
        Canvas(Modifier.fillMaxSize()) {
            val r = photoRect(b.width.toFloat(), b.height.toFloat(), size.width, size.height, pos)
            drawImage(
                b,
                dstOffset = IntOffset(r.left.roundToInt(), r.top.roundToInt()),
                dstSize = IntSize(r.width.roundToInt(), r.height.roundToInt()),
                filterQuality = FilterQuality.Medium,
            )
        }
    }
}

/**
 * Bildschirmfüllendes Ausrichten: Das Bild lässt sich unter einem Rahmen (exaktes Seitenverhältnis der
 * Ansicht) verschieben und zoomen; außerhalb des Rahmens ist es abgedunkelt.
 */
@Composable
fun PhotoCropDialog(file: File, aspect: Float, initial: PhotoPos, title: String, onDone: (PhotoPos) -> Unit, onDismiss: () -> Unit) {
    var pos by remember { mutableStateOf(initial) }
    val bmp by produceState<ImageBitmap?>(null, file) { value = PhotoCache.load(file, 1600) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(start = 8.dp))
                TextButton(onClick = { pos = PhotoPos() }) { Text(str(R.string.reset)) }
            }
            Text(
                str(R.string.photo_crop_info), color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp),
            )
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val w = constraints.maxWidth.toFloat()
                val h = constraints.maxHeight.toFloat()
                val fw = minOf(w * 0.92f, h * 0.85f * aspect)
                val fh = fw / aspect
                val frame = Rect(Offset((w - fw) / 2f, (h - fh) / 2f), Size(fw, fh))
                val b = bmp
                val iw = b?.width?.toFloat() ?: 0f
                val ih = b?.height?.toFloat() ?: 0f
                fun limits(z: Float): Pair<Float, Float> {
                    if (iw <= 0f || ih <= 0f) return 0f to 0f
                    val s = maxOf(fw / iw, fh / ih) * z
                    return ((iw * s - fw) / 2f).coerceAtLeast(0f) to ((ih * s - fh) / 2f).coerceAtLeast(0f)
                }
                Canvas(
                    Modifier.fillMaxSize().pointerInput(iw, ih, fw, fh) {
                        detectTransformGestures { _, pan, zoomChange, _ ->
                            val p = pos
                            val z = (p.zoom * zoomChange).coerceIn(1f, 5f)
                            val (oldMx, oldMy) = limits(p.zoom)
                            val (mx, my) = limits(z)
                            val nx = if (mx > 0f) ((p.x * oldMx + pan.x) / mx).coerceIn(-1f, 1f) else 0f
                            val ny = if (my > 0f) ((p.y * oldMy + pan.y) / my).coerceIn(-1f, 1f) else 0f
                            pos = PhotoPos(nx, ny, z)
                        }
                    },
                ) {
                    if (b != null) {
                        val r = photoRect(iw, ih, fw, fh, pos).translate(frame.topLeft)
                        drawImage(
                            b, dstOffset = IntOffset(r.left.roundToInt(), r.top.roundToInt()),
                            dstSize = IntSize(r.width.roundToInt(), r.height.roundToInt()), filterQuality = FilterQuality.Medium,
                        )
                    }
                    // Alles außerhalb des Rahmens abdunkeln, Rahmen hervorheben
                    val dim = Color.Black.copy(alpha = 0.65f)
                    drawRect(dim, Offset.Zero, Size(size.width, frame.top))
                    drawRect(dim, Offset(0f, frame.bottom), Size(size.width, size.height - frame.bottom))
                    drawRect(dim, Offset(0f, frame.top), Size(frame.left, frame.height))
                    drawRect(dim, Offset(frame.right, frame.top), Size(size.width - frame.right, frame.height))
                    drawRect(Color.White, frame.topLeft, frame.size, style = Stroke(2.dp.toPx()))
                }
            }
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                TextButton(onClick = onDismiss) { Text(str(R.string.cancel)) }
                Button(onClick = { onDone(pos) }) { Text(str(R.string.ok)) }
            }
        }
    }
}

/** Wie und wann bei mehreren Bildern gewechselt wird (gespeichert in [Vehicle.photoOrder]). */
data class PhotoShow(val order: String = "random", val mode: String = OPEN, val seconds: Int = 60) {
    fun encode() = "$order;$mode;$seconds"

    companion object {
        const val OPEN = "open"
        const val RETURN = "return"
        const val TIMED = "timed"
        const val DAILY = "daily"
        val modes = listOf(OPEN, RETURN, TIMED, DAILY)
        val intervals = listOf(10, 30, 60, 300)

        /** Ältere Versionen speicherten nur „random“ bzw. „sequence“ – das bedeutet „bei jedem Öffnen“. */
        fun parse(s: String?): PhotoShow {
            val p = s.orEmpty().split(';')
            return PhotoShow(
                if (p.getOrNull(0) == "sequence") "sequence" else "random",
                p.getOrNull(1)?.takeIf { it in modes } ?: OPEN,
                p.getOrNull(2)?.toIntOrNull()?.coerceIn(5, 3600) ?: 60,
            )
        }
    }
}

/**
 * Welches Bild eines Fahrzeugs gerade gezeigt wird – je nach Einstellung beim Öffnen der App, bei jeder
 * Rückkehr zur Fahrzeugliste, im Takt (Diashow) oder einmal am Tag; zufällig oder der Reihe nach.
 * Innerhalb eines Takts zeigen alle Ansichten dasselbe Bild.
 */
object PhotoRotation {
    private val generation = kotlinx.coroutines.flow.MutableStateFlow(0)
    private val chosen = mutableMapOf<String, Int>()

    /** Neues Öffnen der App. */
    fun next() {
        synchronized(chosen) { chosen.clear() }
        generation.value++
    }

    /** Fahrzeugliste wird (wieder) gezeigt: Fahrzeuge mit „bei Rückkehr“ bekommen ein neues Bild. */
    fun listShown(vehicles: List<Vehicle>) {
        val ids = vehicles.filter { PhotoShow.parse(it.photoOrder).mode == PhotoShow.RETURN }.map { it.id }.toSet()
        if (ids.isEmpty()) return
        synchronized(chosen) { chosen.keys.removeAll(ids) }
    }

    private fun advance(last: Int, n: Int, order: String) =
        if (order == "sequence") (last + 1).mod(n) else ((0 until n).toList() - last).random()

    fun pick(ctx: android.content.Context, v: Vehicle, slot: Long): VehicleImage? {
        val imgs = v.images()
        if (imgs.size <= 1) return imgs.firstOrNull()
        val n = imgs.size
        val show = PhotoShow.parse(v.photoOrder)
        val sp = ctx.getSharedPreferences("photo_rotation", android.content.Context.MODE_PRIVATE)
        val idx = when (show.mode) {
            // Im Takt: aus der Uhrzeit berechnet, damit alle Ansichten gleichzeitig wechseln
            PhotoShow.TIMED -> if (show.order == "sequence") slot.mod(n) else {
                val cycle = slot / n
                (0 until n).shuffled(kotlin.random.Random(v.id.hashCode() + cycle)).elementAt(slot.mod(n))
            }
            PhotoShow.DAILY -> {
                val day = (now() + java.util.TimeZone.getDefault().getOffset(now())) / 86_400_000L
                if (sp.getLong("day_" + v.id, -1) == day) sp.getInt(v.id, 0)
                else advance(sp.getInt(v.id, -1), n, show.order).also { sp.edit().putInt(v.id, it).putLong("day_" + v.id, day).apply() }
            }
            else -> synchronized(chosen) {
                chosen.getOrPut(v.id) { advance(sp.getInt(v.id, -1), n, show.order).also { sp.edit().putInt(v.id, it).apply() } }
            }
        }
        return imgs[idx.coerceIn(0, n - 1)]
    }

    @Composable
    fun current(v: Vehicle): VehicleImage? {
        val ctx = LocalContext.current
        val show = PhotoShow.parse(v.photoOrder)
        var slot by remember { mutableLongStateOf(0L) }
        if (show.mode == PhotoShow.TIMED && v.images().size > 1) {
            val lifecycle = LocalLifecycleOwner.current.lifecycle
            // Takt nur, solange die Ansicht sichtbar ist (kein Akkuverbrauch im Hintergrund)
            LaunchedEffect(v.id, show.seconds) {
                lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    val ms = show.seconds * 1000L
                    while (true) {
                        slot = now() / ms
                        delay(ms - now() % ms)
                    }
                }
            }
        }
        val gen by generation.collectAsState()
        return remember(v.id, v.photoVersion, v.extraPhotos, v.photoOrder, gen, slot) { pick(ctx, v, slot) }
    }
}

@Composable
fun VehiclePhoto(v: Vehicle, modifier: Modifier, large: Boolean = false) {
    val ctx = LocalContext.current
    val img = PhotoRotation.current(v)
    val shaped = modifier.clip(RoundedCornerShape(12.dp))
    // Sichtbare Wechsel werden überblendet
    Crossfade(targetState = img, animationSpec = tween(700), modifier = shaped, label = "photo") { im ->
        val file = im?.let { PhotoStore.file(ctx, v.id, it.version) }
        if (file != null && file.exists()) {
            PositionedPhoto(file, PhotoPos.parse(if (large) im.largePos else im.thumbPos), Modifier.fillMaxSize())
        } else {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.DirectionsCar, contentDescription = null, modifier = Modifier.size(40.dp))
            }
        }
    }
}

/** Hinweis direkt auf dem Bild, dass man zum Ausrichten tippen kann. */
@Composable
private fun TapToCropBadge(modifier: Modifier, compact: Boolean = false) {
    Row(
        modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(Icons.Default.Crop, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        if (!compact) Text(str(R.string.photo_tap_badge), color = Color.White, style = MaterialTheme.typography.labelMedium)
    }
}

/** Vorschau beider Ausschnitte; Antippen öffnet das große Ausrichten. */
@Composable
fun PhotoPositionEditor(
    file: File,
    thumb: PhotoPos,
    large: PhotoPos,
    onThumb: (PhotoPos) -> Unit,
    onLarge: (PhotoPos) -> Unit,
) {
    var editing by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(str(R.string.photo_tap_to_crop), style = MaterialTheme.typography.bodySmall)
        Text(str(R.string.photo_large), style = MaterialTheme.typography.labelLarge)
        Box(Modifier.fillMaxWidth().aspectRatio(LARGE_PHOTO_ASPECT).clip(RoundedCornerShape(12.dp)).clickable { editing = "large" }) {
            PositionedPhoto(file, large, Modifier.fillMaxSize())
            TapToCropBadge(Modifier.align(Alignment.BottomEnd).padding(8.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(140.dp)) {
                Text(str(R.string.photo_thumb), style = MaterialTheme.typography.labelLarge)
                Box(Modifier.size(120.dp).clip(RoundedCornerShape(12.dp)).clickable { editing = "thumb" }) {
                    PositionedPhoto(file, thumb, Modifier.fillMaxSize())
                    TapToCropBadge(Modifier.align(Alignment.BottomEnd).padding(6.dp), compact = true)
                }
            }
            TextButton(onClick = { onThumb(PhotoPos()); onLarge(PhotoPos()) }) { Text(str(R.string.reset)) }
        }
    }
    when (editing) {
        "large" -> PhotoCropDialog(file, LARGE_PHOTO_ASPECT, large, str(R.string.photo_large), { onLarge(it); editing = null }) { editing = null }
        "thumb" -> PhotoCropDialog(file, 1f, thumb, str(R.string.photo_thumb), { onThumb(it); editing = null }) { editing = null }
    }
}
