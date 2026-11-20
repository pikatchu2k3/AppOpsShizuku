### English

**App Ops Shizuku 1.20.8** — backups are faster and end up where you can find them, the *Blocked* filter shows what it says, and a new error log helps to report problems.

**Fixed: backup location**

*Export* used to write `privacy_profiles.json` into the app's private storage. Without root you could not reach that file, and uninstalling the app deleted it. *Export* now opens the system save dialog (suggested name `appopsshizuku-backup-<date>.json`), so you choose the place, e.g. *Downloads*. "Creating backup …" appears right away, because reading all apps through Shizuku can take a while. Success messages appear under the buttons in *Settings* and disappear after 8 seconds; errors stay until you tap them.

**Faster export:** the export used to run two Shizuku commands per app, one after the other (about 750 for 377 apps). It now reads the standby buckets of all apps with one command and the frozen state directly from Android without any shell command, so the export needs three commands regardless of how many apps are installed. Devices that do not support the combined query fall back to single queries, four at a time.

**Fixed: filter *Blocked***

The filter showed almost every app, including apps marked *Unchanged*. It counted every `Uid mode: …: ignore` line from `cmd appops get`, and Android writes such a line for every permission an app was never granted. The filter now uses the same basis as the status line on each card: apps with at least one permission set to *ignore* or *deny* with this app. It works instantly, without scanning every app.

**New: error log**

- Records crashes, failed Shizuku commands and internal errors, only on the device (about 200 KB at most; the same error is written once per 10 minutes).
- *Tools → Diagnostics → Error log*: number of entries, the latest lines, *Share error report*, *Save as file*, *Clear*. The report contains app version, device model, Android version, the last diagnostics result and the log, including the package names of apps where something failed.
- After a crash or an error a notification (channel *Errors*) offers *Share* and *Save*; after a crash the app also offers to open Diagnostics on the next start.
- Still **no internet permission**: nothing is sent automatically, sharing and saving go through the system dialogs.

**Also**

- Bug report and feature request forms on GitHub (*Issues → New issue*), in German and English.
- README split into English (`README.md`) and German (`README.de.md`) with a language switch at the top, and completed with features that were missing from it.

**Honest limits**

- **No device run.** The combined standby query, the save dialog, the auto-hiding message, the new filter, the error notification and the crash dialog were built and checked in code and unit tests, not on a phone.
- The *Blocked* filter now only knows what was blocked **with this app**. A permission you denied in Android's own settings does not put an app into this filter.

**Tests:** 358 unit tests in 46 classes, including 7 new ones for the error log and 4 for the faster export.

Download: https://github.com/pikatchu2k3/AppOpsShizuku/releases/latest

### Deutsch

**App Ops Shizuku 1.20.8** — Sicherungen sind schneller und landen dort, wo du sie findest, der Filter *Blockiert* zeigt, was er sagt, und ein neues Fehlerprotokoll hilft beim Melden von Problemen.

**Behoben: Speicherort der Sicherung**

*Exportieren* schrieb `privacy_profiles.json` bisher in den privaten Speicher der App. Ohne Root kam man an die Datei nicht heran, und beim Deinstallieren war sie weg. Jetzt öffnet *Exportieren* den Speichern-Dialog des Systems (Vorschlag `appopsshizuku-backup-<Datum>.json`), du wählst den Ort, z. B. *Downloads*. Sofort erscheint „Sicherung wird erstellt …“, denn das Einlesen aller Apps über Shizuku kann dauern. Erfolgsmeldungen stehen unter den Knöpfen in den *Einstellungen* und verschwinden nach 8 Sekunden; Fehler bleiben, bis du sie antippst.

**Schnellerer Export:** Der Export lief bisher mit zwei Shizuku-Befehlen je App, nacheinander (bei 377 Apps rund 750). Jetzt liest er die Standby-Stufen aller Apps mit einem Befehl und den Eingefroren-Zustand direkt von Android, ganz ohne Shell-Befehl. Damit braucht der Export drei Befehle, egal wie viele Apps installiert sind. Geräte, die die Sammelabfrage nicht kennen, fragen einzeln ab, vier gleichzeitig.

**Behoben: Filter *Blockiert***

Der Filter zeigte fast jede App, auch solche mit *Unverändert*. Er zählte jede Zeile `Uid mode: …: ignore` aus `cmd appops get`, und die schreibt Android für jede Berechtigung, die eine App nie bekommen hat. Jetzt nutzt der Filter dieselbe Grundlage wie die Statuszeile auf der Karte: Apps, bei denen mindestens eine Berechtigung mit dieser App auf *Ignorieren* oder *Verweigern* steht. Er wirkt sofort, ohne jede App abzufragen.

**Neu: Fehlerprotokoll**

- Zeichnet Abstürze, fehlgeschlagene Shizuku-Befehle und interne Fehler auf, nur auf dem Gerät (höchstens etwa 200 KB; derselbe Fehler wird einmal pro 10 Minuten geschrieben).
- *Werkzeuge → Diagnose → Fehlerprotokoll*: Anzahl der Einträge, die neuesten Zeilen, *Fehlerbericht teilen*, *Als Datei speichern*, *Leeren*. Der Bericht enthält App-Version, Gerätemodell, Android-Version, das letzte Diagnose-Ergebnis und das Protokoll, auch die Paketnamen der Apps, bei denen etwas fehlschlug.
- Nach einem Absturz oder Fehler bietet eine Benachrichtigung (Kanal *Fehler*) *Teilen* und *Speichern* an; nach einem Absturz fragt die App beim nächsten Start außerdem, ob sie die Diagnose öffnen soll.
- Weiterhin **keine Internet-Berechtigung**: Es wird nichts automatisch gesendet, Teilen und Speichern laufen über die System-Dialoge.

**Außerdem**

- Formulare für Fehlermeldungen und Ideen auf GitHub (*Issues → New issue*), auf Deutsch und Englisch.
- README aufgeteilt in Englisch (`README.md`) und Deutsch (`README.de.md`) mit Sprachumschalter oben, und um fehlende Funktionen ergänzt.

**Ehrliche Grenzen**

- **Kein Gerätelauf.** Die Sammelabfrage der Standby-Stufen, der Speichern-Dialog, das Ausblenden der Meldung, der neue Filter, die Fehler-Benachrichtigung und der Absturz-Dialog sind im Code und mit Unit-Tests geprüft, nicht am Telefon.
- Der Filter *Blockiert* kennt jetzt nur, was **mit dieser App** gesperrt wurde. Eine Berechtigung, die du in den Android-Einstellungen entzogen hast, bringt eine App nicht in diesen Filter.

**Tests:** 358 Unit-Tests in 46 Klassen, davon 7 neue für das Fehlerprotokoll und 4 für den schnelleren Export.

Download: https://github.com/pikatchu2k3/AppOpsShizuku/releases/latest

<sub>Paket `de.minipc.appopsshizuku` · versionCode 48 · debug-signiert (updatefähig über 1.20.7) · targetSdk 35 · Lizenz GPL-3.0</sub>
