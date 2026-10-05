### English

**App Ops Shizuku 1.15.1** — the tracker overview now also reads the app's code, and it says out loud that it cannot switch anything off.

**New**
- The tracker overview no longer looks at the manifest alone: it also searches the app's DEX files (base APK plus every split of an app bundle) for the class names of known building blocks. Those files are readable by a normal app — no root, no Shizuku needed.
- Every entry now shows where it was found: *manifest + code*, *code only*, *manifest only*, or not found. Building blocks without manifest components were invisible before and are caught now: Segment, Amplitude, the old Google Analytics, Facebook SDK (app events) and the Google advertising ID library.
- Real measurements on a test device: 37 MB app → 4.6 s, 196 MB app bundle → 21.8 s, on a background thread, with a progress line, measured runtime in the log and a result cache per app.
- The tab states clearly why per-tracker switches are missing: Android refuses component changes to the shell without root. Blocking is done by the three switches above; only the APK patch (Stealth tab) removes code.

**Fixed**
- False alarm: Google's advertising ID library (`AdvertisingIdClient`) is present in many apps that show no ads at all — it made the AdMob entry light up. AdMob now uses precise class names, and the advertising ID has its own honest entry. Verified on two real apps: an app without ads now correctly reports "AdMob: not found" instead of a wrong hit.

**Honest limits**
- The code search works on names. An obfuscated app can rename library classes, and SDKs loaded only by reflection or remote config leave no name behind — "not found" stays a probability, not a promise.
- Disabling single *components* of other apps is not offered: Android 15/16 refuses it to the shell (`SecurityException: Shell cannot change component state`).
- The switches stop network traffic, not the tracking code inside the app.

**App Ops Shizuku 1.16.0** — Stealth tab: tracker switch-off while patching, plus the work in progress from the previous release.
- **In development (beta).** Patching and switching trackers off can damage the app. Back up the app's data before you patch.
- The patched app is signed with **our own key** — **not** with the manufacturer's or Google's key. So: **no more Play updates**, Play Protect may warn, and the way back to the original app is a reinstall.
- New *Disable trackers (beta)*: sets `android:enabled="false"` on the components of the chosen groups (nothing is deleted) and adds the documented Firebase switches. Push, WorkManager, billing, login and the DataTransport scheduler are never touched. The patched APK is scanned and compared with the original ("before: 5 — after: 2 — switched off: …"), the original APKs are saved first, and the app is started once with a ~15 s log check (`FATAL EXCEPTION`, `ClassNotFoundException`, `NoSuchMethodError`, `VerifyError`, `SecurityException`).
- Clone: package-rename fix for `android:authorities` lists like `a.x;a.y` (every entry is renamed now), a clone is refused with a clear message when a split carries its own code, the target package name is checked for collisions, default label "Original (Kopie)".
- Limits (measured on a test device): the code of switched-off building blocks stays in the APK and the switch-off is a statement about the manifest, not a guarantee that nothing is sent. A 39 MB app took ~90 s for the manifest-only route, while the clone's code rewrite is CPU-bound and slow on weak hardware (9 MB app: several minutes in DEX_PATCH; 37 MB: no progress past 40 % in nine minutes). On a real app the result was "before: 8 — after: 6", with AdMob, advertising-ID, Braze, Adjust, DataTransport and Crashlytics explicitly reported as still active, and SDKs unknown to the catalog (Microsoft Clarity) not covered at all. SDKs without manifest components cannot be switched off this way; a clone gets its own data, Firebase `google_app_id` / AdMob app ID are bound to package and signature, and Play Integrity apps may refuse to start.

Download: https://github.com/pikatchu2k3/AppOpsShizuku/releases/latest
Automatic updates: add this repository to [Obtainium](https://github.com/ImranR98/Obtainium) (source: GitHub Releases).

Build: `:app:assembleDebug` · package `de.minipc.appopsshizuku` · versionCode 30 · debug signature (certificate SHA-256 `652394f7…7226e`).
License: **GNU General Public License v3.0** — see [LICENSE](https://github.com/pikatchu2k3/AppOpsShizuku/blob/master/LICENSE).

### Deutsch

**App Ops Shizuku 1.15.1** — die Tracker-Übersicht liest jetzt auch den Code der App und sagt deutlich, dass sie nichts abschalten kann.

**Neu**
- Die Tracker-Übersicht schaut nicht mehr nur ins Manifest: Sie durchsucht zusätzlich die DEX-Dateien der App (Basis-APK und alle Splits eines App-Bundles) nach den Klassennamen bekannter Bausteine. Diese Dateien darf eine normale App lesen — ohne Root, ohne Shizuku.
- Jeder Eintrag zeigt jetzt die Herkunft: *Manifest + Code*, *nur Code*, *nur Manifest* oder nicht gefunden. Bausteine ohne Manifest-Komponenten waren vorher unsichtbar und werden nun erfasst: Segment, Amplitude, das alte Google Analytics, Facebook SDK (App-Events) und die Google-Werbe-ID-Bibliothek.
- Echte Messwerte am Testgerät: 37-MB-App → 4,6 s, 196-MB-App-Bundle → 21,8 s, im Hintergrund-Thread, mit Fortschrittszeile, gemessener Laufzeit im Log und Ergebnis-Cache je App.
- Der Reiter sagt jetzt klar, warum Schalter pro Tracker fehlen: Android verweigert der Shell ohne Root das Ändern von Komponenten. Blockiert wird über die drei Schalter oben; Code entfernt nur der APK-Patch (Reiter Tarnung).

**Behoben**
- Fehlalarm: Die Google-Werbe-ID-Bibliothek (`AdvertisingIdClient`) steckt in vielen Apps, die gar keine Werbung zeigen — sie ließ den AdMob-Eintrag aufleuchten. AdMob prüft jetzt genaue Klassennamen, die Werbe-ID hat einen eigenen, ehrlichen Eintrag. An zwei echten Apps belegt: Eine App ohne Werbung meldet nun korrekt „AdMob: nicht gefunden" statt eines falschen Treffers.

**Ehrliche Grenzen**
- Die Code-Suche arbeitet über Namen. Eine verschleierte App kann Bibliotheksnamen umbenennen, und SDKs, die nur per Reflection oder Remote-Konfiguration geladen werden, hinterlassen keinen Namen — „nicht gefunden" bleibt eine Wahrscheinlichkeit, keine Zusage.
- Das Abschalten einzelner *Komponenten* fremder Apps wird bewusst nicht angeboten: Android 15/16 verweigert es der Shell (`SecurityException: Shell cannot change component state`).
- Die Schalter stoppen Netzverkehr, nicht den Tracker-Code in der App.

**App Ops Shizuku 1.16.0** — Reiter Tarnung: Tracker beim Patchen abschalten, dazu die schon begonnene Arbeit aus dem vorigen Release.
- **In Entwicklung (Beta).** Patch und Abschalten können die App beschädigen. Wichtig ist eine Sicherung der App-Daten, bevor du patchst.
- Die gepatchte App wird mit **unserem eigenen Schlüssel** signiert — **nicht** mit dem Schlüssel des Herstellers bzw. von Google. Deshalb: **keine Play-Updates mehr**, Play Protect kann warnen, und zurück zur Original-App geht es nur über Neuinstallation.
- Neu: *Tracker abschalten (Beta)*: setzt `android:enabled="false"` an den Komponenten der gewählten Gruppen (nichts wird gelöscht) und hängt die dokumentierten Firebase-Schalter an. Push, WorkManager, Bezahlung, Login und der DataTransport-Scheduler werden nie angefasst. Das gepatchte APK wird gescannt und mit dem Original verglichen („vorher: 5 — nachher: 2 — abgeschaltet: …"), die Original-APKs werden vorher gesichert, und die App wird einmal gestartet, mit ca. 15 s Logprüfung (`FATAL EXCEPTION`, `ClassNotFoundException`, `NoSuchMethodError`, `VerifyError`, `SecurityException`).
- Klon: Paket-Umbenennung korrigiert für `android:authorities`-Listen wie `a.x;a.y` (jeder Eintrag wird umbenannt), ein Klon wird mit klarer Meldung abgelehnt, wenn ein Split eigenen Code enthält, der Ziel-Paketname wird auf Kollisionen geprüft, Standard-Name „Original (Kopie)".
- Grenzen (am Testgerät gemessen): Der Code abgeschalteter Bausteine bleibt in der APK, und das Abschalten ist eine Aussage über das Manifest, keine Zusage, dass nichts gesendet wird. Eine 39-MB-App brauchte über den Manifest-Weg rund 90 s, während der Code-Umbau des Klons CPU-gebunden und auf schwacher Hardware langsam ist (9-MB-App: mehrere Minuten in DEX_PATCH; 37 MB: in neun Minuten kein Fortschritt über 40 %). An einer echten App lautete das Ergebnis „vorher: 8 — nachher: 6", wobei AdMob, Werbe-ID, Braze, Adjust, Datentransport und Crashlytics ausdrücklich als weiter aktiv gemeldet wurden und dem Katalog unbekannte SDKs (Microsoft Clarity) gar nicht erfasst sind. SDKs ohne Manifest-Komponenten lassen sich so nicht abschalten; ein Klon hat eigene Daten, Firebase-`google_app_id` / AdMob-App-ID sind an Paketname und Signatur gebunden, und Apps mit Play Integrity können den Start verweigern.

Download: https://github.com/pikatchu2k3/AppOpsShizuku/releases/latest
Automatische Updates: dieses Repository in [Obtainium](https://github.com/ImranR98/Obtainium) hinzufügen (Quelle: GitHub Releases).

Build: `:app:assembleDebug` · Paket `de.minipc.appopsshizuku` · versionCode 30 · Debug-Signatur (Zertifikat SHA-256 `652394f7…7226e`).
Lizenz: **GNU General Public License v3.0** — siehe [LICENSE](https://github.com/pikatchu2k3/AppOpsShizuku/blob/master/LICENSE).
