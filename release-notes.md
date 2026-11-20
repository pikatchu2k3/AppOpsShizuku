### English

**App Ops Shizuku 1.20.9** — the backup export no longer takes minutes.

**Faster backup export**

*Export* used to run two Shizuku commands per app, one after the other: `am get-standby-bucket <package>` and `dumpsys package <package>`. With 377 installed apps that was about 750 shell processes, and the dialog sat on "Creating backup …" for a long time. Now:

- The standby bucket of all apps is read with a single `cmd activity get-standby-bucket` without a package name — one process for the whole list.
- The frozen state comes from Android itself (`ApplicationInfo.FLAG_SUSPENDED` through the package manager), with no shell command at all. Where the system does not report it, the app falls back to `dumpsys package` for that app only.
- Disabled apps and the battery whitelist were already one query each.

The export therefore needs three Shizuku commands no matter how many apps are installed. On devices that do not answer the combined query, the missing apps are read individually, four at a time — never a few hundred processes at once.

**Honest limits**

- **No device run.** The combined standby query, the package-manager lookup for the frozen state and the fallback are covered by unit tests, not on a phone. The exact behaviour on OnePlus/HyperOS energy savers is still unmeasured.
- The backup contains what the device reports. If a manufacturer answers `get-standby-bucket` in a different format, the value for that app stays empty in the export and is refilled on import.
- Everything from 1.20.8 stays as it was: the save dialog for the backup file, the *Blocked* filter, the error log, both READMEs.

**Tests:** 358 unit tests in 46 classes, including 4 new ones for the batch export.

Download: https://github.com/pikatchu2k3/AppOpsShizuku/releases/latest

### Deutsch

**App Ops Shizuku 1.20.9** — der Export der Sicherung dauert nicht mehr Minuten.

**Schnellerer Export der Sicherung**

*Exportieren* lief bisher mit zwei Shizuku-Befehlen je App, nacheinander: `am get-standby-bucket <Paket>` und `dumpsys package <Paket>`. Bei 377 installierten Apps waren das rund 750 Shell-Prozesse, und der Dialog stand lange auf „Sicherung wird erstellt …“. Jetzt:

- Die Standby-Stufe aller Apps wird mit einem einzigen `cmd activity get-standby-bucket` ohne Paketnamen gelesen — ein Prozess für die ganze Liste.
- Der Eingefroren-Zustand kommt direkt von Android (`ApplicationInfo.FLAG_SUSPENDED` über den PackageManager), ganz ohne Shell-Befehl. Meldet das System ihn nicht, liest die App nur für diese eine App per `dumpsys package` nach.
- Deaktivierte Apps und die Akku-Ausnahme waren schon vorher je eine Abfrage für alle.

Damit braucht der Export drei Shizuku-Befehle, egal wie viele Apps installiert sind. Auf Geräten, die die Sammelabfrage nicht beantworten, werden die fehlenden Apps einzeln gelesen, vier gleichzeitig — nie ein paar hundert Prozesse auf einmal.

**Ehrliche Grenzen**

- **Kein Gerätelauf.** Die Sammelabfrage der Standby-Stufen, der Blick in den PackageManager für den Eingefroren-Zustand und der Rückfall auf Einzelabfragen sind mit Unit-Tests geprüft, nicht am Telefon. Das genaue Verhalten auf OnePlus-/HyperOS-Energiesparern ist weiter ungemessen.
- Die Sicherung enthält, was das Gerät meldet. Antwortet ein Hersteller bei `get-standby-bucket` in einem anderen Format, bleibt der Wert für diese App im Export leer und wird beim Import nachgezogen.
- Alles aus 1.20.8 bleibt, wie es war: Speichern-Dialog für die Sicherungsdatei, Filter *Blockiert*, Fehlerprotokoll, beide READMEs.

**Tests:** 358 Unit-Tests in 46 Klassen, davon 4 neue für den Sammel-Export.

Download: https://github.com/pikatchu2k3/AppOpsShizuku/releases/latest

<sub>Paket `de.minipc.appopsshizuku` · versionCode 49 · debug-signiert (updatefähig über 1.20.8) · targetSdk 35 · Lizenz GPL-3.0</sub>
