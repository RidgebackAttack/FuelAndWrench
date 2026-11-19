package de.spritbuch.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * Homescreen-Widget: Verbrauch, Kosten im laufenden Monat und nächste fällige Erinnerung eines Fahrzeugs.
 * „+ Betankung“ öffnet direkt die Eingabe; bei mehreren Fahrzeugen wechselt ⇄ das angezeigte.
 */
class FuelWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        scope.launch {
            try {
                ids.forEach { render(ctx, mgr, it) }
            } finally {
                pending.finish()
            }
        }
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == ACTION_NEXT) {
            val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
            val pending = goAsync()
            scope.launch {
                try {
                    val list = vehicles(ctx)
                    val cur = list.indexOfFirst { it.id == selected(ctx, id) }
                    if (list.isNotEmpty()) select(ctx, id, list[(cur + 1) % list.size].id)
                    render(ctx, AppWidgetManager.getInstance(ctx), id)
                } finally {
                    pending.finish()
                }
            }
            return
        }
        super.onReceive(ctx, intent)
    }

    override fun onDeleted(ctx: Context, ids: IntArray) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        ids.forEach { sp.remove("w$it") }
        sp.apply()
    }

    companion object {
        private const val ACTION_NEXT = "de.spritbuch.app.WIDGET_NEXT"
        private const val PREFS = "widgets"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Alle Widgets neu zeichnen (nach Änderungen an den Daten). */
        fun refresh(ctx: Context) {
            val app = ctx.applicationContext
            val mgr = AppWidgetManager.getInstance(app) ?: return
            val ids = mgr.getAppWidgetIds(ComponentName(app, FuelWidget::class.java))
            if (ids.isEmpty()) return
            scope.launch { ids.forEach { runCatching { render(app, mgr, it) } } }
        }

        private fun selected(ctx: Context, id: Int) =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("w$id", null)

        private fun select(ctx: Context, id: Int, vehicleId: String) =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("w$id", vehicleId).apply()

        /** Aktive, auf diesem Gerät sichtbare Fahrzeuge – zuletzt genutztes zuerst. */
        private suspend fun vehicles(ctx: Context): List<Vehicle> {
            val prefs = Prefs(ctx)
            val db = ctx.repo.db
            val lastUse = db.refuels().lastUse().associate { it.vehicleId to it.lastTime }
            return db.vehicles().getAllRaw().filter { !it.deleted && !it.archived && prefs.shows(it.id) }
                .sortedByDescending { lastUse[it.id] ?: it.updatedAt }
        }

        private fun open(ctx: Context, request: Int, action: String, vehicleId: String?): PendingIntent {
            val i = Intent(ctx, MainActivity::class.java)
                .setAction("de.spritbuch.app.WIDGET.$action.$request")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra(Shortcuts.EXTRA_ACTION, action)
                .apply { if (vehicleId != null) putExtra(Shortcuts.EXTRA_VEHICLE, vehicleId) }
            return PendingIntent.getActivity(ctx, request, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        private suspend fun render(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val views = RemoteViews(ctx.packageName, R.layout.widget)
            val list = vehicles(ctx)
            val v = list.firstOrNull { it.id == selected(ctx, id) } ?: list.firstOrNull()
            if (v == null) {
                views.setTextViewText(R.id.widget_title, "Fuel & Wrench")
                views.setTextViewText(R.id.widget_consumption, str(R.string.widget_no_vehicle))
                views.setTextViewText(R.id.widget_month, "")
                views.setTextViewText(R.id.widget_reminder, "")
                views.setViewVisibility(R.id.widget_add, View.GONE)
                views.setViewVisibility(R.id.widget_next, View.GONE)
                views.setOnClickPendingIntent(R.id.widget_root, open(ctx, id * 10, Shortcuts.VIEW, null))
                mgr.updateAppWidget(id, views)
                return
            }
            val db = ctx.repo.db
            val refuels = db.refuels().forVehicle(v.id)
            val stats = computeStats(refuels, v.batteryCapacity)
            val odo = maxOf(v.initialOdometer, v.manualOdometer, refuels.maxOfOrNull { it.odometer } ?: 0)

            val month = Calendar.getInstance().apply {
                set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val cost = refuels.filter { it.timestamp >= month }.sumOf { it.totalPrice } +
                db.services().forVehicle(v.id).filter { it.timestamp >= month }.sumOf { it.cost } +
                db.expenses().forVehicle(v.id).filter { it.timestamp >= month }.sumOf { it.amount }

            val next = db.reminders().forVehicle(v.id).minByOrNull { it.state(odo).urgency }
            val reminderText = next?.let { r ->
                val st = r.state(odo)
                val kmFirst = st.remainingDays == null || (st.remainingKm != null && st.remainingKm * 1.0 <= st.remainingDays * 50.0)
                "🔧 " + when {
                    st.overdue -> str(R.string.chip_due, r.title)
                    kmFirst && st.remainingKm != null -> str(R.string.chip_due_in, r.title, st.remainingKm.dist(v.distanceUnit))
                    else -> str(R.string.chip_due_in, r.title, plural(R.plurals.pl_days_in, st.remainingDays ?: 0))
                }
            } ?: ""

            views.setTextViewText(R.id.widget_title, v.name)
            views.setTextViewText(
                R.id.widget_consumption,
                stats.avg?.let { "Ø " + Units.consumption(v, v.defaultFuelType, it) } ?: str(R.string.no_consumption_yet),
            )
            views.setTextViewText(R.id.widget_month, str(R.string.widget_month, cost.fmt() + " " + v.currency))
            views.setTextViewText(R.id.widget_reminder, reminderText)
            views.setViewVisibility(R.id.widget_reminder, if (reminderText.isEmpty()) View.GONE else View.VISIBLE)
            // Günstigste Tankstelle in der Nähe (mit Tankerkönig-Schlüssel und bekanntem Standort)
            val cheapest = FuelPrices.typeFor(v.defaultFuelType)?.takeIf { FuelPrices.enabled(ctx) }?.let { type ->
                val loc = LocationHelper.lastKnown(ctx) ?: return@let null
                runCatching { FuelPrices.nearby(ctx, loc.latitude, loc.longitude, type) }.getOrNull()
                    ?.firstOrNull { it.isOpen && it.price != null }
            }
            if (cheapest != null) {
                views.setTextViewText(R.id.widget_prices, "⛽ " + FuelPrices.summary(v.defaultFuelType, cheapest) + " (" + now().time() + ")")
                views.setViewVisibility(R.id.widget_prices, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.widget_prices, View.GONE)
            }
            views.setTextViewText(R.id.widget_add, "+ " + str(if (v.isElectric) R.string.charge else R.string.refuel))
            views.setViewVisibility(R.id.widget_add, View.VISIBLE)
            views.setOnClickPendingIntent(R.id.widget_add, open(ctx, id * 10 + 1, Shortcuts.REFUEL, v.id))
            views.setOnClickPendingIntent(R.id.widget_root, open(ctx, id * 10 + 2, Shortcuts.VIEW, v.id))
            if (list.size > 1) {
                views.setViewVisibility(R.id.widget_next, View.VISIBLE)
                val next = Intent(ctx, FuelWidget::class.java).setAction(ACTION_NEXT)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                views.setOnClickPendingIntent(
                    R.id.widget_next,
                    PendingIntent.getBroadcast(ctx, id, next, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
                )
            } else {
                views.setViewVisibility(R.id.widget_next, View.GONE)
            }
            mgr.updateAppWidget(id, views)
        }
    }
}
