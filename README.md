# Fuel & Wrench

Android-App zum Erfassen von Betankungen und Kraftstoffverbrauch, mit Synchronisation über WebDAV/Nextcloud.

> [!WARNING]
> **Diese App ist zu 100 % vibecoded!** Geschrieben hat sie komplett eine KI (Claude) nach meinen Wünschen – ich selbst
> könnte so etwas nicht programmieren. Nutzung auf eigene Gefahr, regelmäßige Sicherungen sind eine gute Idee.
> [Die Geschichte dahinter](#die-geschichte-dahinter) steht weiter unten.

## Funktionen
- Mehrere Fahrzeuge mit Foto, Kennzeichen, Standard-Kraftstoff, Tankinhalt
- Betankungen: Datum, Kilometerstand, Menge, Preis (€/l und gesamt, rechnet gegenseitig), voll/teil, Kraftstoffart
- Tankstelle: Betreiber (Aral, Shell, …), Adresse, GPS-Position; „Aktuelle Position“ erkennt die nächste Tankstelle über OpenStreetMap
- Bedingungen: Standheizung, Anhänger, Dachbox, Klimaanlage; Fahrstil; Strecke; Reifentyp
- Verbrauch nach Voll-zu-Voll-Methode, Kosten pro 100 km, Gesamtkosten
- Wartungserinnerungen nach Kilometern und/oder Zeit (Vorwarnung + Fälligkeit als Benachrichtigung)
- Sonstige Kosten (Versicherung, Steuer, Parken, Maut …), Belege als Foto/PDF an Service und Kosten
- Service-Historie mit Filter (mehrere Kategorien, auch „ohne“), Vorschau der Belege; HU/TÜV mit Ergebnis und Prüfbericht setzt die nächste HU
- Fahrzeug-Steckbrief (Ölsorte, Reifendruck, Teilenummern, FIN, HSN/TSN, eigene Felder) – antippen zum Kopieren
- Ölnachfüllungen mit Ölverbrauch (l/1000 km seit Ölwechsel, pro Jahr)
- Saisonkennzeichen (Erinnerungen pausieren außerhalb), Gesamtkosten pro km und Monat
- Tanken im Ausland in allen europäischen Währungen (EZB-Kurs), „Lohnt sich der Umweg?“ bei den Spritpreisen, CSV-Export pro Fahrzeug
- Datenpflege: Datenprüfung auf Tippfehler, Speicher aufräumen, Sync-Protokoll, Absturzberichte nur lokal
- Statistik: Kosten pro Monat/Jahr, Literpreis-Verlauf, Verbrauch nach Reifen, Strecke und Fahrstil
- Beleg-Scan per Kamera (Tesseract, offline auf dem Gerät)
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

Lokal bauen: `git submodule update --init`, dann `gradle assembleRelease` (ohne `SIGNING_PASSWORD` mit Debug-Schlüssel).
Die Texterkennung (Tesseract4Android 4.9.0 mit Tesseract, Leptonica, libjpeg, libpng) wird aus dem Submodul
`external/Tesseract4Android` mit dem Android NDK gebaut (NDK 27.2.12479018, CMake 3.22.1).

## Lizenz
GPL-3.0-or-later, siehe [LICENSE](LICENSE). Verwendete Bibliotheken stehen überwiegend unter der Apache License 2.0
(Übersicht in der App unter Einstellungen → Über & Lizenzen); Tesseract4Android bringt Leptonica, libjpeg und libpng mit.
Karten- und Tankstellendaten © OpenStreetMap-Mitwirkende (ODbL).

Diese App wurde mit KI-Unterstützung (Claude, Anthropic) entwickelt.

## Handbuch
[Deutsch](docs/manual/de.md) · [English](docs/manual/en.md) · [Dansk](docs/manual/da.md) – mit Screenshots (erfundene Beispieldaten).

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

## Die Geschichte dahinter

<details>
<summary>Warum es diese App gibt (aufklappen)</summary>

Die App, die ich haben wollte – kein Tracking, keine Werbung, Sync auf den eigenen WebDAV-Server –, gab es nicht.
Als Sportler saß ich nach einem Unfall eine Zeit lang im Rollstuhl, hatte viel Zeit und noch mehr Langeweile – ich
brauchte Ablenkung! Und wie schwer kann es schon sein, eine App zu programmieren?!

Die äußerst erschütternde Antwort: ziemlich schwer.

Nachdem ich dem Rollstuhl entflohen war, schlief das Projekt ein. Jahre später fand ich die Reste meines Experiments
wieder. Inzwischen gibt es KI, und ich ließ meine Idee und meine Anforderungen und Wünsche von einer KI begutachten.
So wurde aus meinen kläglichen Versuchen diese App.

Ein Profi bräuchte dafür wahrscheinlich mindestens ein Jahr in Vollzeit. Ich habe nur leicht verwirrt Claude mit
Wünschen bombardiert – und wäre nicht im Ansatz in der Lage, etwas zu schreiben, das auch nur ein wenig so aussieht
wie diese App.

</details>
