package de.spritbuch.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Texterkennung komplett auf dem Gerät (Tesseract, Apache-2.0). Es werden keine Bilder übertragen.
 * Sprachmodelle (tessdata_fast) liegen in den Assets und werden beim ersten Gebrauch entpackt.
 */
object Ocr {
    private const val LANGS = "deu+dan"
    private val mutex = Mutex()

    private fun dataDir(ctx: Context): File {
        val base = File(ctx.filesDir, "ocr")
        val td = File(base, "tessdata").apply { mkdirs() }
        for (l in LANGS.split("+")) {
            val f = File(td, "$l.traineddata")
            if (!f.exists() || f.length() == 0L) {
                ctx.assets.open("tessdata/$l.traineddata").use { input -> f.outputStream().use { input.copyTo(it) } }
            }
        }
        return base
    }

    private fun load(ctx: Context, uri: Uri, maxSide: Int): Bitmap {
        val r = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        r.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val bmp = r.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error(str(R.string.err_image_read))
        val rotation = runCatching { r.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0 }.getOrDefault(0)
        return if (rotation != 0) {
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
        } else bmp
    }

    /** Erkennt Text; mit digitsOnly nur Ziffern (für den Kilometerstand). */
    suspend fun recognize(ctx: Context, uri: Uri, digitsOnly: Boolean): String = mutex.withLock {
        withContext(Dispatchers.Default) {
            val bmp = load(ctx, uri, if (digitsOnly) 1600 else 2400)
            val api = TessBaseAPI()
            try {
                if (!api.init(dataDir(ctx).absolutePath, LANGS)) error(str(R.string.ocr_failed))
                if (digitsOnly) {
                    api.setVariable("tessedit_char_whitelist", "0123456789")
                    api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT)
                } else {
                    api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
                }
                api.setImage(bmp)
                api.getUTF8Text() ?: ""
            } finally {
                api.recycle()
            }
        }
    }
}
