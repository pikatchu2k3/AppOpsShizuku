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

Download: https://github.com/pikatchu2k3/AppOpsShizuku/releases/latest
Automatic updates: add this repository to [Obtainium](https://github.com/ImranR98/Obtainium) (source: GitHub Releases).

Build: `:app:assembleDebug` · package `de.minipc.appopsshizuku` · versionCode 29 · debug signature (certificate SHA-256 `652394f7…7226e`).

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

Download: https://github.com/pikatchu2k3/AppOpsShizuku/releases/latest
Automatische Updates: dieses Repository in [Obtainium](https://github.com/ImranR98/Obtainium) hinzufügen (Quelle: GitHub Releases).

Build: `:app:assembleDebug` · Paket `de.minipc.appopsshizuku` · versionCode 29 · Debug-Signatur (Zertifikat SHA-256 `652394f7…7226e`).
