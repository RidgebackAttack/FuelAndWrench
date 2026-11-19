package de.spritbuch.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class UpdateInfo(val version: String, val pageUrl: String, val apkUrl: String?)

/**
 * Hinweis auf neue Versionen für direkt (z. B. per Browser) installierte APKs.
 * Wer über F-Droid, Obtainium oder einen anderen Store installiert, bekommt Updates dort – dann prüft die App nicht.
 */
object UpdateChecker {
    private const val API = "https://api.github.com/repos/RidgebackAttack/FuelAndWrench/releases/latest"
    private const val CHANNEL = "updates"
    private val stores = setOf(
        "org.fdroid.fdroid", "org.fdroid.basic", "org.fdroid.fdroid.privileged",
        "dev.imranr.obtainium", "dev.imranr.obtainium.fdroid",
        "com.looker.droidify", "com.machiav3lli.fdroid", "app.accrescent.client",
        "com.aurora.store", "com.android.vending", "in.sunilpaulmathew.izzyondroid",
    )
    val available = MutableStateFlow<UpdateInfo?>(null)

    fun installer(ctx: Context): String? = runCatching {
        if (Build.VERSION.SDK_INT >= 30) ctx.packageManager.getInstallSourceInfo(ctx.packageName).installingPackageName
        else @Suppress("DEPRECATION") ctx.packageManager.getInstallerPackageName(ctx.packageName)
    }.getOrNull()

    /** true, wenn die App nicht über einen App-Store/Updater installiert wurde. */
    fun isDirectInstall(ctx: Context): Boolean = installer(ctx) !in stores

    fun currentVersion(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "0"

    /** Vergleicht Versionen wie „1.10.2“ zahlenweise. */
    fun isNewer(remote: String, local: String): Boolean {
        val r = remote.removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
        val l = local.removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    /** Prüft höchstens alle 20 Stunden (oder sofort mit force). */
    suspend fun check(ctx: Context, force: Boolean = false): UpdateInfo? = withContext(Dispatchers.IO) {
        val prefs = Prefs(ctx)
        if (!isDirectInstall(ctx) || !prefs.updateCheck) return@withContext null
        val cached = prefs.latestRelease
        if (!force && now() - prefs.lastUpdateCheck < 20 * 3_600_000L) {
            return@withContext cached?.takeIf { isNewer(it.version, currentVersion(ctx)) }.also { available.value = it }
        }
        val info = runCatching {
            val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
            val req = Request.Builder().url(API)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "FuelAndWrench-Android")
                .build()
            val body = http.newCall(req).execute().use { if (it.isSuccessful) it.body?.string() else null } ?: return@runCatching null
            val o = Json.parseToJsonElement(body).jsonObject
            val tag = o["tag_name"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
            val page = o["html_url"]?.jsonPrimitive?.contentOrNull ?: "$SOURCE_URL/releases"
            val apks = o["assets"]?.jsonArray?.mapNotNull { it.jsonObject["browser_download_url"]?.jsonPrimitive?.contentOrNull }
                ?.filter { it.endsWith(".apk") }.orEmpty()
            // Passende APK für den Prozessor, sonst die Universal-APK
            val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: ""
            val apk = apks.firstOrNull { it.endsWith("-$abi.apk") }
                ?: apks.firstOrNull { a -> listOf("arm64-v8a", "armeabi-v7a").none { a.endsWith("-$it.apk") } }
                ?: apks.firstOrNull()
            UpdateInfo(tag.removePrefix("v"), page, apk)
        }.getOrNull()
        prefs.lastUpdateCheck = now()
        if (info != null) prefs.latestRelease = info
        val newer = (info ?: cached)?.takeIf { isNewer(it.version, currentVersion(ctx)) }
        available.value = newer
        if (newer != null && prefs.notifiedUpdate != newer.version) {
            prefs.notifiedUpdate = newer.version
            notify(ctx, newer)
        }
        newer
    }

    fun open(ctx: Context, info: UpdateInfo) {
        runCatching {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.apkUrl ?: info.pageUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun notify(ctx: Context, info: UpdateInfo) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, ctx.getString(R.string.update_channel), NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(
            ctx, 1, Intent(Intent.ACTION_VIEW, Uri.parse(info.pageUrl)), PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(ctx.getString(R.string.update_available, info.version))
            .setContentText(ctx.getString(R.string.update_tap))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(4711, n)
        } catch (_: SecurityException) {
        }
    }
}

/** Tägliche Aufgaben: Erinnerungen nach Datum prüfen, nach Updates schauen. */
class DailyWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        runCatching { ReminderChecker.checkAll(applicationContext) }
        runCatching { UpdateChecker.check(applicationContext) }
        runCatching { FuelWidget.refresh(applicationContext) } // Monatswechsel, Fälligkeiten nach Datum
        return Result.success()
    }

    companion object {
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<DailyWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.NOT_REQUIRED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("daily", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
