package de.spritbuch.app

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Calendar

/**
 * Klickt durch die echte App (ohne Emulator): Fahrzeug öffnen, Statistik, Betankung eintragen, Einstellungen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiFlowTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val db get() = ctx.repo.db
    private var scenario: ActivityScenario<MainActivity>? = null

    private fun s(id: Int) = ctx.getString(id)

    private fun ts(year: Int, month: Int) = Calendar.getInstance().apply { set(year, month - 1, 15, 12, 0) }.timeInMillis

    private fun seed(withHistory: Boolean) = runBlocking {
        db.vehicles().upsert(Vehicle(id = "v1", name = "Testwagen", initialOdometer = 1000))
        if (withHistory) {
            db.refuels().upsert(Refuel(id = "r1", vehicleId = "v1", timestamp = ts(2025, 3), odometer = 1500, amount = 40.0, totalPrice = 70.0))
            db.refuels().upsert(Refuel(id = "r2", vehicleId = "v1", timestamp = ts(2025, 9), odometer = 2100, amount = 38.0, totalPrice = 66.0))
            db.refuels().upsert(Refuel(id = "r3", vehicleId = "v1", timestamp = ts(2026, 2), odometer = 2700, amount = 41.0, totalPrice = 72.0))
            db.expenses().upsert(Expense(id = "e1", vehicleId = "v1", timestamp = ts(2025, 6), title = "Versicherung", amount = 300.0))
        }
    }

    private fun launch(intent: Intent = Intent(ctx, MainActivity::class.java)) {
        scenario = ActivityScenario.launch(intent)
    }

    @After fun close() {
        scenario?.close()
    }

    private fun waitForText(text: String, substring: Boolean = false) = rule.waitUntil(10_000) {
        rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
    }

    /** Statistik mit Kosten über mehrere Jahre (hier stürzte 1.6.5 auf älteren Android-Versionen ab). */
    @Test fun statisticsOverSeveralYears() {
        seed(withHistory = true)
        launch()
        waitForText("Testwagen")
        rule.onAllNodesWithText("Testwagen").onFirst().performClick()
        rule.waitUntil(10_000) {
            runCatching { rule.onNodeWithContentDescription(s(R.string.statistics)).fetchSemanticsNode() }.isSuccess
        }
        rule.onNodeWithContentDescription(s(R.string.statistics)).performClick()
        waitForText("2025", substring = true)
    }

    /** Neue Betankung wie aus dem App-Icon-Menü bzw. Widget: Formular ausfüllen und speichern. */
    @Test fun addRefuelThroughForm() {
        seed(withHistory = false)
        launch(
            Intent(ctx, MainActivity::class.java)
                .putExtra(Shortcuts.EXTRA_ACTION, Shortcuts.REFUEL)
                .putExtra(Shortcuts.EXTRA_VEHICLE, "v1"),
        )
        waitForText(s(R.string.refuel_new))
        rule.onNode(hasSetTextAction() and hasText(s(R.string.odometer), substring = true)).performTextInput("1500")
        rule.onNode(hasSetTextAction() and hasText(s(R.string.amount), substring = true)).performTextInput("40")
        rule.onNode(hasSetTextAction() and hasText(s(R.string.total), substring = true)).performTextInput("70")
        rule.onNodeWithContentDescription(s(R.string.save)).performClick()
        rule.waitUntil(10_000) { runBlocking { db.refuels().getAllRaw().isNotEmpty() } }
        val r = runBlocking { db.refuels().getAllRaw().single() }
        assertEquals(1500, r.odometer)
        assertEquals(40.0, r.amount, 0.001)
        assertEquals(70.0, r.totalPrice, 0.001)
    }

    /** Einstellungen öffnen; über die Übersicht ist die Verschlüsselung erreichbar. */
    @Test fun settingsOpen() {
        seed(withHistory = false)
        launch()
        waitForText("Testwagen")
        rule.onNodeWithContentDescription(s(R.string.settings)).performClick()
        // Übersicht → Synchronisierung
        waitForText(s(R.string.set_not_configured))
        rule.onNodeWithText(s(R.string.sync)).performClick()
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText(s(R.string.e2e_title)).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText(s(R.string.e2e_title)).performScrollTo()
    }

    /** Erstes Fahrzeug über das Formular anlegen (ohne Bild). */
    @Test fun createVehicleThroughForm() {
        launch()
        waitForText(s(R.string.vehicle))
        rule.onAllNodesWithText(s(R.string.vehicle)).onFirst().performClick()
        waitForText(s(R.string.vehicle_name_hint))
        rule.onNode(hasSetTextAction() and hasText(s(R.string.vehicle_name_hint), substring = true)).performTextInput("Neuwagen")
        rule.onNodeWithContentDescription(s(R.string.save)).performClick()
        rule.waitUntil(10_000) { runBlocking { db.vehicles().getAllRaw().any { it.name == "Neuwagen" } } }
    }

    /** Reifenwechsel-Hinweis erscheint, wenn der falsche Radsatz montiert ist, und verschwindet mit „Erledigt“. */
    @Test fun tireSeasonHintUntilDone() {
        seed(withHistory = false)
        val (target, _) = TireSeason.period()
        val wrong = if (target == TireSeason.WINTER) TireSeason.SUMMER else TireSeason.WINTER
        runBlocking {
            db.tireSets().upsert(TireSet(id = "t1", vehicleId = "v1", season = wrong))
            db.tireSets().upsert(TireSet(id = "t2", vehicleId = "v1", season = target))
            db.services().upsert(ServiceEntry(id = "s1", vehicleId = "v1", category = "Reifen", title = "Radwechsel", tireSetId = "t1", odometer = 1000))
        }
        launch()
        waitForText("Testwagen")
        rule.onAllNodesWithText("Testwagen").onFirst().performClick()
        val hint = s(if (target == TireSeason.WINTER) R.string.tires_due_winter else R.string.tires_due_summer)
        waitForText(hint)
        rule.onNodeWithText(s(R.string.tires_due_done)).performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText(hint).fetchSemanticsNodes().isEmpty() }
    }
}
