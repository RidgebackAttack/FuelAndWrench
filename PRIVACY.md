# Datenschutzerklärung – Fuel & Wrench

Stand: 15. November 2026

Fuel & Wrench ist eine App zum Erfassen von Tankvorgängen, Service-Arbeiten und Reifen. Sie kommt ohne Werbung, Tracking, Analyse-Dienste, Absturzberichte und ohne Google-Dienste aus. Der Entwickler betreibt keinen eigenen Server und erhält keine Daten.

## Welche Daten verarbeitet die App?

- **Fahrzeug-, Tank-, Service- und Reifendaten**, die du selbst eingibst (z. B. Kilometerstand, Menge, Preis, Tankstelle, Werkstatt, Notizen, Fahrzeugfotos).
- **Standort** (nur wenn du „Aktuelle Position“ antippst und die Berechtigung erteilt hast), um die Tankstelle zu erkennen und beim Tankvorgang zu speichern.
- **Zugangsdaten für die Synchronisation** (Server-Adresse, Benutzername, App-Passwort), sofern du die Synchronisation einrichtest.

Alle Daten werden **lokal auf deinem Gerät** gespeichert. Die Android-Datensicherung ist für die App deaktiviert.

## Wohin werden Daten übertragen?

| Empfänger | Daten | Wann | Abschaltbar |
|---|---|---|---|
| Dein eigener WebDAV-/Nextcloud-Server | alle App-Daten und Fahrzeugfotos | bei der Synchronisation | ja – Synchronisation nicht einrichten |
| overpass-api.de (OpenStreetMap) | Koordinaten, IP-Adresse | „Aktuelle Position“ – Tankstellensuche | ja – Einstellungen → Datenschutz |
| Android-Geocoder (auf Geräten mit Google-Diensten: Google) | Koordinaten | Adressermittlung | ja – standardmäßig aus |
| Netzwerk-Ortung des Systems (auf Geräten mit Google-Diensten: Google) | WLAN-/Mobilfunkdaten | Ortung | ja – standardmäßig aus, dann nur GPS |
| tile.openstreetmap.org | angezeigter Kartenausschnitt, IP-Adresse | beim Öffnen der Karte | nur durch Nicht-Nutzung der Karte |

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

Fuel & Wrench records fuel-ups, service work and tyres. It contains no ads, no tracking, no analytics, no crash reporting and no Google services. The developer runs no server and receives no data.

All data is stored **locally on your device** (Android backup disabled). Data is only transmitted to: **your own WebDAV/Nextcloud server** (sync, if set up); **overpass-api.de** (coordinates when tapping "current position" – can be disabled); the **Android geocoder** (coordinates – off by default; Google on devices with Google services); **network location** (off by default; GPS only otherwise); **tile.openstreetmap.org** (visible map area when opening the map).

Permissions: precise location (only for "current position"), internet/network state, notifications (maintenance reminders). Data is kept until you delete it or uninstall the app.
