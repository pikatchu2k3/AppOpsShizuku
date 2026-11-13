### English

**App Ops Shizuku 1.20.5** — automatic rules now get the permissions they actually need, and the app stops doing shell work on the main thread.

**Fixed**

- **WLAN rules never received their location permission.** The app only asked for `ACCESS_FINE_LOCATION`; since Android 12 the system ignores such a request unless `ACCESS_COARSE_LOCATION` is asked for at the same time. The dialog never appeared and the network name stayed unreadable. Both are now requested together.
- **WLAN rules did not work in the background.** Android hides the network name there without background location. When a WLAN rule is saved, the app grants itself `ACCESS_BACKGROUND_LOCATION` through Shizuku (best effort). Without a WLAN rule the app still holds no location at all — and it still has no internet access.
- **Hang when saving or toggling a rule.** `AutoRuleManager.evaluate` ran on the main thread (shell calls, up to 3 s waiting for Shizuku). It now runs on a background thread.
- Dark-theme defaults for 19 colours, `isPackageSuspended` guarded to API 29, `longVersionCode` via `PackageInfoCompat` — lint from 27 down to 3 findings (the remaining ones sit in older code and are untouched here).

**Less shell load**

- The drift check runs at most every 10 minutes instead of on every return to the app list (one shell call per plain AppOp in the profile).
- "Stop only" mode: without usage access the `dumpsys` fallback is polled every 15 s instead of every 3 s.

**Honest limits**

- **No device run.** Whether the location dialog now appears when creating a WLAN rule, and whether the background location can be granted through Shizuku, has to be confirmed on the phone.
- The Android behaviour (FINE requires COARSE from API 31) is checked against the documentation, not measured here.

**Tests:** 345 unit tests, 0 failures, 1 skipped (44 test classes).

Download: https://github.com/pikatchu2k3/AppOpsShizuku/releases/latest — this repository is **private**; for updates through [Obtainium](https://github.com/ImranR98/Obtainium) you need a GitHub token with read access.

### Deutsch

**App Ops Shizuku 1.20.5** — Automatik-Regeln bekommen die Rechte, die sie wirklich brauchen, und die App erledigt keine Shell-Arbeit mehr im Haupt-Thread.

**Behoben**

- **WLAN-Regeln bekamen nie ihre Standortberechtigung.** Die App fragte nur `ACCESS_FINE_LOCATION` an; ab Android 12 ignoriert das System eine solche Anfrage ohne gleichzeitiges `ACCESS_COARSE_LOCATION`. Der Dialog kam nie, der Netznamen blieb unlesbar. Jetzt werden beide zusammen angefragt.
- **WLAN-Regeln griffen im Hintergrund nicht.** Dort verbirgt Android den Netznamen ohne Hintergrund-Standort. Beim Speichern einer WLAN-Regel erteilt sich die App `ACCESS_BACKGROUND_LOCATION` per Shizuku (Best-Effort). Ohne WLAN-Regel hält die App weiterhin gar keinen Standort — und sie hat weiterhin keinen Internetzugriff.
- **Hänger beim Speichern oder Umschalten einer Regel.** `AutoRuleManager.evaluate` lief im Haupt-Thread (Shell-Aufrufe, bis zu 3 s Warten auf Shizuku). Läuft jetzt im Hintergrund-Thread.
- Standardwerte für 19 Farben der dunklen Palette, `isPackageSuspended` mit API-29-Prüfung, `longVersionCode` über `PackageInfoCompat` — Lint von 27 auf 3 Befunde (die restlichen liegen in älterem, hier nicht angefasstem Code).

**Weniger Shell-Last**

- Der Abweichungs-Check läuft höchstens alle 10 Minuten statt bei jeder Rückkehr zur App-Liste (je reiner AppOp im Profil ein Shell-Aufruf).
- „Nur beenden": ohne Nutzungszugriff wird der `dumpsys`-Rückfall alle 15 s statt alle 3 s abgefragt.

**Ehrliche Grenzen**

- **Kein Lauf auf einem Gerät.** Ob der Standort-Dialog beim Anlegen einer WLAN-Regel jetzt erscheint und ob sich der Hintergrund-Standort per Shizuku erteilen lässt, muss am Telefon bestätigt werden.
- Das Android-Verhalten (FINE braucht ab API 31 COARSE) ist gegen die Dokumentation geprüft, nicht hier gemessen.

**Tests:** 345 Unit-Tests, 0 Fehler, 1 übersprungen (44 Testklassen).

Download: https://github.com/pikatchu2k3/AppOpsShizuku/releases/latest — dieses Repository ist **privat**; für Updates über [Obtainium](https://github.com/ImranR98/Obtainium) ist ein GitHub-Token mit Lesezugriff nötig.

<sub>Paket `de.minipc.appopsshizuku` · versionCode 45 · debug-signiert (updatefähig über 1.20.4) · targetSdk 35 · Lizenz GPL-3.0</sub>
