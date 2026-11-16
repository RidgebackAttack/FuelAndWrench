# Fuel & Wrench

Android-App zum Erfassen von Betankungen und Kraftstoffverbrauch, mit Synchronisation über WebDAV/Nextcloud.

## Funktionen
- Mehrere Fahrzeuge mit Foto, Kennzeichen, Standard-Kraftstoff, Tankinhalt
- Betankungen: Datum, Kilometerstand, Menge, Preis (€/l und gesamt, rechnet gegenseitig), voll/teil, Kraftstoffart
- Tankstelle: Betreiber (Aral, Shell, …), Adresse, GPS-Position; „Aktuelle Position“ erkennt die nächste Tankstelle über OpenStreetMap
- Bedingungen: Standheizung, Anhänger, Dachbox, Klimaanlage; Fahrstil; Strecke; Reifentyp
- Verbrauch nach Voll-zu-Voll-Methode, Kosten pro 100 km, Gesamtkosten
- Wartungserinnerungen nach Kilometern und/oder Zeit (Vorwarnung + Fälligkeit als Benachrichtigung)
- Sonstige Kosten (Versicherung, Steuer, Parken, Maut …), Belege als Foto/PDF an Service und Kosten
- Statistik: Kosten pro Monat/Jahr, Literpreis-Verlauf, Verbrauch nach Reifen, Strecke und Fahrstil
- Beleg- und Tacho-Scan per Kamera (Tesseract, offline auf dem Gerät)
- Backup als ZIP (inkl. Fotos, Belege, CSV-Tabellen) und Wiederherstellen
- Sprachen: Deutsch, Englisch, Dänisch (Texte in `tools/i18n/strings.tsv`, `python3 tools/i18n/gen.py` erzeugt die strings.xml)
- Update-Hinweis für direkt installierte APKs (abschaltbar)
- Sync über WebDAV (Nextcloud): automatisch nach jeder Änderung, beim Öffnen und alle 15 Min.; mehrere Geräte gleichzeitig (Zusammenführung pro Datensatz, ETag-Absicherung)
- Import von Spritmonitor.de-CSV (getestet mit echtem Export) und anderen CSV-Dateien; Zahlencodes von Spritmonitor (Kraftstoff, Reifen, Fahrweise, Strecken) werden beim Import zugeordnet
- Fahrzeuge archivieren (Daten bleiben erhalten, Erinnerungen pausiert)
- Einheiten pro Fahrzeug: km oder Meilen, Liter oder US-/UK-Gallonen, Anzeige als l/100 km oder km/l bzw. mpg, Währung; Umstellung rechnet vorhandene Daten um
- Keine Google-Dienste nötig

## Build & Signatur
Jeder Push auf `main` baut per GitHub Actions eine APK. Der Release-Schlüssel liegt AES-256-verschlüsselt in
`app/release.jks.enc`; das Passwort steht ausschließlich im Repository-Secret `SIGNING_PASSWORD`.
Ohne Secret (z. B. in Forks) wird nur eine Debug-signierte APK als Artefakt gebaut und kein Release veröffentlicht.

Lokal bauen: `gradle assembleRelease` (ohne `SIGNING_PASSWORD` mit Debug-Schlüssel).

## Lizenz
GPL-3.0-or-later, siehe [LICENSE](LICENSE). Verwendete Bibliotheken stehen überwiegend unter der Apache License 2.0
(Übersicht in der App unter Einstellungen → Über & Lizenzen); Tesseract4Android bringt Leptonica, libjpeg und libpng mit.
Karten- und Tankstellendaten © OpenStreetMap-Mitwirkende (ODbL).

Diese App wurde mit KI-Unterstützung (Claude, Anthropic) entwickelt.

## Datenschutz
Siehe [PRIVACY.md](PRIVACY.md).

## Store-Texte
F-Droid/Play-Metadaten im fastlane-Format unter `fastlane/metadata/android/`.

## Neue Version veröffentlichen
1. In `app/build.gradle.kts` `versionCode` und `versionName` erhöhen.
2. Änderungsnotiz `fastlane/metadata/android/{de-DE,en-US}/changelogs/<versionCode>.txt` anlegen (max. 500 Zeichen).
3. Push auf `main` → GitHub baut, signiert und legt Release + Tag `v<versionName>` an.
4. F-Droid erkennt den neuen Tag automatisch (UpdateCheckMode: Tags).

Die Vorlage für den F-Droid-Eintrag (fdroiddata) liegt unter `fdroid/`.
