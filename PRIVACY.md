# Datenschutzerklärung – Fuel & Wrench

Stand: 25. November 2026

Fuel & Wrench ist eine App zum Erfassen von Betankungen, Service-Arbeiten und Reifen. Sie kommt ohne Werbung, Tracking, Analyse-Dienste und ohne Google-Dienste aus. Abstürze werden nur auf dem Gerät festgehalten und verlassen es nur, wenn du sie selbst teilst. Der Entwickler betreibt keinen eigenen Server und erhält keine Daten.

## Welche Daten verarbeitet die App?

- **Fahrzeug-, Tank-, Service- und Reifendaten**, die du selbst eingibst (z. B. Kilometerstand, Menge, Preis, Tankstelle, Werkstatt, Notizen, Fahrzeugfotos).
- **Standort** (nur wenn du „Aktuelle Position“ antippst und die Berechtigung erteilt hast), um die Tankstelle zu erkennen und bei der Betankung zu speichern.
- **Zugangsdaten für die Synchronisation** (Server-Adresse, Benutzername, App-Passwort), sofern du die Synchronisation einrichtest.

Alle Daten werden **lokal auf deinem Gerät** gespeichert. Die Android-Datensicherung ist für die App deaktiviert.

Die **Texterkennung** von Belegen läuft vollständig auf dem Gerät; dafür werden keine Fotos übertragen. **Belege** (Fotos/PDFs) werden wie die übrigen Daten nur mit deinem eigenen Sync-Server abgeglichen und landen in Backups, die du selbst erstellst. Eine Kamera-Berechtigung fordert die App nicht an – Fotos nimmt die Kamera-App des Systems auf.

## Wohin werden Daten übertragen?

| Empfänger | Daten | Wann | Abschaltbar |
|---|---|---|---|
| Dein eigener WebDAV-/Nextcloud-Server | alle App-Daten und Fahrzeugfotos | bei der Synchronisation | ja – Synchronisation nicht einrichten |
| overpass-api.de (OpenStreetMap) | Koordinaten, IP-Adresse | „Aktuelle Position“ – Tankstellensuche | ja – Einstellungen → Datenschutz |
| nominatim.openstreetmap.org (OpenStreetMap) | Koordinaten, IP-Adresse | „Aktuelle Position“ / „Adresse“ – Adresssuche | ja – gleicher Schalter |
| api.github.com (GitHub) | IP-Adresse | einmal täglich Update-Prüfung, nur bei direkt installierter APK | ja – Einstellungen → Datenschutz |
| Android-Geocoder (auf Geräten mit Google-Diensten: Google) | Koordinaten | Adressermittlung | ja – standardmäßig aus |
| Netzwerk-Ortung des Systems (auf Geräten mit Google-Diensten: Google) | WLAN-/Mobilfunkdaten | Ortung | ja – standardmäßig aus, dann nur GPS |
| tile.openstreetmap.org | angezeigter Kartenausschnitt, IP-Adresse | beim Öffnen der Karte | nur durch Nicht-Nutzung der Karte |
| creativecommons.tankerkoenig.de (Tankerkönig) | auf ca. 1 km gerundete Position, dein API-Schlüssel, IP-Adresse | Spritpreise in der Nähe | ja – nur mit eigenem Schlüssel aktiv |
| data-api.ecb.europa.eu (Europäische Zentralbank) | Währung und Datum, IP-Adresse | Betankung in Fremdwährung – Wechselkurs | ja – Kurs von Hand eintragen bzw. keine Fremdwährung wählen |

Für die genannten Dienste gelten deren eigene Datenschutzbestimmungen (OpenStreetMap Foundation, FOSSGIS e. V. bzw. Google).

## Berechtigungen

- **Standort (genau)**: nur für „Aktuelle Position“ beim Tanken.
- **Internet / Netzwerkstatus**: Synchronisation, Tankstellensuche, Karte.
- **Benachrichtigungen**: Wartungserinnerungen.

## Speicherdauer und Löschung

Daten bleiben gespeichert, bis du sie in der App löschst oder die App deinstallierst. Daten auf deinem Sync-Server verwaltest du selbst.

## Kontakt

Fragen zum Datenschutz: über die Issues des Projekts auf GitHub.

---

# Privacy Policy – Fuel & Wrench (English)

Fuel & Wrench records fuel-ups, service work and tyres. It contains no ads, no tracking, no analytics and no Google services. Crashes are only recorded on the device and leave it only if you share them yourself. The developer runs no server and receives no data.

All data is stored **locally on your device** (Android backup disabled). Data is only transmitted to: **your own WebDAV/Nextcloud server** (sync, if set up); **overpass-api.de** and **nominatim.openstreetmap.org** (coordinates when tapping "current position" or "address" – can be disabled); the **Android geocoder** (coordinates – off by default; Google on devices with Google services); **network location** (off by default; GPS only otherwise); **tile.openstreetmap.org** (visible map area when opening the map); **api.github.com** (daily update check, only for APKs installed directly – can be disabled); **creativecommons.tankerkoenig.de** (position rounded to ~1 km and your API key, only for nearby fuel prices with your own key); **data-api.ecb.europa.eu** (currency and date, only for refuels in a foreign currency). Text recognition of receipts runs entirely on the device.

Permissions: precise location (only for "current position"), internet/network state, notifications (maintenance reminders). Data is kept until you delete it or uninstall the app.
