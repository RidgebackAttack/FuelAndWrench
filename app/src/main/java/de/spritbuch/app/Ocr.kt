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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
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

    /**
     * Lädt ein Foto (gedreht nach EXIF) und verkleinert es auf höchstens [maxSide] – auch bei 50-MP-Kameras.
     * Drehen und Verkleinern passieren in einem Schritt, Zwischenbilder werden sofort freigegeben.
     */
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
        val scale = minOf(1f, maxSide.toFloat() / maxOf(bmp.width, bmp.height))
        if (rotation == 0 && scale >= 1f) return bmp
        val m = Matrix().apply { postScale(scale, scale); postRotate(rotation.toFloat()) }
        val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (out !== bmp) bmp.recycle()
        return out
    }

    /**
     * Liest nur den Ausschnitt [crop] (0…1, bezogen auf das gedrehte Bild) aus dem Originalfoto in voller
     * Auflösung, begrenzt auf [maxSide]. So wird ein kleiner Bon in einem großen Foto scharf erkannt,
     * ohne das ganze Foto in den Speicher zu laden.
     */
    fun loadRegion(ctx: Context, uri: Uri, crop: RectF, maxSide: Int): Bitmap {
        val r = ctx.contentResolver
        val rotation = runCatching { r.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0 }.getOrDefault(0)
        val c = RectF(crop.left.coerceIn(0f, 1f), crop.top.coerceIn(0f, 1f), crop.right.coerceIn(0f, 1f), crop.bottom.coerceIn(0f, 1f))
        val o = regionInOriginal(c, rotation)
        val bmp = r.openInputStream(uri)?.use { input ->
            @Suppress("DEPRECATION")
            val dec = if (android.os.Build.VERSION.SDK_INT >= 31) android.graphics.BitmapRegionDecoder.newInstance(input)
            else android.graphics.BitmapRegionDecoder.newInstance(input, false)
            try {
                dec ?: error(str(R.string.err_image_read))
                val w = dec.width
                val h = dec.height
                val rect = android.graphics.Rect(
                    (o.left * w).toInt(), (o.top * h).toInt(),
                    (o.right * w).toInt().coerceAtLeast((o.left * w).toInt() + 1), (o.bottom * h).toInt().coerceAtLeast((o.top * h).toInt() + 1),
                )
                var sample = 1
                while (maxOf(rect.width(), rect.height()) / (sample * 2) >= maxSide) sample *= 2
                dec.decodeRegion(rect, BitmapFactory.Options().apply { inSampleSize = sample })
            } finally {
                dec?.recycle()
            }
        } ?: error(str(R.string.err_image_read))
        val scale = minOf(1f, maxSide.toFloat() / maxOf(bmp.width, bmp.height))
        if (rotation == 0 && scale >= 1f) return bmp
        val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postScale(scale, scale); postRotate(rotation.toFloat()) }, true)
        if (out !== bmp) bmp.recycle()
        return out
    }

    /** Ausschnitt im gedrehten Bild → Ausschnitt im Original (EXIF-Drehung 0/90/180/270), jeweils 0…1. */
    fun regionInOriginal(c: RectF, rotation: Int): RectF = when ((rotation % 360 + 360) % 360) {
        90 -> RectF(c.top, 1 - c.right, c.bottom, 1 - c.left)
        180 -> RectF(1 - c.right, 1 - c.bottom, 1 - c.left, 1 - c.top)
        270 -> RectF(1 - c.bottom, c.left, 1 - c.top, c.right)
        else -> RectF(c)
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
        if (cut !== src) cut.recycle()

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

    /** Längstens so lange darf die Erkennung dauern, danach wird sie abgebrochen. */
    private const val TIMEOUT_MS = 60_000L

    /**
     * Erkennt den Text eines Kassenbons. Läuft mit niedriger Priorität, damit das Telefon bedienbar bleibt,
     * und lässt sich abbrechen: Zeitlimit oder Verlassen der Eingabe stoppen Tesseract sofort, statt es
     * im Hintergrund weiterrechnen zu lassen.
     */
    suspend fun recognize(ctx: Context, source: Bitmap, crop: RectF?): String = mutex.withLock {
        val img = withContext(Dispatchers.Default) { prepare(source, crop) }
        val api = TessBaseAPI()
        if (!api.init(dataDir(ctx).absolutePath, TEXT_LANGS)) {
            api.recycle()
            img.recycle()
            error(str(R.string.ocr_failed))
        }
        api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
        api.setImage(img)
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        // Eigener Thread, damit ein Abbruch nicht auf das Ende der nativen Erkennung warten muss
        val work = CoroutineScope(Dispatchers.IO).async {
            val t = android.os.Process.getThreadPriority(android.os.Process.myTid())
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            try {
                api.getHOCRText(0) // unterbrechbar über stop()
                api.getUTF8Text() ?: "" // liefert nur noch das schon erkannte Ergebnis
            } finally {
                synchronized(done) { done.set(true); api.recycle() }
                img.recycle()
                android.os.Process.setThreadPriority(t)
            }
        }
        try {
            withTimeout(TIMEOUT_MS) { work.await() }
        } catch (e: Throwable) {
            synchronized(done) { if (!done.get()) api.stop() }
            if (e is TimeoutCancellationException) error(str(R.string.ocr_timeout)) else throw e
        }
    }
}
