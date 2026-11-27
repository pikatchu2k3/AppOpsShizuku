### English

**App Ops Shizuku 1.21.0** — security hardening and a build that works from source (F-Droid).

**Security**

- **Imports are checked and previewed.** A backup file is foreign input. Before anything is stored or applied, the app shows what it contains (apps, behaviour changes, disable/freeze requests, rules) and discards invalid package names, unknown permissions/modes and disable/freeze requests for protected system apps (System UI, Settings, launcher, keyboard, Shizuku, this app). Applying to the device now runs in the background instead of on the UI thread. The "import by intent extra" convenience works in debug builds only.
- **"Reset" never grants.** Choosing "default" on a permission used to *grant* it, and an unknown mode string fell into the same branch. Now: permissions offer *reset* (revoke and clear "don't ask again"), unknown modes do nothing. After blocking, the app checks that Android really stored "don't ask again" and says so if not.
- **Signing key protected.** The key used for patched apps is now encrypted with the Android Keystore (existing keys are migrated in place, so patched apps stay updatable). Cloud backup is switched off, and the key is excluded from device transfer.
- **Trust anchors pinned.** The vault companion app and launcher apps for "only on demand" are remembered with their signing certificate, not just their package name.
- GitHub releases are now real release builds (not debuggable); same signing certificate.

**Faster, more robust**

- Opening an app reads all permissions with **one** query instead of about 30. The app list is cached and rebuilt only when packages change.
- One shell execution path; parallel commands really run in parallel (the shared two-thread pool is gone). Background threads end with their screen.

**More permissions**

- 11 more: Bluetooth connect, phone numbers, make calls, send SMS, legacy storage read/write, and a new group *Special access* (install unknown apps, system settings, exact alarms, full-screen notifications).

**Editions and build**

- **Two editions:** *full* (as before) and *lite* (no location permissions, no Wi-Fi rules).
- Builds from source without prebuilt binaries: the bundled `calcyou.apk` is gone (it was never in the repository), the JDK path is no longer hard-coded, AGP 8.7 / Kotlin 2.0, version catalog, CI, F-Droid metadata (`fastlane/`, `docs/fdroid/`).
- Android 15: the patch service stops cleanly when the system ends its `dataSync` time.

**Honest limits**

- **Not tested on a device.** The 400+ unit tests, lint and release builds of both editions pass; nothing here was run on a phone. In particular the Keystore wrapping, the one-query read path, the package-change reload and the new permissions are device-unverified.
- R8 shrinking exists as an opt-in (`-Pminify=true`) and is off by default for the same reason.
- "Only while in use" still ends the app's process when a permission is revoked (Android behaviour); apps with a running foreground service are not exempt yet.
- F-Droid builds use F-Droid's signing key; switching between F-Droid and GitHub builds needs a reinstall.

Download: https://github.com/pikatchu2k3/AppOpsShizuku/releases/latest

### Deutsch

**App Ops Shizuku 1.21.0** — Sicherheits-Härtung und ein Build, der aus dem Quellcode funktioniert (F-Droid).

**Sicherheit**

- **Importe werden geprüft und vorab angezeigt.** Eine Sicherungsdatei ist fremde Eingabe. Bevor etwas gespeichert oder angewendet wird, zeigt die App, was darin steht (Apps, Verhaltens-Änderungen, Deaktivieren/Einfrieren, Regeln), und verwirft ungültige Paketnamen, unbekannte Berechtigungen/Modi und Deaktivieren/Einfrieren geschützter System-Apps (System-UI, Einstellungen, Launcher, Tastatur, Shizuku, diese App). Das Anwenden auf das Gerät läuft jetzt im Hintergrund statt im UI-Thread. „Import per Intent-Extra“ gibt es nur noch in Debug-Builds.
- **„Zurücksetzen“ erteilt nie.** „default“ bei einer Berechtigung hat sie bisher *erteilt*, ebenso jeder unbekannte Modus-Text. Jetzt: Berechtigungen bieten *reset* (entziehen und „Nicht mehr fragen“ lösen), unbekannte Modi tun nichts. Nach dem Blockieren prüft die App, ob Android „Nicht mehr fragen“ wirklich gespeichert hat, und sagt es, wenn nicht.
- **Signaturschlüssel geschützt.** Der Schlüssel für gepatchte Apps wird mit dem Android-Keystore verschlüsselt (vorhandene Schlüssel werden an Ort und Stelle migriert, gepatchte Apps bleiben aktualisierbar). Cloud-Backup ist aus, der Schlüssel ist von der Geräte-Übertragung ausgenommen.
- **Vertrauensanker gepinnt.** Die Vault-Begleit-App und Starter-Apps für „Nur bei Bedarf“ werden mit ihrem Signaturzertifikat gemerkt, nicht nur mit dem Paketnamen.
- GitHub-Releases sind jetzt echte Release-Builds (nicht debuggable); gleiches Signaturzertifikat.

**Schneller, robuster**

- Beim Öffnen einer App werden alle Berechtigungen mit **einer** Abfrage gelesen statt mit rund 30. Die App-Liste wird gecacht und nur bei Paketänderungen neu gebaut.
- Ein Shell-Ausführungsweg; parallele Befehle laufen wirklich parallel (der gemeinsame Zwei-Thread-Pool entfällt). Hintergrund-Threads enden mit ihrem Bildschirm.

**Mehr Berechtigungen**

- 11 weitere: Bluetooth verbinden, Telefonnummern, Anrufe, SMS senden, alter Speicher lesen/schreiben und die neue Gruppe *Spezialzugriff* (unbekannte Apps installieren, Systemeinstellungen, exakte Alarme, Vollbild-Benachrichtigungen).

**Ausgaben und Build**

- **Zwei Ausgaben:** *full* (wie bisher) und *lite* (keine Standort-Rechte, keine WLAN-Regeln).
- Baut aus dem Quellcode ohne vorkompilierte Binärdateien: die gebündelte `calcyou.apk` entfällt (sie war nie im Repository), der JDK-Pfad ist nicht mehr fest verdrahtet, AGP 8.7 / Kotlin 2.0, Version-Catalog, CI, F-Droid-Metadaten (`fastlane/`, `docs/fdroid/`).
- Android 15: Der Patch-Dienst endet sauber, wenn das System seine `dataSync`-Zeit beendet.

**Ehrliche Grenzen**

- **Nicht auf einem Gerät getestet.** Die über 400 Unit-Tests, Lint und die Release-Builds beider Ausgaben laufen durch; nichts davon lief auf einem Handy. Insbesondere Keystore-Umhüllung, der Ein-Abfrage-Lesepfad, das Neuladen bei Paketänderungen und die neuen Berechtigungen sind am Gerät ungeprüft.
- R8-Verkleinern gibt es als Opt-in (`-Pminify=true`) und ist aus demselben Grund standardmäßig aus.
- „Nur während Nutzung“ beendet beim Entziehen weiterhin den Prozess der App (Android-Verhalten); Apps mit laufendem Vordergrunddienst sind noch nicht ausgenommen.
- F-Droid-Builds nutzen den Schlüssel von F-Droid; ein Wechsel zwischen F-Droid- und GitHub-Build braucht eine Neuinstallation.

Download: https://github.com/pikatchu2k3/AppOpsShizuku/releases/latest
