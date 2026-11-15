# Spritbuch

Android-App zum Erfassen von Tankvorgängen und Kraftstoffverbrauch, mit Synchronisation über WebDAV/Nextcloud.

## Funktionen
- Mehrere Fahrzeuge mit Foto, Kennzeichen, Standard-Kraftstoff, Tankinhalt
- Tankvorgänge: Datum, Kilometerstand, Menge, Preis (€/l und gesamt, rechnet gegenseitig), voll/teil, Kraftstoffart
- Tankstelle: Betreiber (Aral, Shell, …), Adresse, GPS-Position; „Aktuelle Position“ erkennt die nächste Tankstelle über OpenStreetMap
- Bedingungen: Standheizung, Anhänger, Dachbox, Klimaanlage; Fahrstil; Strecke; Reifentyp
- Verbrauch nach Voll-zu-Voll-Methode, Kosten pro 100 km, Gesamtkosten
- Wartungserinnerungen nach Kilometern (Vorwarnung + Fälligkeit als Benachrichtigung)
- Sync über WebDAV (Nextcloud): automatisch nach jeder Änderung, beim Öffnen und alle 15 Min.; mehrere Geräte gleichzeitig (Zusammenführung pro Datensatz, ETag-Absicherung)
- Import von CSV-Exporten (Spritmonitor.de und andere): Spalten werden anhand der Kopfzeile erkannt, Duplikate übersprungen
- Fahrzeuge archivieren (Daten bleiben erhalten, Erinnerungen pausiert)
- Einheiten pro Fahrzeug: km oder Meilen, Liter oder US-/UK-Gallonen, Anzeige als l/100 km oder km/l bzw. mpg, Währung; Umstellung rechnet vorhandene Daten um
- Keine Google-Dienste nötig

## Build
Jeder Push auf `main` baut per GitHub Actions eine signierte APK und legt sie unter „Releases“ ab.

Die APK ist mit einem im Repo liegenden Schlüssel signiert (`app/spritbuch.keystore`), damit Updates
sich über die vorhandene Installation installieren lassen. Repo deshalb privat halten.

## Sync-Ordner
```
spritbuch.json      Alle Daten (Fahrzeuge, Tankvorgänge, Erinnerungen)
photos/             Fahrzeugbilder
```
