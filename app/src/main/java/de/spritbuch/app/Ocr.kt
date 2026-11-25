package de.spritbuch.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
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
 * Sprachmodelle (tessdata_fast deu/dan) liegen in den Assets
 * und werden beim ersten Gebrauch entpackt.
 */
object Ocr {
    private const val TEXT_LANGS = "deu+dan"
    private val mutex = Mutex()

    private fun dataDir(ctx: Context): File {
        val base = File(ctx.filesDir, "ocr")
        val td = File(base, "tessdata").apply { mkdirs() }
        for (l in TEXT_LANGS.split("+")) {
            val f = File(td, "$l.traineddata")
            if (!f.exists() || f.length() == 0L) {
                ctx.assets.open("tessdata/$l.traineddata").use { input -> f.outputStream().use { input.copyTo(it) } }
            }
        }
        return base
    }

    /** Lädt ein Foto (gedreht nach EXIF), verkleinert auf maxSide. */
    fun load(ctx: Context, uri: Uri, maxSide: Int): Bitmap {
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

    /** Ausschnitt (Koordinaten 0…1) und Aufbereitung: Graustufen, Kontrast strecken, kleine Schrift vergrößern. */
    fun prepare(src: Bitmap, crop: RectF?): Bitmap {
        val c = crop ?: RectF(0f, 0f, 1f, 1f)
        val x = (c.left.coerceIn(0f, 1f) * src.width).toInt().coerceAtMost(src.width - 1)
        val y = (c.top.coerceIn(0f, 1f) * src.height).toInt().coerceAtMost(src.height - 1)
        val w = ((c.right - c.left).coerceIn(0.02f, 1f) * src.width).toInt().coerceAtMost(src.width - x).coerceAtLeast(1)
        val h = ((c.bottom - c.top).coerceIn(0.02f, 1f) * src.height).toInt().coerceAtMost(src.height - y).coerceAtLeast(1)
        val cut = Bitmap.createBitmap(src, x, y, w, h)

        // Kleine Ausschnitte hochskalieren: Tesseract mag Ziffern ab ca. 30 px Höhe
        val minSide = 1200
        val scale = if (maxOf(w, h) < minSide) minSide.toFloat() / maxOf(w, h) else 1f
        val out = Bitmap.createBitmap((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val gray = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) }
        canvas.drawBitmap(cut, null, android.graphics.Rect(0, 0, out.width, out.height), gray)

        // Kontrast: 2 %- und 98 %-Helligkeit auf Schwarz/Weiß strecken (hilft bei blassem Thermopapier)
        val px = IntArray(out.width * out.height)
        out.getPixels(px, 0, out.width, 0, 0, out.width, out.height)
        val hist = IntArray(256)
        for (p in px) hist[p and 0xFF]++
        fun percentile(q: Double): Int {
            val target = (px.size * q).toInt()
            var acc = 0
            for (i in 0..255) { acc += hist[i]; if (acc >= target) return i }
            return 255
        }
        val lo = percentile(0.02)
        val hi = percentile(0.98)
        if (hi - lo in 10..240) {
            val f = 255f / (hi - lo)
            for (i in px.indices) {
                val v = (((px[i] and 0xFF) - lo) * f).toInt().coerceIn(0, 255)
                px[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
            out.setPixels(px, 0, out.width, 0, 0, out.width, out.height)
        }
        return out
    }

    private fun run(ctx: Context, bmp: Bitmap, lang: String): String {
        val api = TessBaseAPI()
        try {
            if (!api.init(dataDir(ctx).absolutePath, lang)) error(str(R.string.ocr_failed))
            api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
            api.setImage(bmp)
            return api.getUTF8Text() ?: ""
        } finally {
            api.recycle()
        }
    }

    /** Erkennt den Text eines Kassenbons. */
    suspend fun recognize(ctx: Context, source: Bitmap, crop: RectF?): String = mutex.withLock {
        withContext(Dispatchers.Default) { run(ctx, prepare(source, crop), TEXT_LANGS) }
    }
}
