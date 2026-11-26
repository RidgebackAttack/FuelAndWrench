package de.spritbuch.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Calendar
import java.util.Random

/**
 * Erzeugt die Screenshots für das Handbuch (docs/manual) – mit erfundenen Beispieldaten, ohne echte
 * Kennzeichen, Adressen oder Standorte. Läuft nur mit `gradle :app:testReleaseUnitTest -Pmanual
 * --tests '*ManualScreenshots*'`; Bilder landen in app/build/manual/<sprache>/.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ManualScreenshots {
    @get:Rule val rule = createEmptyComposeRule()
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val db get() = ctx.repo.db
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var dir: File
    private val failed = mutableListOf<String>()

    @After fun close() {
        scenario?.close()
    }

    @Test @Config(qualifiers = "de-w393dp-h852dp-xhdpi") fun german() = shoot("de")
    @Test @Config(qualifiers = "en-w393dp-h852dp-xhdpi") fun english() = shoot("en")
    @Test @Config(qualifiers = "da-w393dp-h852dp-xhdpi") fun danish() = shoot("da")

    private fun s(id: Int) = ctx.getString(id)

    private fun shoot(lang: String) {
        assumeTrue(System.getProperty("fw.manual") == "true")
        dir = File(System.getProperty("fw.manualDir") ?: "build/manual", lang).apply { mkdirs() }
        DemoData.seed(db)
        scenario = ActivityScenario.launch(Intent(ctx, MainActivity::class.java))
        waitText(DemoData.CAR_NAME)

        step("01-vehicles") { }
        step("02-overview") { go("vehicle/${DemoData.CAR}"); waitText(s(R.string.tab_overview)) }
        step("03-overview-more") { click(s(R.string.tab_overview)); scrollTo(s(R.string.specs)) }
        step("04-refuels") { click(s(R.string.tab_refuels)); waitText("2026", true) }
        step("05-service") { click(s(R.string.tab_service)); waitText(s(R.string.filter_all)) }
        step("06-costs") { click(s(R.string.tab_costs)) }
        step("07-refuel-new") { go("refuelEdit/${DemoData.CAR}/new"); waitText(s(R.string.scan_receipt)) }
        step("08-odometer-prompt", idle = false) {
            rule.onAllNodes(hasText(s(R.string.scan_receipt), substring = true) and hasClickAction()).onFirst()
                .performSemanticsAction(SemanticsActions.OnClick)
            // Auf das Dialogfenster warten; nicht auf „idle“, das ein blinkender Cursor verhindern kann
            val deadline = System.currentTimeMillis() + 10_000
            while (org.robolectric.shadows.ShadowDialog.getLatestDialog()?.isShowing != true && System.currentTimeMillis() < deadline) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
            }
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(600))
            dialogShot = true
        }
        step("09-refuel-foreign") { go("refuelEdit/${DemoData.CAR}/${DemoData.DK_REFUEL}"); waitText(s(R.string.fx_currency)) }
        step("10-service-hu") { go("serviceEdit/${DemoData.CAR}/${DemoData.HU}"); waitText(s(R.string.hu_result)) }
        step("11-tire-set") { go("tireSetEdit/${DemoData.CAR}/${DemoData.SUMMER}"); scrollTo(s(R.string.tire_dot_title)) }
        step("12-stats") { go("stats/${DemoData.CAR}"); waitText("2026", true) }
        step("13-stats-total") { scrollTo(s(R.string.total_cost_title)) }
        step("14-specs") { go("specs/${DemoData.CAR}"); waitText(s(R.string.specs_order)) }
        step("15-vehicle-bike") { go("vehicle/${DemoData.BIKE}"); waitText(DemoData.BIKE_NAME) }
        step("16-vehicle-edit") { go("vehicleEdit/${DemoData.BIKE}"); scrollTo(s(R.string.reg_title)) }
        step("17-settings") { go("settings"); waitText(s(R.string.set_maintenance)) }
        step("18-maintenance") { go("settings/maintenance"); waitText(s(R.string.check_title)) }
        step("19-checks") { go("checks"); waitText(s(R.string.check_title)) }

        if (failed.isNotEmpty()) println("Handbuch-Screenshots ohne Ergebnis ($lang): $failed")
    }

    /** Ein Bild; schlägt ein Schritt fehl, fehlt nur dieses Bild. */
    private fun step(name: String, idle: Boolean = true, action: () -> Unit) {
        val ok = runCatching { action(); if (idle) rule.waitForIdle(); capture(name) }
        ok.exceptionOrNull()?.let { failed += name; println("Screenshot $name: ${it.message}") }
    }

    private fun go(route: String) {
        rule.runOnUiThread { NavHook.get()!!.navigate(route) }
        rule.waitForIdle()
        // Übergangsanimationen auslaufen lassen
        rule.mainClock.advanceTimeBy(600)
        rule.waitForIdle()
    }

    /** Antippen über die Semantik statt über Touch-Ereignisse (die gehen unter Robolectric bei Tabs daneben). */
    private fun click(text: String) {
        rule.onAllNodes(hasText(text, substring = true) and hasClickAction()).onFirst().performSemanticsAction(SemanticsActions.OnClick)
        if (rule.mainClock.autoAdvance) rule.waitForIdle()
        rule.mainClock.advanceTimeBy(400)
    }

    /** Zu einem Text scrollen: in normalen Spalten direkt, in Listen über die Listen-Scrollaktion. */
    private fun scrollTo(text: String) {
        rule.waitForIdle()
        if (runCatching { rule.onAllNodesWithText(text, substring = true).onFirst().performScrollTo() }.isSuccess) return
        // nur senkrechte Listen – sonst blättert die Suche durch die Reiter (HorizontalPager)
        val lists = rule.onAllNodes(hasScrollToNodeAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
        val n = lists.fetchSemanticsNodes().size
        for (i in 0 until n) {
            if (runCatching { lists[i].performScrollToNode(hasText(text, substring = true)) }.isSuccess) { rule.waitForIdle(); return }
        }
        // Letzter Weg: senkrecht scrollbare Listen schrittweise weiterschieben, bis der Text erscheint
        val vertical = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
        for (i in 0 until vertical.fetchSemanticsNodes().size) {
            repeat(15) {
                if (rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()) {
                    runCatching { rule.onAllNodesWithText(text, substring = true).onFirst().performScrollTo() }
                    rule.waitForIdle()
                    return
                }
                runCatching { vertical[i].performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 600f) } }
                rule.waitForIdle()
            }
        }
        val info = vertical.fetchSemanticsNodes().map { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.let { r -> "${r.value()}/${r.maxValue()}" } }
        val texts = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)).fetchSemanticsNodes().take(40)
            .map { it.config.getOrNull(SemanticsProperties.Text)?.joinToString() }
        error("nicht gefunden: $text – senkrecht: $info, Listen: $n, sichtbar: $texts")
    }

    private fun waitText(text: String, substring: Boolean = false) = rule.waitUntil(10_000) {
        rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
    }

    /** Nächste Aufnahme zeigt einen Dialog: Fenster des Dialogs über den abgedunkelten Bildschirm legen. */
    private var dialogShot = false

    private fun captureDialog(): Bitmap? {
        val d = org.robolectric.shadows.ShadowDialog.getLatestDialog() ?: return null
        var out: Bitmap? = null
        scenario!!.onActivity { a ->
            val base = a.window.decorView
            val dv = d.window?.decorView ?: return@onActivity
            if (dv.width == 0 || dv.height == 0) return@onActivity
            val b = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            base.draw(c)
            c.drawColor(android.graphics.Color.argb(110, 0, 0, 0))
            c.save()
            c.translate((base.width - dv.width) / 2f, (base.height - dv.height) / 2f)
            dv.draw(c)
            c.restore()
            out = b
        }
        return out
    }

    private fun capture(name: String) {
        if (dialogShot) {
            dialogShot = false
            captureDialog()?.let { bmp -> File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }; return }
        }
        val bmp = runCatching { rule.onAllNodes(isRoot()).onLast().captureToImage().asAndroidBitmap() }.getOrElse {
            // Ersatz: Fensterinhalt direkt zeichnen
            var out: Bitmap? = null
            scenario!!.onActivity { a ->
                val v = a.window.decorView
                out = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { b -> v.draw(Canvas(b)) }
            }
            out!!
        }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

/** Erfundene, aber realistische Beispieldaten: ein Diesel-Kombi und ein Motorrad mit Saisonkennzeichen. */
object DemoData {
    const val CAR = "demo-car"
    const val BIKE = "demo-bike"
    const val CAR_NAME = "Kombi"
    const val BIKE_NAME = "Naked Bike"
    const val DK_REFUEL = "demo-dk"
    const val HU = "demo-hu"
    const val SUMMER = "demo-summer"

    private fun ts(y: Int, m: Int, d: Int) = Calendar.getInstance().apply { clear(); set(y, m - 1, d, 12, 0) }.timeInMillis

    fun seed(db: AppDatabase) = runBlocking {
        val rnd = Random(42)
        val specs = Specs.encode(
            listOf(
                SpecField("oil_spec", "5W-30, Longlife-04"),
                SpecField("oil_capacity", "6,5 l"),
                SpecField("tire_size_front", "225/55 R17"),
                SpecField("tire_pressure_front", "2,4 bar"),
                SpecField("tire_pressure_rear", "2,6 bar"),
                SpecField("oil_filter", "OX 123 D"),
                SpecField("vin", "WDEMO0000X0000000"),
                SpecField(Specs.CUSTOM, "Box 14", "Reserverad-Schloss"),
            ),
        )
        db.vehicles().upsert(
            Vehicle(
                id = CAR, name = CAR_NAME, defaultFuelType = "Diesel", tankCapacity = 70.0, initialOdometer = 118_000,
                specs = specs, specsUpdatedAt = 1,
            ),
        )
        db.vehicles().upsert(
            Vehicle(
                id = BIKE, name = BIKE_NAME, vehicleType = "Motorrad", defaultFuelType = "Premium", tankCapacity = 17.4,
                initialOdometer = 21_000, registration = "03-10",
            ),
        )

        // Kombi: etwa alle drei Wochen tanken, gut 6 l/100 km
        val brands = listOf("Aral", "Shell", "Esso", "JET", "TotalEnergies")
        var odo = 118_000
        var t = ts(2024, 6, 3)
        var n = 0
        while (t < ts(2026, 11, 20)) {
            val amount = 48.0 + rnd.nextInt(120) / 10.0
            odo += (amount / (6.1 + rnd.nextInt(8) / 10.0) * 100).toInt()
            val price = 1.55 + rnd.nextInt(30) / 100.0
            val id = if (n == 36) DK_REFUEL else "car-$n"
            val r = if (n == 36) {
                // Urlaubsfahrt: in Dänemark getankt, in DKK bezahlt
                val dkk = (amount * 13.39).let { Math.round(it * 100) / 100.0 }
                Refuel(
                    id = id, vehicleId = CAR, timestamp = t, odometer = odo, amount = amount, totalPrice = dkk / 7.4601,
                    fuelType = "Diesel", stationBrand = "Q8", payCurrency = "DKK", payTotal = dkk, fxRate = 7.4601,
                )
            } else {
                Refuel(
                    id = id, vehicleId = CAR, timestamp = t, odometer = odo, amount = amount,
                    totalPrice = Math.round(amount * price * 100) / 100.0, fuelType = "Diesel", stationBrand = brands[n % brands.size],
                )
            }
            db.refuels().upsert(r)
            t += (17 + rnd.nextInt(10)) * 24L * 3600 * 1000
            n++
        }

        // Motorrad: nur in der Saison März–Oktober
        var bodo = 21_000
        n = 0
        for (year in 2025..2026) {
            var bt = ts(year, 3, 20)
            while (bt < ts(year, 10, 20)) {
                val amount = 11.0 + rnd.nextInt(40) / 10.0
                bodo += (amount / (5.2 + rnd.nextInt(8) / 10.0) * 100).toInt()
                // Ein Tippfehler für die Datenprüfung: mehr getankt als in den Tank passt
                val typo = n == 5
                db.refuels().upsert(
                    Refuel(
                        id = "bike-$n", vehicleId = BIKE, timestamp = bt, odometer = bodo, amount = if (typo) 21.4 else amount,
                        totalPrice = Math.round(amount * (1.92 + rnd.nextInt(15) / 100.0) * 100) / 100.0,
                        fuelType = if (n % 4 == 3) "Super E5" else "Premium", stationBrand = brands[(n + 1) % brands.size],
                    ),
                )
                bt += (9 + rnd.nextInt(12)) * 24L * 3600 * 1000
                n++
            }
        }

        // Service
        fun svc(id: String, v: String, at: Long, km: Int, title: String, cat: String, cost: Double, block: (ServiceEntry) -> ServiceEntry = { it }) =
            runBlocking { db.services().upsert(block(ServiceEntry(id = id, vehicleId = v, timestamp = at, odometer = km, title = title, category = cat, cost = cost, workshop = "Freie Werkstatt"))) }
        svc("s1", CAR, ts(2025, 5, 12), 136_400, "Ölwechsel", "Wartung", 189.0) { it.copy(oilChange = true, oilAmount = 6.5) }
        svc(HU, CAR, ts(2025, 9, 8), 141_900, "HU/TÜV", HU_CATEGORY, 145.5) { it.copy(huResult = HU_OK, workshop = "Prüfstelle") }
        svc("s3", CAR, ts(2026, 3, 18), 151_200, "Bremsbeläge vorne", "Reparatur", 318.0)
        svc("s4", CAR, ts(2026, 4, 2), 152_000, "Radwechsel", "Reifen", 40.0) { it.copy(tireSetId = SUMMER, removedTireSetId = "demo-winter", treadDepth = "6,5") }
        svc("s5", CAR, ts(2026, 6, 20), 156_000, "Öl nachgefüllt", OIL_TOPUP, 14.9) { it.copy(oilAmount = 0.5, workshop = "") }
        svc("s6", CAR, ts(2026, 9, 1), 160_800, "Öl nachgefüllt", OIL_TOPUP, 0.0) { it.copy(oilAmount = 0.4, workshop = "") }
        svc("s7", CAR, ts(2026, 7, 4), 157_500, "Wäsche & Pflege", "Pflege", 24.0) { it.copy(workshop = "") }
        svc("b1", BIKE, ts(2026, 3, 22), bodo - 4200, "Ölwechsel", "Wartung", 95.0) { it.copy(oilChange = true, oilAmount = 3.2) }
        svc("b2", BIKE, ts(2026, 4, 15), bodo - 3800, "HU/TÜV", HU_CATEGORY, 98.0) { it.copy(huResult = HU_MINOR, workshop = "Prüfstelle", note = "Kennzeichenbeleuchtung") }
        svc("b3", BIKE, ts(2026, 5, 30), bodo - 2500, "Kettensatz", "Reparatur", 265.0)

        // Reifen mit Herstellungsdatum (DOT)
        db.tireSets().upsert(TireSet(id = SUMMER, vehicleId = CAR, season = TireSeason.SUMMER, brandFront = "Michelin", modelFront = "Primacy 4", sizeFront = "225/55 R17", brandRear = "Michelin", modelRear = "Primacy 4", sizeRear = "225/55 R17", dotFront = "1223", dotRear = "1223"))
        db.tireSets().upsert(TireSet(id = "demo-winter", vehicleId = CAR, season = TireSeason.WINTER, brandFront = "Continental", modelFront = "WinterContact", sizeFront = "225/55 R17", brandRear = "Continental", modelRear = "WinterContact", sizeRear = "225/55 R17", dotFront = "3818", dotRear = "4019"))
        db.tireSets().upsert(TireSet(id = "demo-bike-tires", vehicleId = BIKE, season = "Ganzjahresreifen", brandFront = "Pirelli", modelFront = "Diablo Rosso IV", sizeFront = "120/70 ZR17", brandRear = "Pirelli", modelRear = "Diablo Rosso IV", sizeRear = "180/55 ZR17", dotFront = "0825", dotRear = "2224"))

        // Erinnerungen und Kosten
        db.reminders().upsert(Reminder(id = "rm1", vehicleId = CAR, title = "Ölwechsel", intervalKm = 30_000, lastDoneKm = 136_400, intervalMonths = 24, lastDoneDate = ts(2025, 5, 12)))
        db.reminders().upsert(Reminder(id = "$CAR-hu", vehicleId = CAR, title = "HU/TÜV", intervalKm = 0, intervalMonths = 24, lastDoneDate = ts(2025, 9, 8)))
        db.reminders().upsert(Reminder(id = "rm3", vehicleId = BIKE, title = "Kette schmieren", intervalKm = 800, lastDoneKm = bodo - 300, warnBeforeKm = 100))
        for (y in 2025..2026) {
            db.expenses().upsert(Expense(id = "ins-$y", vehicleId = CAR, timestamp = ts(y, 1, 2), category = "Versicherung", title = "Kfz-Versicherung", amount = 612.0))
            db.expenses().upsert(Expense(id = "tax-$y", vehicleId = CAR, timestamp = ts(y, 2, 10), category = "Steuer", title = "Kfz-Steuer", amount = 296.0))
            db.expenses().upsert(Expense(id = "bins-$y", vehicleId = BIKE, timestamp = ts(y, 3, 1), category = "Versicherung", title = "Saisonversicherung", amount = 148.0))
        }
        db.expenses().upsert(Expense(id = "park", vehicleId = CAR, timestamp = ts(2026, 7, 6), category = "Parken", title = "Parkhaus Flughafen", amount = 63.0))
    }
}
