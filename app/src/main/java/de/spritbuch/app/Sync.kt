package de.spritbuch.app

import android.content.Context
import androidx.room.withTransaction
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

fun httpMessage(code: Int): String = when (code) {
    401 -> str(R.string.http_401)
    403 -> str(R.string.http_403)
    404 -> str(R.string.http_404)
    409 -> str(R.string.http_409)
    507 -> str(R.string.http_507)
    else -> str(R.string.http_other, code)
}

class WebDavClient(baseUrl: String, user: String, password: String) {
    private val base = baseUrl.trim().trimEnd('/')
    private val auth = Credentials.basic(user.trim(), password)
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    class Result(val code: Int, val body: ByteArray?, val etag: String?)

    private fun req(path: String) = Request.Builder()
        .url(if (path.isEmpty()) "$base/" else "$base/${encodePath(path)}")
        .header("Authorization", auth)
        // Ohne Komprimierung: Reverse-Proxys (z. B. nginx mit gzip) machen ETags sonst „schwach“ (W/…),
        // und If-Match schlägt dann immer fehl.
        .header("Accept-Encoding", "identity")

    private fun exec(r: Request): Result = client.newCall(r).execute().use {
        Result(it.code, if (it.isSuccessful) it.body?.bytes() else null, it.header("ETag"))
    }

    fun get(path: String) = exec(req(path).get().build())

    fun propfind(path: String, xml: String): Result {
        val url = if (path.isEmpty()) "$base/" else "$base/${encodePath(path)}/"
        return exec(
            Request.Builder().url(url).header("Authorization", auth).header("Depth", "1")
                .method("PROPFIND", xml.toRequestBody("application/xml; charset=utf-8".toMediaType()))
                .build()
        )
    }
    fun head(path: String): Int = exec(req(path).head().build()).code

    fun put(path: String, data: ByteArray, type: String, ifMatch: String? = null, ifNoneMatch: Boolean = false): Int {
        val b = req(path).put(data.toRequestBody(type.toMediaType()))
        if (ifMatch != null) b.header("If-Match", ifMatch.removePrefix("W/"))
        if (ifNoneMatch) b.header("If-None-Match", "*")
        return exec(b.build()).code
    }

    /** Legt einen Ordner an; "existiert schon" (405) ist ok. */
    fun ensureDir(path: String) {
        val code = exec(req(path).method("MKCOL", null).build()).code
        if (code != 405 && code !in 200..299) throw IOException(httpMessage(code))
    }
}

@Serializable
data class SyncData(
    val format: Int = 1,
    val vehicles: List<Vehicle> = emptyList(),
    val refuels: List<Refuel> = emptyList(),
    val reminders: List<Reminder> = emptyList(),
    val services: List<ServiceEntry> = emptyList(),
    val tireSets: List<TireSet> = emptyList(),
    val reminderTemplates: List<TemplateItem> = emptyList(),
    val templatesUpdatedAt: Long = 0,
)

@Serializable
data class TemplateItem(val title: String, val km: Int)

data class SyncResult(val ok: Boolean, val message: String)

/**
 * Synchronisation über eine gemeinsame JSON-Datei im WebDAV-Ordner.
 * - Jeder Datensatz hat eine UUID und einen Änderungszeitstempel; pro Datensatz gewinnt die neuere Version.
 * - Löschungen werden als "deleted" markiert, damit sie auf alle Geräte übertragen werden.
 * - Hochladen nur mit If-Match (ETag): Hat ein anderes Gerät zwischenzeitlich geschrieben,
 *   lehnt der Server ab (412) und es wird neu zusammengeführt. So geht bei gleichzeitiger
 *   Nutzung auf mehreren Geräten nichts verloren.
 */
object SyncManager {
    private const val FILE = "spritbuch.json"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()
    val running = MutableStateFlow(false)
    val status = MutableStateFlow("")

    suspend fun sync(ctx: Context): SyncResult = mutex.withLock {
        val app = ctx.applicationContext
        val prefs = Prefs(app)
        if (!prefs.configured) {
            return@withLock SyncResult(true, str(R.string.sync_not_configured))
        }
        running.value = true
        val result = try {
            SyncResult(true, withContext(Dispatchers.IO) { doSync(app, prefs) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SyncResult(false, str(R.string.error_prefix, e.message ?: e.javaClass.simpleName))
        } finally {
            running.value = false
        }
        val stamped = "${now().dateTime()} – ${result.message}"
        prefs.lastSync = stamped
        status.value = stamped
        result
    }

    private suspend fun doSync(ctx: Context, prefs: Prefs): String {
        val dav = WebDavClient(prefs.url, prefs.user, prefs.password)
        dav.ensureDir("")
        dav.ensureDir("photos")
        val db = ctx.repo.db

        var previousEtag: String? = null
        var etagUnreliable = false
        repeat(5) {
            val remote = dav.get(FILE)
            val remoteData: SyncData? = when {
                remote.code == 404 -> null
                remote.code in 200..299 -> json.decodeFromString<SyncData>(remote.body?.decodeToString() ?: "{}")
                else -> throw IOException(httpMessage(remote.code))
            }
            val r = remoteData ?: SyncData()

            // Vorlagen für Standard-Erinnerungen: die zuletzt geänderte Liste gewinnt
            val remoteTplNewer = r.templatesUpdatedAt > prefs.templatesUpdatedAt
            if (remoteTplNewer) {
                prefs.setTemplatesFromSync(r.reminderTemplates.map { it.title to it.km }, r.templatesUpdatedAt)
            }
            val tplList = prefs.reminderTemplates.map { TemplateItem(it.first, it.second) }
            val tplTs = prefs.templatesUpdatedAt

            val merged = db.withTransaction {
                SyncData(
                    reminderTemplates = tplList,
                    templatesUpdatedAt = tplTs,
                    vehicles = mergeInto(db.vehicles().getAllRaw(), r.vehicles, { it.id }, { it.updatedAt }) { db.vehicles().upsert(it) },
                    refuels = mergeInto(db.refuels().getAllRaw(), r.refuels, { it.id }, { it.updatedAt }) { db.refuels().upsert(it) },
                    reminders = mergeInto(db.reminders().getAllRaw(), r.reminders, { it.id }, { it.updatedAt }) { db.reminders().upsert(it) },
                    services = mergeInto(db.services().getAllRaw(), r.services, { it.id }, { it.updatedAt }) { db.services().upsert(it) },
                    tireSets = mergeInto(db.tireSets().getAllRaw(), r.tireSets, { it.id }, { it.updatedAt }) { db.tireSets().upsert(it) },
                )
            }

            val remoteChanged = remoteData == null ||
                merged.vehicles.toSet() != r.vehicles.toSet() ||
                merged.refuels.toSet() != r.refuels.toSet() ||
                merged.reminders.toSet() != r.reminders.toSet() ||
                merged.services.toSet() != r.services.toSet() ||
                merged.tireSets.toSet() != r.tireSets.toSet() ||
                merged.templatesUpdatedAt != r.templatesUpdatedAt || merged.reminderTemplates != r.reminderTemplates

            if (remoteChanged) {
                val code = dav.put(
                    FILE,
                    json.encodeToString(merged).toByteArray(),
                    "application/json",
                    ifMatch = if (remoteData != null && !etagUnreliable) remote.etag else null,
                    ifNoneMatch = remoteData == null,
                )
                if (code == 412) {
                    // Hat sich die Datei seit dem letzten Versuch gar nicht verändert, ist es kein echter
                    // Konflikt, sondern der Server/Proxy vergleicht das ETag nicht korrekt → ohne If-Match hochladen.
                    if (remote.etag != null && remote.etag == previousEtag) etagUnreliable = true
                    previousEtag = remote.etag
                    return@repeat // sonst: anderes Gerät war schneller → neu zusammenführen
                }
                if (code !in 200..299) throw IOException(httpMessage(code))
            }

            syncPhotos(ctx, dav, prefs, merged.vehicles)
            ReminderChecker.checkAll(ctx)
            val v = merged.vehicles.count { !it.deleted }
            val t = merged.refuels.count { !it.deleted }
            return str(R.string.sync_ok, v, t)
        }
        throw IOException(str(R.string.sync_conflict))
    }

    private suspend fun <T> mergeInto(
        local: List<T>, remote: List<T>,
        id: (T) -> String, ts: (T) -> Long,
        write: suspend (T) -> Unit,
    ): List<T> {
        val result = local.associateBy(id).toMutableMap()
        for (item in remote) {
            val l = result[id(item)]
            if (l == null || ts(item) > ts(l)) {
                result[id(item)] = item
                write(item)
            }
        }
        return result.values.toList()
    }

    private fun syncPhotos(ctx: Context, dav: WebDavClient, prefs: Prefs, vehicles: List<Vehicle>) {
        val uploaded = prefs.uploadedPhotos.toMutableSet()
        for (v in vehicles) {
            if (v.deleted || v.photoVersion == 0L) continue
            val name = PhotoStore.name(v.id, v.photoVersion)
            val file = PhotoStore.file(ctx, v.id, v.photoVersion)
            if (file.exists()) {
                if (name in uploaded) continue
                if (dav.head("photos/$name") == 404) {
                    val code = dav.put("photos/$name", file.readBytes(), "image/jpeg")
                    if (code !in 200..299) continue
                }
                uploaded += name
            } else {
                val got = dav.get("photos/$name")
                val body = got.body
                if (got.code in 200..299 && body != null) {
                    file.parentFile?.mkdirs()
                    file.writeBytes(body)
                    uploaded += name
                }
            }
        }
        prefs.uploadedPhotos = uploaded
    }
}

class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val res = SyncManager.sync(applicationContext)
        return if (res.ok) Result.success() else if (runAttemptCount < 3) Result.retry() else Result.failure()
    }
}

object SyncScheduler {
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Regelmäßiger Abgleich im Hintergrund (Android-Minimum: alle 15 Minuten). */
    fun schedulePeriodic(ctx: Context) {
        if (!Prefs(ctx).configured) return
        val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(network).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("sync-periodic", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** Abgleich direkt nach einer Änderung bzw. beim Öffnen der App. */
    fun syncSoon(ctx: Context, delaySeconds: Long = 2) {
        if (!Prefs(ctx).configured) return
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .setConstraints(network)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("sync-now", ExistingWorkPolicy.REPLACE, req)
    }
}
