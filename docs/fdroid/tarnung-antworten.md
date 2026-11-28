# Antwortvorlage F-Droid-Review: Reiter „Tarnung“ und Berechtigungen

Zitierfähige Kurzantworten (Stand 1.21.3 / versionCode 53). Die App hat **keine INTERNET-Berechtigung**, keine Werbung, kein Tracking, keine Analytics.

## Reiter „Tarnung“
- **Zweck:** Schutz der Privatsphäre des Geräteeigentümers, z. B. gegenüber Dritten mit Zugriff auf das entsperrte Gerät oder gegenüber Apps, die Gerätemerkmale auslesen.
- **Symbol ausblenden:** Launcher-Symbol der App wird deaktiviert; Start dann über einen frei wählbaren Weg. Rein lokal, jederzeit umkehrbar.
- **Taschenrechner-Tarnung:** Die App tarnt sich als Taschenrechner und öffnet erst nach Eingabe einer Zugangsfolge. Nutzerseitiger Sichtschutz, keine Täuschung von Dritten zu Schaden.
- **Geräte-Spoofing:** Setzt lokal Geräte-/Systemkennungen, damit Apps weniger Gerätemerkmale auslesen können. Keine Netzwerkkommunikation, kein Eingriff in fremde Konten oder Dienste.
- **App-Klone:** Zweite Instanz einer App (Arbeitsprofil/Klon), damit Nutzer Konten oder Berechtigungen getrennt halten können. Es wird kein fremder Code verteilt; geklont wird nur, was bereits auf dem Gerät installiert ist.
- **Keine Anti-Feature-relevante Funktion:**
  - keine Werbung (kein *Ads*)
  - kein Tracking, keine Analytics (kein *Tracking*)
  - keine Netzwerkdienste, keine INTERNET-Berechtigung (kein *NonFreeNet*)
  - keine proprietären Abhängigkeiten; `spooftest` (Play-Services-Fixture) wird per `rm:` aus dem Quellbaum entfernt (kein *NonFreeDep*)
  - alles wirkt nur auf dem eigenen Gerät und auf Wunsch des Nutzers.

## Shizuku als Voraussetzung
- App Ops Shizuku ist ein **Frontend für Shizuku bzw. AppOps**: Es setzt AppOps-Modi und verwandte Einstellungen über die von Shizuku bereitgestellte Schnittstelle.
- **Ohne laufendes Shizuku keine Funktion**; die App verlangt das nicht stillschweigend, sondern zeigt den Status an und führt zur Einrichtung.
- Shizuku ist freie Software (Apache-2.0) und selbst in F-Droid verfügbar, daher keine nicht-freie Abhängigkeit.

## Sensible Berechtigungen
- **QUERY_ALL_PACKAGES:** Kernfunktion. Eine Berechtigungs-App muss alle installierten Apps auflisten, um deren AppOps zu zeigen und zu ändern. Die Liste bleibt lokal.
- **WRITE_SECURE_SETTINGS:** Wird nur gebraucht, um bestimmte Systemeinstellungen (u. a. für Tarnung/Sperrfunktionen) zu schreiben; Erteilung manuell per Shizuku/ADB durch den Nutzer. Nicht abgreifbar ohne dessen ausdrückliche Handlung.
- **PACKAGE_USAGE_STATS:** Zeigt Nutzungsdaten für „Nur bei Bedarf“ und Auswertungen. Auswertung rein lokal, keine Übertragung (es gibt keinen Netzzugriff).
- **Gemeinsames Argument:** Das sind Kernfunktionen einer Berechtigungs-App, keine Datensammlung; ohne INTERNET-Berechtigung können keine Daten das Gerät verlassen.

## Zwei Ausgaben: full / lite
- **full:** mit WLAN-Regeln (WLAN-Name lesen erfordert Standort-Rechte). Enthält die volle Funktionalität.
- **lite:** ohne WLAN-Regeln und ohne jedes Standort-Recht im Manifest.
- **F-Droid baut `full`** (`gradle: [full]`), weil es der funktionsvollständige Build ist; die Standort-Rechte dienen ausschließlich dem Lesen des WLAN-Namens für lokale Regeln, nicht der Ortung oder Übertragung.
- `lite` kann bei Bedarf als zweiter Build-Eintrag (`gradle: [lite]`) ergänzt werden.
