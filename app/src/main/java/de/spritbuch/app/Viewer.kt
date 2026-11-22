package de.spritbuch.app

import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Seiten von Belegen und Dokumenten als Bilder: Fotos direkt, PDFs Seite für Seite über den in Android
 * eingebauten PDF-Renderer (keine zusätzliche Bibliothek). Ergebnisse bleiben im Speicher.
 */
object PageRenderer {
    private val cache = object : LruCache<String, ImageBitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }
    private val counts = HashMap<String, Int>()
    private val lock = Any()

    /** Anzahl Seiten (Foto = 1). */
    suspend fun pageCount(file: File): Int = withContext(Dispatchers.IO) {
        if (!AttachmentStore.isPdf(file.name)) return@withContext 1
        synchronized(lock) {
            counts[file.path]?.let { return@withContext it }
            val n = runCatching {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { it.pageCount } }
            }.getOrDefault(0)
            counts[file.path] = n
            n
        }
    }

    /** Seite [page] etwa [width] Pixel breit zeichnen. */
    suspend fun render(file: File, page: Int, width: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        val key = "${file.path}#$page@$width@${file.lastModified()}"
        cache.get(key)?.let { return@withContext it }
        val bmp = if (!AttachmentStore.isPdf(file.name)) {
            PhotoCache.load(file, PhotoCache.bucket(width.toFloat()))
        } else synchronized(lock) {
            runCatching {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    PdfRenderer(fd).use { r ->
                        r.openPage(page.coerceIn(0, r.pageCount - 1)).use { p ->
                            val w = width.coerceIn(64, 2400)
                            val h = (w.toFloat() * p.height / p.width).toInt().coerceAtLeast(1)
                            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            b.eraseColor(AColor.WHITE) // PDFs sind oft transparent
                            p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            b.asImageBitmap()
                        }
                    }
                }
            }.getOrNull()
        }
        bmp?.also { cache.put(key, it) }
    }
}

/** Vorschau der ersten Seite (Foto oder PDF). */
@Composable
fun PageThumbnail(name: String, modifier: Modifier) {
    val ctx = LocalContext.current
    val file = remember(name) { AttachmentStore.file(ctx, name) }
    val bmp by produceState<ImageBitmap?>(null, name) { value = PageRenderer.render(file, 0, 320) }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Icon(Icons.Default.Description, contentDescription = null)
    }
}

/**
 * Bildschirmfüllender Betrachter: wischt durch alle Seiten aller Dateien (Fotos und PDF-Seiten),
 * zoomen mit zwei Fingern, Doppeltipp setzt zurück. „In anderer App öffnen“ für Teilen/Drucken.
 */
@Composable
fun PageViewer(names: List<String>, startFile: Int, onClose: () -> Unit) {
    val ctx = LocalContext.current
    // Seitenliste: (Datei, Seite) – PDFs mit allen Seiten
    val pages by produceState<List<Pair<String, Int>>?>(null, names) {
        value = names.flatMap { n -> (0 until PageRenderer.pageCount(AttachmentStore.file(ctx, n)).coerceAtLeast(1)).map { n to it } }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val list = pages
            if (list == null || list.isEmpty()) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else {
                val start = list.indexOfFirst { it.first == names.getOrNull(startFile) }.coerceAtLeast(0)
                val pager = rememberPagerState(initialPage = start) { list.size }
                HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1) { i ->
                    val (name, page) = list[i]
                    ZoomablePage(AttachmentStore.file(ctx, name), page)
                }
                Column(Modifier.fillMaxWidth().systemBarsPadding().background(Color.Black.copy(alpha = 0.5f))) {
                    Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = str(R.string.close), tint = Color.White) }
                        Text(
                            "${pager.currentPage + 1} / ${list.size}", color = Color.White,
                            style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { AttachmentStore.open(ctx, list[pager.currentPage].first) }) {
                            Icon(Icons.Default.OpenInNew, contentDescription = str(R.string.open_other_app), tint = Color.White)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ZoomablePage(file: File, page: Int) {
    val width = with(androidx.compose.ui.platform.LocalDensity.current) {
        (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp.toPx() * 1.5f).toInt()
    }
    val bmp by produceState<ImageBitmap?>(null, file, page) { value = PageRenderer.render(file, page, width) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier.fillMaxSize().clipToBounds()
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { scale = 1f; offset = Offset.Zero })
            }
            .pointerInput(Unit) {
                // Nur mit zwei Fingern bzw. im Zoom eingreifen, damit das Wischen zur nächsten Seite frei bleibt
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val multi = event.changes.count { it.pressed } >= 2
                        if (multi || scale > 1f) {
                            scale = (scale * event.calculateZoom()).coerceIn(1f, 6f)
                            offset = if (scale <= 1f) Offset.Zero else offset + event.calculatePan()
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val b = bmp
        if (b == null) {
            CircularProgressIndicator()
        } else {
            Image(
                b, contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
                },
            )
        }
    }
}
