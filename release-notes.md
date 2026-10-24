### English

**App Ops Shizuku 1.19.0** — remove permissions while patching (beta): offline clone, no ad ID, no app list, no location, no phone status, no accounts list.

**New section "Remove permissions (beta)"** in the Stealth tab, right below "Switch off trackers". Six checkboxes, each naming the permissions it removes:

| Checkbox | Permissions removed |
|---|---|
| No internet (offline clone) | `INTERNET` |
| No ad ID | `com.google.android.gms.permission.AD_ID`, `ACCESS_ADSERVICES_*` (Privacy Sandbox) |
| Installed apps not visible | `QUERY_ALL_PACKAGES` |
| No location | `ACCESS_FINE/COARSE/BACKGROUND_LOCATION` |
| No phone status / no phone number | `READ_PHONE_STATE`, `READ_PHONE_NUMBERS` |
| No accounts list | `GET_ACCOUNTS` |

The `<uses-permission>` entries (including `uses-permission-sdk-23`) are cut out of the patched manifest. Unlike `pm revoke`, this is a property of the installed APK: it survives restarts, it needs no running Shizuku, and the app cannot even ask for the permission again. Without `INTERNET` the app's UID cannot open network sockets at all.

**Checked before installing:** the manifest of the **signed** APK is compared with the original. If a selected permission is still in there, nothing gets installed. Afterwards the same start check as with switching trackers off runs: the app is started and watched for about 15 seconds.

**Honest limits**
- Google only promises the all-zeros ad ID from targetSdk 33, and Android only filters the app list from targetSdk 30. If the app targets lower, the result says so instead of pretending.
- One line shows which of these permissions the app declares at all. If it declares none of the selected ones and no other option is picked, the run stops with a message instead of re-signing the APK.
- **Without a clone** the patched app replaces the original — to get the permissions back you have to restore the original.
- Removing a permission is not removing code: trackers that need no permission keep running. Apps that need the internet stop working without it — that is the purpose of the offline clone, not a bug.
- **Not tested on a device.** Open: whether an offline clone of a small app starts and really gets no network, and whether Spooftest shows an all-zeros ad ID with "No Ad ID" (Spooftest has `AD_ID` and `READ_PHONE_STATE` in its real manifest — that path is covered by the unit tests, not by a phone).

**Tests:** 249 unit tests, 0 failures, 1 skipped. The new `PermissionStripTest` adds 11 — checked with aapt2 against synthetic manifests, against the real manifests of FOSS Browser and Spooftest, and in combination with the clone rename.

**Also in the box (since 1.18.x)**
- Backup and restore of profiles plus behaviour settings in one file (format v2).
- Switch state is only written through `SwitchGuard`, so opening the detail page no longer fires confirmation dialogs on its own.
- Tracker overview reads manifest **and** code (DEX) and names the source per entry.

Download: https://github.com/pikatchu2k3/AppOpsShizuku/releases/latest — this repository is **private**; for updates through [Obtainium](https://github.com/ImranR98/Obtainium) you need a GitHub token with read access.

### Deutsch

**App Ops Shizuku 1.19.0** — Berechtigungen beim Patchen entfernen (Beta): Offline-Klon, keine Werbe-ID, keine App-Liste, kein Standort, kein Telefonstatus, keine Kontenliste.

**Neuer Abschnitt „Berechtigungen entfernen (Beta)"** im Reiter Tarnung, direkt unter „Tracker abschalten". Sechs Kästchen, jeweils mit den Berechtigungen, die sie entfernen:

| Kästchen | Entfernte Berechtigungen |
|---|---|
| Kein Internet (Offline-Klon) | `INTERNET` |
| Keine Werbe-ID | `com.google.android.gms.permission.AD_ID`, `ACCESS_ADSERVICES_*` (Privacy Sandbox) |
| Installierte Apps nicht sichtbar | `QUERY_ALL_PACKAGES` |
| Kein Standort | `ACCESS_FINE/COARSE/BACKGROUND_LOCATION` |
| Kein Telefonstatus / keine Rufnummer | `READ_PHONE_STATE`, `READ_PHONE_NUMBERS` |
| Keine Kontenliste | `GET_ACCOUNTS` |

Die `<uses-permission>`-Einträge (auch `uses-permission-sdk-23`) werden aus dem gepatchten Manifest herausgeschnitten. Anders als `pm revoke` ist das eine Eigenschaft der installierten APK: Es übersteht Neustarts, braucht kein laufendes Shizuku, und die App kann die Berechtigung nicht einmal mehr anfragen. Ohne `INTERNET` kann die App-UID überhaupt keine Netz-Sockets öffnen.

**Geprüft, bevor installiert wird:** Das Manifest der **signierten** APK wird mit dem Original verglichen. Steht eine gewählte Berechtigung noch drin, wird nichts installiert. Danach läuft dieselbe Startprobe wie beim Tracker-Abschalten: App starten, rund 15 Sekunden auf Abstürze achten.

**Ehrliche Grenzen**
- Die Nullen-Werbe-ID sagt Google erst ab targetSdk 33 zu, den Filter der App-Liste gibt es erst ab targetSdk 30. Liegt die App darunter, steht das im Ergebnis, statt es zu verschweigen.
- Eine Zeile zeigt vorab, welche dieser Berechtigungen die App überhaupt deklariert. Deklariert sie keine der gewählten und ist keine weitere Option gewählt, bricht der Lauf mit einer Meldung ab, statt neu zu signieren.
- **Ohne Klon** ersetzt die gepatchte App das Original — um die Berechtigungen zurückzubekommen, muss das Original wiederhergestellt werden.
- Eine Berechtigung zu entfernen ist nicht dasselbe wie Code zu entfernen: Tracker, die keine Berechtigung brauchen, laufen weiter. Apps, die Internet brauchen, funktionieren ohne nicht mehr — genau das ist der Zweck des Offline-Klons, kein Fehler.
- **Nicht am Gerät getestet.** Offen: ob ein Offline-Klon einer kleinen App startet und wirklich kein Netz bekommt, und ob Spooftest mit „Keine Werbe-ID" eine Werbe-ID aus Nullen zeigt (Spooftest hat `AD_ID` und `READ_PHONE_STATE` im echten Manifest — dieser Pfad ist durch die Unit-Tests abgedeckt, nicht durch ein Telefon).

**Tests:** 249 Unit-Tests, 0 Fehler, 1 übersprungen. Neu sind 11 in `PermissionStripTest` — mit aapt2 an synthetischen Manifesten, an den echten Manifesten von FOSS Browser und Spooftest und in Kombination mit dem Klon-Rename geprüft.

**Ebenfalls drin (seit 1.18.x)**
- Sicherung und Wiederherstellung von Profilen samt Verhaltens-Einstellungen in einer Datei (Format v2).
- Schalter-Zustand wird nur noch über `SwitchGuard` gesetzt — das Öffnen der Detailseite löst keine Bestätigungsdialoge mehr von selbst aus.
- Tracker-Übersicht liest Manifest **und** Code (DEX) und nennt je Eintrag die Quelle.

Download: https://github.com/pikatchu2k3/AppOpsShizuku/releases/latest — dieses Repository ist **privat**; für Updates über [Obtainium](https://github.com/ImranR98/Obtainium) ist ein GitHub-Token mit Lesezugriff nötig.

<sub>Paket `de.minipc.appopsshizuku` · versionCode 34 · debug-signiert (Zertifikat SHA-256 `652394f7…7226e`, updatefähig über 1.18.1) · targetSdk 35 · Lizenz GPL-3.0</sub>
