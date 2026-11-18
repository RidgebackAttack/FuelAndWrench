package de.spritbuch.app

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.abs

/**
 * Ausschnitt wählen: Rahmen an den Ecken ziehen oder in der Mitte verschieben.
 * Liefert den Ausschnitt in Bildkoordinaten 0…1.
 */
@Composable
fun CropDialog(bitmap: Bitmap, initial: RectF, hint: String, onCancel: () -> Unit, onDone: (RectF) -> Unit) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    var l by remember { mutableStateOf(initial.left) }
    var t by remember { mutableStateOf(initial.top) }
    var r by remember { mutableStateOf(initial.right) }
    var b by remember { mutableStateOf(initial.bottom) }
    val handleRadius = with(LocalDensity.current) { 28.dp.toPx() }
    val accent = MaterialTheme.colorScheme.primary

    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black)) {
            Text(hint, color = Color.White, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(8.dp)) {
                val boxW = constraints.maxWidth.toFloat()
                val boxH = constraints.maxHeight.toFloat()
                val scale = minOf(boxW / bitmap.width, boxH / bitmap.height)
                val imgW = bitmap.width * scale
                val imgH = bitmap.height * scale
                val ox = (boxW - imgW) / 2
                val oy = (boxH - imgH) / 2
                Image(image, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                Canvas(
                    Modifier.fillMaxSize().pointerInput(bitmap) {
                        var mode = 0 // 1=links oben, 2=rechts oben, 3=links unten, 4=rechts unten, 5=verschieben
                        detectDragGestures(
                            onDragStart = { p ->
                                val corners = listOf(
                                    1 to Offset(ox + l * imgW, oy + t * imgH), 2 to Offset(ox + r * imgW, oy + t * imgH),
                                    3 to Offset(ox + l * imgW, oy + b * imgH), 4 to Offset(ox + r * imgW, oy + b * imgH),
                                )
                                val near = corners.minBy { (it.second - p).getDistance() }
                                mode = when {
                                    (near.second - p).getDistance() <= handleRadius * 1.6f -> near.first
                                    p.x in (ox + l * imgW)..(ox + r * imgW) && p.y in (oy + t * imgH)..(oy + b * imgH) -> 5
                                    else -> 0
                                }
                            },
                        ) { change, d ->
                            change.consume()
                            val dx = d.x / imgW
                            val dy = d.y / imgH
                            val min = 0.05f
                            when (mode) {
                                1 -> { l = (l + dx).coerceIn(0f, r - min); t = (t + dy).coerceIn(0f, b - min) }
                                2 -> { r = (r + dx).coerceIn(l + min, 1f); t = (t + dy).coerceIn(0f, b - min) }
                                3 -> { l = (l + dx).coerceIn(0f, r - min); b = (b + dy).coerceIn(t + min, 1f) }
                                4 -> { r = (r + dx).coerceIn(l + min, 1f); b = (b + dy).coerceIn(t + min, 1f) }
                                5 -> {
                                    val mx = dx.coerceIn(-l, 1f - r)
                                    val my = dy.coerceIn(-t, 1f - b)
                                    l += mx; r += mx; t += my; b += my
                                }
                            }
                        }
                    },
                ) {
                    val left = ox + l * imgW
                    val top = oy + t * imgH
                    val right = ox + r * imgW
                    val bottom = oy + b * imgH
                    val shade = Color.Black.copy(alpha = 0.55f)
                    // Außenbereich abdunkeln
                    drawRect(shade, Offset(ox, oy), Size(imgW, top - oy))
                    drawRect(shade, Offset(ox, bottom), Size(imgW, oy + imgH - bottom))
                    drawRect(shade, Offset(ox, top), Size(left - ox, bottom - top))
                    drawRect(shade, Offset(right, top), Size(ox + imgW - right, bottom - top))
                    drawRect(accent, Offset(left, top), Size(right - left, bottom - top), style = Stroke(width = 3.dp.toPx()))
                    for (c in listOf(Offset(left, top), Offset(right, top), Offset(left, bottom), Offset(right, bottom))) {
                        drawCircle(Color.White, radius = handleRadius / 2.2f, center = c)
                        drawCircle(accent, radius = handleRadius / 2.2f, center = c, style = Stroke(width = 3.dp.toPx()))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text(str(R.string.cancel), color = Color.White) }
                OutlinedButton(onClick = { l = 0f; t = 0f; r = 1f; b = 1f }, modifier = Modifier.weight(1f)) {
                    Text(str(R.string.crop_all), color = Color.White)
                }
                Button(onClick = { onDone(RectF(l, t, r, b)) }, modifier = Modifier.weight(1f)) { Text(str(R.string.crop_recognize)) }
            }
        }
    }
}

enum class ScanTarget { ODOMETER, AMOUNT, PRICE, TOTAL }

data class ScanReview(
    val forOdometer: Boolean,
    val values: Map<ScanTarget, Double>,
    val fuelType: String?,
    val date: Triple<Int, Int, Int>?,
    val numbers: List<Pair<String, Double>>,
)

/**
 * „Stimmt das?“ nach der Erkennung. Bei „Korrigieren“ werden alle gefundenen Zahlen angezeigt;
 * Antippen und Feld wählen ordnet sie zu.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScanReviewDialog(
    review: ScanReview,
    label: (ScanTarget) -> String,
    format: (ScanTarget, Double) -> String,
    extras: String?,
    onDismiss: () -> Unit,
    onApply: (Map<ScanTarget, Double>) -> Unit,
) {
    val targets = if (review.forOdometer) listOf(ScanTarget.ODOMETER) else listOf(ScanTarget.AMOUNT, ScanTarget.PRICE, ScanTarget.TOTAL)
    val values = remember { mutableStateMapOf<ScanTarget, Double>().apply { putAll(review.values) } }
    var correcting by remember { mutableStateOf(review.values.isEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(str(if (correcting) R.string.scan_correct_title else R.string.scan_review_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                targets.forEach { tgt ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(label(tgt), modifier = Modifier.weight(1f))
                        Text(values[tgt]?.let { format(tgt, it) } ?: "–", fontWeight = FontWeight.SemiBold)
                        if (correcting && values[tgt] != null) {
                            IconButton(onClick = { values.remove(tgt) }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Close, contentDescription = str(R.string.remove), modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
                if (!correcting && extras != null) Text(extras, style = MaterialTheme.typography.bodySmall)
                if (correcting) {
                    if (review.numbers.isEmpty()) {
                        Text(str(R.string.scan_nothing), style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text(str(R.string.scan_correct_info), style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            review.numbers.forEach { (text, value) ->
                                var menu by remember { mutableStateOf(false) }
                                Box {
                                    AssistChip(onClick = {
                                        if (targets.size == 1) values[targets.first()] = value else menu = true
                                    }, label = { Text(text) })
                                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                        targets.forEach { tgt ->
                                            DropdownMenuItem(text = { Text(label(tgt)) }, onClick = { values[tgt] = value; menu = false })
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(values.toMap()) }, enabled = values.isNotEmpty()) {
                Text(str(if (correcting) R.string.scan_apply else R.string.scan_correct_yes))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text(str(R.string.cancel)) }
                if (!correcting) TextButton(onClick = { correcting = true }) { Text(str(R.string.scan_correct)) }
            }
        },
    )
}

/** Abstand zweier Zahlen relativ, für die Plausibilitätsanzeige. */
internal fun relDiff(a: Double, b: Double) = if (b == 0.0) 0.0 else abs(a - b) / abs(b)
