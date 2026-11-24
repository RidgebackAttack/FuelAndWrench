package de.spritbuch.app

import android.content.Context
import java.util.Calendar

/**
 * Reifenwechsel nach der Faustregel „von O bis O“: ab 1. Oktober Winterreifen, ab Ostersonntag Sommerreifen.
 * Erinnert nur, wenn beide Radsätze vorhanden sind und der falsche montiert ist – nicht bei Ganzjahresreifen
 * oder nur einem Satz. Pro Saison einmal als Benachrichtigung; der Hinweis in der App bleibt, bis der
 * Radwechsel eingetragen oder als erledigt markiert ist.
 */
object TireSeason {
    const val SUMMER = "Sommerreifen"
    const val WINTER = "Winterreifen"

    /** Fällige Umrüstung: [target] = Saison, die drauf soll; [period] = Kennung der Saison (z. B. „W2026“). */
    data class Due(val target: String, val period: String)

    /** Ostersonntag (gregorianisch, Gaußsche Osterformel in der Form von Meeus/Jones/Butcher). */
    fun easter(year: Int): Calendar {
        val a = year % 19
        val b = year / 100
        val c = year % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = ((h + l - 7 * m + 114) % 31) + 1
        return Calendar.getInstance().apply { clear(); set(year, month - 1, day) }
    }

    /** Welche Saison gerade gilt und ihre Kennung: Winter vom 1. Oktober bis vor Ostern, sonst Sommer. */
    fun period(time: Long = now()): Pair<String, String> {
        val c = Calendar.getInstance().apply { timeInMillis = time }
        val y = c.get(Calendar.YEAR)
        return when {
            c.get(Calendar.MONTH) >= Calendar.OCTOBER -> WINTER to "W$y"
            time < easter(y).timeInMillis -> WINTER to "W${y - 1}"
            else -> SUMMER to "S$y"
        }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("tire_season", Context.MODE_PRIVATE)

    /** Reine Entscheidung (testbar): Ist bei diesen Radsätzen und diesem montierten Satz eine Umrüstung fällig? */
    fun decide(sets: List<TireSet>, mountedId: String?, time: Long = now()): Due? {
        val (target, period) = period(time)
        val other = if (target == WINTER) SUMMER else WINTER
        val active = sets.filter { !it.deleted }
        if (active.none { it.season == target } || active.none { it.season == other }) return null
        val mounted = active.firstOrNull { it.id == mountedId } ?: return null
        return if (mounted.season == other) Due(target, period) else null
    }

    suspend fun due(ctx: Context, v: Vehicle): Due? {
        if (v.deleted || v.archived || !Registration.isActive(v)) return null
        val repo = ctx.repo
        val d = decide(repo.db.tireSets().forVehicle(v.id), repo.mountedTireSetId(v.id)) ?: return null
        return if (v.tireSeasonDone == d.period) null else d
    }

    /** Am Fahrzeug speichern, damit es über den Sync auf allen Geräten gilt. */
    suspend fun markDone(ctx: Context, vehicleId: String, d: Due) {
        val repo = ctx.repo
        val v = repo.getVehicle(vehicleId) ?: return
        repo.saveVehicle(v.copy(tireSeasonDone = d.period))
    }

    /** Einmal pro Saison benachrichtigen (aus dem täglichen Hintergrundjob bzw. nach dem Sync). */
    suspend fun notifyIfDue(ctx: Context, v: Vehicle) {
        if (!Prefs(ctx).shows(v.id)) return
        val d = due(ctx, v) ?: return
        val key = "notified_${v.id}_${d.period}"
        if (prefs(ctx).getBoolean(key, false)) return
        prefs(ctx).edit().putBoolean(key, true).apply()
        Notifications.show(ctx, ("tires" + v.id).hashCode(), v.name, text(d))
    }

    fun text(d: Due) = str(if (d.target == WINTER) R.string.tires_due_winter else R.string.tires_due_summer)
}
