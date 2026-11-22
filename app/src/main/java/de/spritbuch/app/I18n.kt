package de.spritbuch.app

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import java.util.Locale

/** Übersetzter Text; funktioniert überall (auch außerhalb von Compose). */
fun str(@StringRes id: Int, vararg args: Any): String =
    if (args.isEmpty()) App.instance.getString(id) else App.instance.getString(id, *args)

/** Text mit Einzahl/Mehrzahl („1 Betankung“, „3 Betankungen“); ohne weitere Werte wird die Anzahl eingesetzt. */
fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): String =
    App.instance.resources.getQuantityString(id, count, *(if (args.isEmpty()) arrayOf<Any>(count) else args))

/** Sprache der App (berücksichtigt die Sprachwahl pro App ab Android 13). */
fun appLocale(): Locale = App.instance.resources.configuration.locales[0] ?: Locale.getDefault()

/**
 * Gespeicherte Auswahlwerte (Kraftstoff, Fahrstil, Kategorie …) bleiben als deutsche Schlüssel in der
 * Datenbank und im Sync, damit Geräte mit unterschiedlicher Sprache dieselben Daten teilen.
 * Angezeigt wird die Übersetzung.
 */
object Labels {
    private val ids: Map<String, Int> by lazy {
        mapOf(
            "Strom" to R.string.opt_fuel_electricity,
            "Premium-Diesel" to R.string.opt_fuel_premium_diesel,
            "Standheizung" to R.string.opt_extra_heater,
            "Anhänger" to R.string.opt_extra_trailer,
            "Dachbox/Fahrradträger" to R.string.opt_extra_roofbox,
            "Klimaanlage" to R.string.opt_extra_ac,
            "Sparsam" to R.string.opt_style_eco,
            "Normal" to R.string.opt_style_normal,
            "Zügig" to R.string.opt_style_brisk,
            "Sportlich" to R.string.opt_style_sporty,
            "Stadt" to R.string.opt_route_city,
            "Landstraße" to R.string.opt_route_country,
            "Autobahn" to R.string.opt_route_motorway,
            "Gemischt" to R.string.opt_route_mixed,
            "Sommerreifen" to R.string.opt_tire_summer,
            "Fahrzeugschein" to R.string.opt_doc_registration,
            "Fahrzeugbrief" to R.string.opt_doc_title,
            "Versicherungskarte" to R.string.opt_doc_insurance,
            "TÜV-Bericht" to R.string.opt_doc_inspection,
            "Winterreifen" to R.string.opt_tire_winter,
            "Ganzjahresreifen" to R.string.opt_tire_allseason,
            "Auto" to R.string.opt_vehicle_car,
            "Motorrad" to R.string.opt_vehicle_motorcycle,
            "Wartung" to R.string.opt_cat_maintenance,
            "Reparatur" to R.string.opt_cat_repair,
            "Reifen" to R.string.opt_cat_tires,
            "HU/AU" to R.string.opt_cat_inspection,
            "Pflege" to R.string.opt_cat_care,
            "Zubehör" to R.string.opt_cat_accessories,
            "Sonstiges" to R.string.opt_cat_other,
            "Freie Tankstelle" to R.string.opt_brand_independent,
            "Verbrenner" to R.string.opt_drive_ice,
            "Hybrid" to R.string.opt_drive_hybrid,
            "Plug-in-Hybrid" to R.string.opt_drive_phev,
            "Elektro" to R.string.opt_drive_ev,
            "Zuhause" to R.string.opt_charge_home,
            "AC" to R.string.opt_charge_ac,
            "DC" to R.string.opt_charge_dc,
            "Arbeitgeber" to R.string.opt_charge_work,
            "Versicherung" to R.string.opt_exp_insurance,
            "Steuer" to R.string.opt_exp_tax,
            "Parken" to R.string.opt_exp_parking,
            "Maut" to R.string.opt_exp_toll,
            "Wäsche" to R.string.opt_exp_wash,
            "Finanzierung" to R.string.opt_exp_financing,
            "Gebühren" to R.string.opt_exp_fees,
            "V" to R.string.pos_front,
            "H" to R.string.pos_rear,
            "VL" to R.string.pos_front_left,
            "VR" to R.string.pos_front_right,
            "HL" to R.string.pos_rear_left,
            "HR" to R.string.pos_rear_right,
        )
    }

    fun of(key: String): String = ids[key]?.let { str(it) } ?: key
}

/** Anzeigetext für einen gespeicherten Auswahlwert. */
fun lbl(key: String): String = Labels.of(key)

/** Liste aus einem Text mit „|“-Trennern (für Vorschlagslisten). */
fun strList(@StringRes id: Int): List<String> = str(id).split("|").map { it.trim() }.filter { it.isNotEmpty() }
