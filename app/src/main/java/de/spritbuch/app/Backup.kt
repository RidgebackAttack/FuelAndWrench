package de.spritbuch.app

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Backup als ZIP: backup.json (alle Daten inkl. Löschmarken), Fahrzeugfotos, Belege und
 * CSV-Tabellen für Tabellenkalkulationen. Wiederherstellen führt zusammen (neuere Version gewinnt),
 * es wird also nichts Neueres überschrieben.
 */
object Backup {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun suggestedName(): String = "FuelAndWrench-" + SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date()) + ".zip"

    data class Summary(val vehicles: Int, val refuels: Int, val services: Int, val expenses: Int, val files: Int)

    suspend fun export(ctx: Context, target: Uri): Summary = withContext(Dispatchers.IO) {
        val db = ctx.repo.db
        val prefs = Prefs(ctx)
        val data = SyncData(
            vehicles = db.vehicles().getAllRaw(),
            refuels = db.refuels().getAllRaw(),
            reminders = db.reminders().getAllRaw(),
            services = db.services().getAllRaw(),
            tireSets = db.tireSets().getAllRaw(),
            expenses = db.expenses().getAllRaw(),
            reminderTemplates = prefs.reminderTemplates.map { TemplateItem(it.title, it.km, it.months) },
            templatesUpdatedAt = prefs.templatesUpdatedAt,
        )
        var files = 0
        val out = ctx.contentResolver.openOutputStream(target) ?: error(str(R.string.backup_write_error))
        ZipOutputStream(out.buffered()).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
            fun putFile(name: String, f: File) {
                if (!f.isFile) return
                zip.putNextEntry(ZipEntry(name)); f.inputStream().use { it.copyTo(zip) }; zip.closeEntry(); files++
            }
            put("backup.json", json.encodeToString(data).toByteArray())
            val vehicles = data.vehicles.filter { !it.deleted }
            for (v in vehicles) {
                v.images().forEach { img ->
                    putFile("photos/" + PhotoStore.name(v.id, img.version), PhotoStore.file(ctx, v.id, img.version))
                }
            }
            val names = data.services.filter { !it.deleted }.flatMap { it.attachmentList() } +
                data.expenses.filter { !it.deleted }.flatMap { it.attachmentList() } +
                data.vehicles.filter { !it.deleted }.flatMap { v -> v.documentList().flatMap { it.files } }
            names.distinct().forEach { putFile("${AttachmentStore.DIR}/$it", AttachmentStore.file(ctx, it)) }
            val byId = vehicles.associateBy { it.id }
            put("csv/refuels.csv", Csv.refuels(data.refuels.filter { !it.deleted && it.vehicleId in byId }, byId).toByteArray())
            put("csv/services.csv", Csv.services(data.services.filter { !it.deleted && it.vehicleId in byId }, byId).toByteArray())
            put("csv/expenses.csv", Csv.expenses(data.expenses.filter { !it.deleted && it.vehicleId in byId }, byId).toByteArray())
        }
        Summary(
            data.vehicles.count { !it.deleted }, data.refuels.count { !it.deleted },
            data.services.count { !it.deleted }, data.expenses.count { !it.deleted }, files,
        )
    }

    suspend fun restore(ctx: Context, source: Uri): Summary = withContext(Dispatchers.IO) {
        var data: SyncData? = null
        var files = 0
        val input = ctx.contentResolver.openInputStream(source) ?: error(str(R.string.backup_read_error))
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val name = e.name
                // Nur bekannte, flache Pfade übernehmen (kein „../“)
                val base = name.substringAfterLast('/')
                if (e.isDirectory || base.isEmpty() || base.startsWith(".") || name.contains("..")) continue
                when {
                    name == "backup.json" -> data = json.decodeFromString<SyncData>(zip.readBytes().decodeToString())
                    name.startsWith("photos/") -> {
                        val f = File(File(ctx.filesDir, "photos").apply { mkdirs() }, base)
                        if (!f.exists()) { f.writeBytes(zip.readBytes()); files++ }
                    }
                    name.startsWith(AttachmentStore.DIR + "/") -> {
                        val f = AttachmentStore.file(ctx, base)
                        if (!f.exists()) { f.writeBytes(zip.readBytes()); files++ }
                    }
                }
            }
        }
        val d = data ?: error(str(R.string.backup_invalid))
        val db = ctx.repo.db
        suspend fun <T> merge(local: List<T>, incoming: List<T>, id: (T) -> String, ts: (T) -> Long, write: suspend (T) -> Unit) {
            val map = local.associateBy(id)
            for (item in incoming) {
                val l = map[id(item)]
                if (l == null || ts(item) > ts(l)) write(item)
            }
        }
        db.withTransaction {
            merge(db.vehicles().getAllRaw(), d.vehicles, { it.id }, { it.updatedAt }) { db.vehicles().upsert(it) }
            merge(db.refuels().getAllRaw(), d.refuels, { it.id }, { it.updatedAt }) { db.refuels().upsert(it) }
            merge(db.reminders().getAllRaw(), d.reminders, { it.id }, { it.updatedAt }) { db.reminders().upsert(it) }
            merge(db.services().getAllRaw(), d.services, { it.id }, { it.updatedAt }) { db.services().upsert(it) }
            merge(db.tireSets().getAllRaw(), d.tireSets, { it.id }, { it.updatedAt }) { db.tireSets().upsert(it) }
            merge(db.expenses().getAllRaw(), d.expenses, { it.id }, { it.updatedAt }) { db.expenses().upsert(it) }
        }
        val prefs = Prefs(ctx)
        if (d.templatesUpdatedAt > prefs.templatesUpdatedAt) {
            prefs.setTemplatesFromSync(d.reminderTemplates.map { ReminderTemplate(it.title, it.km, it.months) }, d.templatesUpdatedAt)
        }
        SyncScheduler.syncSoon(ctx)
        Summary(
            d.vehicles.count { !it.deleted }, d.refuels.count { !it.deleted },
            d.services.count { !it.deleted }, d.expenses.count { !it.deleted }, files,
        )
    }
}

/** CSV-Tabellen (Trennzeichen und Dezimalzeichen passend zur Sprache, Spaltennamen englisch). */
object Csv {
    private val comma get() = java.text.DecimalFormatSymbols.getInstance(appLocale()).decimalSeparator == ','
    private val sep get() = if (comma) ';' else ','
    private fun num(d: Double?, decimals: Int = 2): String =
        if (d == null) "" else String.format(Locale.ROOT, "%.${decimals}f", d).let { if (comma) it.replace('.', ',') else it }
    private fun date(ts: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date(ts))
    private fun cell(s: String): String = if (s.any { it == sep || it == '"' || it == '\n' || it == '\r' }) {
        "\"" + s.replace("\"", "\"\"") + "\""
    } else s

    private fun table(header: List<String>, rows: List<List<String>>): String =
        "\uFEFF" + (listOf(header) + rows).joinToString("\r\n") { r -> r.joinToString(sep.toString()) { cell(it) } } + "\r\n"

    fun refuels(list: List<Refuel>, vehicles: Map<String, Vehicle>): String {
        val per = list.groupBy { it.vehicleId }.values.flatMap { computeStats(it).perRefuel.entries }.associate { it.key to it.value }
        return table(
            listOf("vehicle", "date", "odometer", "quantity", "unit", "total", "currency", "price per unit", "full",
                "missed previous", "fuel", "station", "address", "latitude", "longitude", "driving style", "route", "tires",
                "extras", "consumption per 100", "on-board computer per 100", "charge location", "battery before %",
                "battery after %", "note"),
            list.sortedWith(compareBy({ vehicles[it.vehicleId]?.name }, { it.odometer })).map { r ->
                val v = vehicles[r.vehicleId]
                listOf(
                    v?.name ?: "", date(r.timestamp), r.odometer.toString(), num(r.amount), Units.amount(v, r.fuelType),
                    num(r.totalPrice), v?.currency ?: "", num(r.pricePerUnit, 3), if (r.fullTank) "1" else "0",
                    if (r.missedPrevious) "1" else "0", r.fuelType, r.stationBrand, r.stationAddress,
                    r.latitude?.let { String.format(Locale.ROOT, "%.6f", it) } ?: "",
                    r.longitude?.let { String.format(Locale.ROOT, "%.6f", it) } ?: "",
                    r.drivingStyle, r.routeType, r.tireType, r.extrasList().joinToString(", "),
                    num(per[r.id]), num(r.obcConsumption), r.chargeLocation, r.socStart?.toString() ?: "",
                    r.socEnd?.toString() ?: "", r.note,
                )
            },
        )
    }

    fun services(list: List<ServiceEntry>, vehicles: Map<String, Vehicle>): String = table(
        listOf("vehicle", "date", "odometer", "title", "category", "cost", "currency", "workshop", "tires", "tread depth", "note"),
        list.sortedWith(compareBy({ vehicles[it.vehicleId]?.name }, { it.timestamp })).map { s ->
            val v = vehicles[s.vehicleId]
            listOf(
                v?.name ?: "", date(s.timestamp), s.odometer.toString(), s.title, s.category, num(s.cost), v?.currency ?: "",
                s.workshop, s.tireSummary(), TreadCodec.summary(s.treadDepth), s.note,
            )
        },
    )

    fun expenses(list: List<Expense>, vehicles: Map<String, Vehicle>): String = table(
        listOf("vehicle", "date", "category", "title", "amount", "currency", "note"),
        list.sortedWith(compareBy({ vehicles[it.vehicleId]?.name }, { it.timestamp })).map { e ->
            val v = vehicles[e.vehicleId]
            listOf(v?.name ?: "", date(e.timestamp), e.category, e.title, num(e.amount), v?.currency ?: "", e.note)
        },
    )
}

/** CSV-Export eines Fahrzeugs (Betankungen, Service, Kosten) über das Teilen-Menü – z. B. für Excel oder die Steuer. */
object CsvShare {
    suspend fun vehicle(ctx: Context, v: Vehicle) {
        val repo = ctx.repo
        val byId = mapOf(v.id to v)
        val files = withContext(Dispatchers.IO) {
            val dir = File(ctx.cacheDir, "export").apply { mkdirs() }
            val safe = v.name.replace(Regex("[^\\p{L}\\p{N}_-]+"), "_").trim('_').ifBlank { "fahrzeug" }
            listOf(
                "$safe-betankungen.csv" to Csv.refuels(repo.db.refuels().getAllRaw().filter { !it.deleted && it.vehicleId == v.id }, byId),
                "$safe-service.csv" to Csv.services(repo.db.services().getAllRaw().filter { !it.deleted && it.vehicleId == v.id }, byId),
                "$safe-kosten.csv" to Csv.expenses(repo.db.expenses().getAllRaw().filter { !it.deleted && it.vehicleId == v.id }, byId),
            ).map { (name, text) ->
                // BOM, damit Excel Umlaute richtig erkennt
                File(dir, name).apply { writeText("\uFEFF" + text) }
            }
        }
        val uris = ArrayList(files.map { AttachmentStore.uri(ctx, it) })
        val send = android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE)
            .setType("text/csv")
            .putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, uris)
            .putExtra(android.content.Intent.EXTRA_SUBJECT, "Fuel & Wrench – ${v.name}")
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(android.content.Intent.createChooser(send, null).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
