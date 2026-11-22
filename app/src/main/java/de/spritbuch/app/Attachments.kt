package de.spritbuch.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Belege (Fotos, PDFs) zu Service-Einträgen und Kosten. Dateien liegen in files/attachments. */
object AttachmentStore {
    const val DIR = "attachments"

    fun dir(ctx: Context) = File(ctx.filesDir, DIR).apply { mkdirs() }
    fun file(ctx: Context, name: String) = File(dir(ctx), name)
    fun isPdf(name: String) = name.endsWith(".pdf", ignoreCase = true)
    fun mime(name: String) = if (isPdf(name)) "application/pdf" else "image/jpeg"

    /** Kopiert eine ausgewählte Datei; Bilder werden auf max. 2000 px verkleinert und gedreht. */
    suspend fun import(ctx: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val type = ctx.contentResolver.getType(uri) ?: ""
        if (type == "application/pdf") {
            val name = newId() + ".pdf"
            ctx.contentResolver.openInputStream(uri)?.use { input -> file(ctx, name).outputStream().use { input.copyTo(it) } }
                ?: error(str(R.string.err_image_read))
            name
        } else {
            val name = newId() + ".jpg"
            val r = ctx.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            r.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 2000) sample *= 2
            val bmp = r.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: error(str(R.string.err_image_read))
            val rotation = runCatching { r.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0 }.getOrDefault(0)
            val out = if (rotation != 0) {
                Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
            } else bmp
            file(ctx, name).outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            name
        }
    }

    fun uri(ctx: Context, f: File): Uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)

    fun open(ctx: Context, name: String) {
        val f = file(ctx, name)
        if (!f.exists()) return
        runCatching {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri(ctx, f), mime(name))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        }
    }

    /** Leere Datei im Cache für die Kamera-App. */
    fun cameraTarget(ctx: Context): File = File(ctx.cacheDir, "camera").apply { mkdirs() }.let { File(it, "capture.jpg") }
}

/** Belegliste mit „Foto aufnehmen“ und „Datei anhängen“. */
@Composable
fun AttachmentSection(names: List<String>, title: String = str(R.string.receipts), onChange: (List<String>) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    val current by androidx.compose.runtime.rememberUpdatedState(names)

    fun add(uri: Uri) = scope.launch {
        runCatching { AttachmentStore.import(ctx, uri) }
            .onSuccess { onChange(current + it); error = null }
            .onFailure { error = it.message }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) add(uri) }
    val target = remember { AttachmentStore.cameraTarget(ctx) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) add(Uri.fromFile(target))
    }

    var viewing by remember { mutableStateOf<Int?>(null) }
    SectionTitle(title)
    names.forEachIndexed { index, name ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            // Vorschau der (ersten) Seite – auch bei PDFs
            PageThumbnail(name, Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)).clickable { viewing = index })
            Spacer(Modifier.width(12.dp))
            TextButton(onClick = { viewing = index }, modifier = Modifier.weight(1f)) {
                Text(str(if (AttachmentStore.isPdf(name)) R.string.receipt_pdf else R.string.receipt_photo), modifier = Modifier.fillMaxWidth())
            }
            IconButton(onClick = { onChange(current - name) }) { Icon(Icons.Default.Close, contentDescription = str(R.string.remove)) }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = {
            runCatching { camera.launch(AttachmentStore.uri(ctx, target)) }.onFailure { error = it.message }
        }, modifier = Modifier.weight(1f)) {
            Icon(Icons.Default.PhotoCamera, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(str(R.string.take_photo))
        }
        OutlinedButton(onClick = { picker.launch(arrayOf("image/*", "application/pdf")) }, modifier = Modifier.weight(1f)) {
            Icon(Icons.Default.AttachFile, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(str(R.string.attach_file))
        }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    viewing?.let { PageViewer(names, it) { viewing = null } }
}
