# App Ops Shizuku

**English** · [Deutsch](#deutsch)

## English

App Ops Shizuku controls permissions, App Ops and background behaviour of **other apps** on your Android phone through [Shizuku](https://shizuku.rikka.app/). **No root, no Xposed.** It is meant for people who want to see and restrict what installed apps may do (camera, microphone, contacts, clipboard, background network, …) without rooting the device.

### Requirements

- Android 8.0+ (`minSdk 26`, see `app/build.gradle.kts`)
- A running **Shizuku** server (e.g. started via wireless debugging)
- Shizuku permission granted to App Ops Shizuku

Without Shizuku the app only *shows* state; it changes nothing.

### Install / update

Download the APK from the [latest GitHub release](https://github.com/pikatchu2k3/AppOpsShizuku/releases/latest).

To get updates automatically, add the repository to [Obtainium](https://github.com/ImranR98/Obtainium) (source: *GitHub Releases*, URL `https://github.com/pikatchu2k3/AppOpsShizuku`). The signing certificate stays the same, so an update installs in place and keeps your data.

### Features

- **App list** with live search (label and package name), sorting by name / install date / update date, and a "blocked" filter.
- **Privacy profile per app** (tab *Permissions*): camera, microphone, contacts, calendar, call log, accounts, clipboard, sensors and more, set per app to default / allow / ignore / deny. "Block all", "Reset all to default", and JSON export/import of profiles. Every change is read back from the device; ops the device refuses are shown as "not settable".
- **Behaviour control** (tab *Behavior*): disable an app, freeze it (`pm suspend`), clear its data (confirmed by typing the package name), set the standby bucket, grant/remove the battery (Doze) exemption, "optimize app" (force recompile). Also a lifecycle profile (battery exemption + standby bucket + background ops in one step, revertible) and an optional "stop app when the screen turns off" (runs a foreground service while enabled).
- **Stealth** (tab *Stealth*; the controls currently live in the *Components* screen): patches an app's APK on the device and re-signs it. Hide from recents, hide the launcher icon, blank the recents thumbnail (`FLAG_SECURE`), require an active VPN, hide behind a calculator (vault), app-specific device-ID spoofing (build fields, Android ID, IMEI/IMSI), or install as a parallel clone.
- **Network & Trackers** (tab *Network & Trackers*):
  - block background data,
  - take an app completely offline,
  - put an app on Android's background restrict list,
  - tracker detection: reads the app's manifest and lists known building blocks (Firebase Analytics/DataTransport, Crashlytics, Facebook SDK, AdMob, Audience Network, Sentry, Mixpanel, Braze, AppsFlyer, Adjust, Kochava).
  
  Each switch is verified by reading the state back from the device. "Reset all" only undoes what this app changed.

### Honest limits

- **Single components of other apps cannot be switched at runtime.** Android 15/16 refuses it to the shell (`SecurityException: Shell cannot change component state`), so there is deliberately no switch for it. The Stealth tab gets around this differently: it edits the manifest of a re-signed copy (see *Work in progress*).
- The network switches stop **network traffic**, not the tracker code inside the app. Ads/analytics that the app starts itself keep running in its process. Only the APK patch in the Stealth tab can remove actual code.
- Tracker detection only knows building blocks that declare manifest components. **"Not found" does not mean "not included."**
- "Completely offline" is not possible for system apps (the switch is disabled).
- Some permissions can't be changed on some apps/devices (e.g. an app doesn't request the permission, or the device refuses the op); the app says so instead of pretending.

### Work in progress (beta)

The Stealth tab (patch, clone, tracker switch-off) is **in development**. It is shown with a permanent notice in the tab, in the confirmation dialog and in the result.

- **In development (beta).** Patching and switching trackers off can damage the app. Back up the app's data before you patch.
- The patched app is signed with **our own key** — **not** with the manufacturer's or Google's key. Therefore: **no more Play updates**, Play Protect may warn, and the way back to the original app is a reinstall (*Restore* reinstalls the saved original APKs; the app's data does not survive that).

**Disable trackers (beta)** — three check boxes (*Ads*, *Usage data & crash reports*, *Install attribution*), plus a line listing the known building blocks found in the app:
1. Components of the chosen groups get `android:enabled="false"` in the patched manifest. Nothing is deleted: the entries and class names stay, so SDK init paths do not break; the framework just no longer instantiates them.
2. For Firebase the officially documented switches are added as the last children of `<application>` (they win over an earlier entry): `firebase_analytics_collection_enabled=false`, `firebase_analytics_collection_deactivated=true`, `firebase_crashlytics_collection_enabled=false` — only if the SDK is detected.
3. There is **no code-level step**: untested smali edits are not allowed.
- Never touched, even if the catalog knows them: push (`FirebaseMessagingService`, third-party `…MessagingService` classes), WorkManager (`androidx.work.*`), billing (`com.android.billingclient.*`), Google/Facebook login, and the DataTransport scheduler (shared infrastructure of push and crash reporting).
- **Proof:** the patched APK is scanned with the built-in catalog (manifest and code scan) and compared with the original: "Building blocks before: 5 — after: 2 — switched off: …".
- **Way back:** the original APK(s) are saved before the patch; the result names the folder ("Original saved: …"). An aborted patch changes nothing on the installed app.
- **Start test:** after installing, the patched app is started once and `logcat` is read for ~15 s (only the app's own processes): `FATAL EXCEPTION`, `ClassNotFoundException`, `NoSuchMethodError`, `VerifyError`, `SecurityException`. A fatal exception is reported as "app crashes after the patch"; other hits are reported as warnings. No silent success. If the app has no launcher activity or cannot be started, the result says the test was **not** done.

**Honest limits of the patch**
- The code of switched-off building blocks stays in the APK. Building blocks without manifest components (Segment, Amplitude, old Google Analytics, advertising-ID library, Facebook app events) cannot be switched off this way.
- "Switched off" in the proof means: all switchable manifest components of that building block are disabled. It is a statement about the manifest, not a guarantee that nothing is sent.
- Only the base APK's manifest is edited for the tracker switch-off. A split that contains its own code makes a clone fail with a clear message (the package rename only covers the base APK's code).
- The start test is a log heuristic: a caught `SecurityException` shows up as a warning although the app runs.

**Measured on a real device (Android 14, 3.5 GB RAM, test device — not estimates)**
- Patch without clone (manifest only), 39 MB app: extract → repack → sign → install in about 90 s. This is the fast route.
- Klon/code rewrite (baksmali + smali round trip) is CPU-bound and takes long on weak hardware: a 9 MB app already ran several minutes in `DEX_PATCH` (~2.8 cores of continuous load, ~500 MB RSS); a 37 MB app did not get past `DEX_PATCH 40 %` within nine minutes. On this test device clones are only practical for small apps — the progress notification shows the phase, and cancelling leaves the installed app untouched.
- Observed result on a 37 MB app (8 building blocks found): *before: 8 — after: 6 — switched off: Firebase Analytics, Facebook Audience Network*, plus the three Firebase switches; explicitly still active and reported as such: AdMob, advertising-ID library, Braze, Adjust, DataTransport, Crashlytics. Building blocks the catalog does not know are not covered at all (real case: `com.microsoft.clarity.jobs.UploadSessionJob` stayed active).
- The start test is bounded to 90 s and never blocks the run; if it cannot see the app's process it says "not meaningful" instead of claiming success.

**Clone limits**
- Firebase (`google_app_id`) and AdMob (app ID) are bound to the package name and signature — a clone may report registration errors or get no ads.
- Apps with an integrity check (Play Integrity) may refuse to start.
- The clone starts with **its own data** (no login/history transfer).
- Before patching, the target package name is checked: if another app already uses it, the tab says so and nothing is patched. An earlier clone made by this app is updated in place.

### Build from source

```bash
export JAVA_HOME=/path/to/jdk        # JDK 17+ (the project targets Java 17)
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
./gradlew :app:assembleDebug
```

The APK ends up in `app/build/outputs/apk/debug/`. Unit tests: `./gradlew :app:testDebugUnitTest`. `scripts/release.sh` builds, tests, checks the signature and publishes a GitHub release (maintainer use).

### Signature

Releases are signed with the debug certificate, SHA-256 `652394f7…7226e` (full value: `652394f75d9ed1ea168561e4056556fd82d3645a6c88020cd30b18c0a3d7226e`). An update can only be installed over an existing install if it has the **same signature**; an APK you build yourself with a different debug key will not update the released app (uninstall first, which deletes app data).

### License

No license file exists in this repository yet — **this is an open point**, so no license is granted for now. Note: the app bundles a build of a CalcYou fork (`app/src/main/res/raw/calcyou.apk`, described in `PROJEKTSTAND.md` as GPL-3.0) for the calculator vault; that has to be settled when a license is chosen.

---

## Deutsch

[English](#english) · **Deutsch**

App Ops Shizuku steuert Berechtigungen, App Ops und Hintergrundverhalten **anderer Apps** auf deinem Android-Handy über [Shizuku](https://shizuku.rikka.app/). **Ohne Root, ohne Xposed.** Gedacht für alle, die sehen und einschränken wollen, was installierte Apps dürfen (Kamera, Mikrofon, Kontakte, Zwischenablage, Netz im Hintergrund, …), ohne das Gerät zu rooten.

### Voraussetzungen

- Android 8.0+ (`minSdk 26`, siehe `app/build.gradle.kts`)
- Ein laufender **Shizuku**-Server (z. B. per Wireless-Debugging gestartet)
- Erteilte Shizuku-Berechtigung für App Ops Shizuku

Ohne Shizuku *zeigt* die App nur an und schaltet nichts.

### Installation / Update

APK aus dem [neuesten GitHub-Release](https://github.com/pikatchu2k3/AppOpsShizuku/releases/latest) laden.

Automatische Updates: Repository in [Obtainium](https://github.com/ImranR98/Obtainium) eintragen (Quelle: *GitHub Releases*, URL `https://github.com/pikatchu2k3/AppOpsShizuku`). Die Signatur bleibt gleich, deshalb wird ein Update direkt über die installierte App eingespielt, die Daten bleiben erhalten.

### Funktionen

- **App-Liste** mit Live-Suche (Name und Paketname), Sortierung nach Name / Installations- / Aktualisierungsdatum und Filter „Blockiert".
- **Privacy-Profil je App** (Reiter *Berechtigungen*): Kamera, Mikrofon, Kontakte, Kalender, Anrufliste, Konten, Zwischenablage, Sensoren u. a., je App auf Standard / erlauben / ignorieren / verweigern. „Alles blockieren", „Alle auf Standard" sowie JSON-Export/-Import der Profile. Jede Änderung wird vom Gerät zurückgelesen; Ops, die das Gerät verweigert, erscheinen als „nicht setzbar".
- **Verhaltens-Steuerung** (Reiter *Verhalten*): App deaktivieren, einfrieren (`pm suspend`), Daten löschen (Bestätigung per Paketname), Standby-Stufe setzen, Akku-Ausnahme (Doze) erteilen/entfernen, „App optimieren" (neu kompilieren). Dazu ein Lifecycle-Profil (Akku-Ausnahme + Standby-Stufe + Hintergrund-Ops in einem Schritt, zurücknehmbar) und optional „Beim Bildschirm-Aus anhalten" (solange aktiv, läuft ein Vordergrunddienst).
- **Tarnung / Stealth** (Reiter *Tarnung*; die Schalter liegen derzeit noch im Bildschirm *Komponenten*): patcht die APK einer App auf dem Gerät und signiert sie neu. Aus dem Verlauf ausblenden, Launcher-Icon entfernen, Verlaufs-Vorschau schwärzen (`FLAG_SECURE`), VPN-Pflicht, als Taschenrechner tarnen (Vault), app-spezifisches Geräte-Spoofing (Build-Felder, Android-ID, IMEI/IMSI) oder als parallelen Klon installieren.
- **Netz & Tracker** (Reiter *Netz & Tracker*):
  - Hintergrund-Datenverkehr sperren,
  - App komplett offline schalten,
  - App auf Androids Hintergrund-Sperrliste setzen,
  - Tracker-Erkennung: liest das Manifest der App und listet bekannte Bausteine (Firebase Analytics/DataTransport, Crashlytics, Facebook SDK, AdMob, Audience Network, Sentry, Mixpanel, Braze, AppsFlyer, Adjust, Kochava).
  
  Jeder Schalter wird durch Zurücklesen vom Gerät geprüft. „Alles zurücksetzen" macht nur rückgängig, was diese App selbst geändert hat.

### Ehrliche Grenzen

- **Einzelne Komponenten fremder Apps lassen sich zur Laufzeit nicht abschalten.** Android 15/16 verweigert das der Shell (`SecurityException: Shell cannot change component state`) — deshalb gibt es dafür bewusst keinen Schalter. Der Reiter Tarnung umgeht das anders: Er bearbeitet das Manifest einer neu signierten Kopie (siehe *In Entwicklung*).
- Die Netzschalter stoppen **Netzverkehr**, nicht den Tracker-Code in der App. Werbung/Analysen, die die App selbst im eigenen Prozess startet, laufen weiter. Echten Code entfernt nur der APK-Patch im Reiter Tarnung.
- Die Tracker-Erkennung kennt nur Bausteine mit Manifest-Komponenten. **„Nicht gefunden" heißt nicht „nicht enthalten".**
- „Komplett offline" ist bei System-Apps nicht möglich (der Schalter ist dort deaktiviert).
- Manche Berechtigungen lassen sich bei manchen Apps/Geräten nicht ändern (z. B. fordert die App sie nicht an, oder das Gerät lehnt die Op ab); die App sagt das, statt Erfolg vorzutäuschen.

### In Entwicklung (Beta)

Der Reiter Tarnung (Patch, Klon, Tracker abschalten) ist **in Entwicklung**. Er trägt einen festen Hinweis im Reiter, im Bestätigungsdialog und im Ergebnis.

- **In Entwicklung (Beta).** Patch und Abschalten können die App beschädigen. Wichtig ist eine Sicherung der App-Daten, bevor du patchst.
- Die gepatchte App wird mit **unserem eigenen Schlüssel** signiert — **nicht** mit dem Schlüssel des Herstellers bzw. von Google. Deshalb: **keine Play-Updates mehr**, Play Protect kann warnen, und zurück zur Original-App geht es nur über Neuinstallation (*Wiederherstellen* installiert die gesicherten Original-APKs neu; die App-Daten bleiben dabei nicht erhalten).

**Tracker abschalten (Beta)** — drei Ankreuzfelder (*Werbung*, *Nutzungsdaten & Absturzberichte*, *Installations-Zuordnung*) und eine Zeile mit den erkannten Bausteinen der App:
1. Komponenten der gewählten Gruppen bekommen im gepatchten Manifest `android:enabled="false"`. Nichts wird gelöscht: Einträge und Klassennamen bleiben, SDK-Startpfade brechen nicht; das System instanziiert sie nur nicht mehr.
2. Für Firebase werden die offiziell dokumentierten Schalter als letzte Kinder von `<application>` angehängt (sie gewinnen gegen einen früheren Eintrag): `firebase_analytics_collection_enabled=false`, `firebase_analytics_collection_deactivated=true`, `firebase_crashlytics_collection_enabled=false` — nur, wenn das SDK erkannt wurde.
3. Eine **Code-Stufe gibt es nicht**: ungetestete Smali-Eingriffe sind nicht erlaubt.
- Nie angefasst, auch wenn der Katalog sie kennt: Push (`FirebaseMessagingService`, `…MessagingService` von Drittanbietern), WorkManager (`androidx.work.*`), Bezahlung (`com.android.billingclient.*`), Google-/Facebook-Login und der DataTransport-Scheduler (gemeinsame Infrastruktur von Push und Absturzberichten).
- **Beweis:** Das gepatchte APK wird mit dem eingebauten Katalog gescannt (Manifest und Code-Scan) und mit dem Original verglichen: „Bausteine vorher: 5 — nachher: 2 — abgeschaltet: …".
- **Rückweg:** Vor dem Patch werden die Original-APK(s) gesichert; das Ergebnis nennt den Ordner („Original gesichert: …"). Ein abgebrochener Patch ändert nichts an der installierten App.
- **Startprobe:** Nach der Installation wird die gepatchte App einmal gestartet und ca. 15 s lang das `logcat` gelesen (nur die eigenen Prozesse der App): `FATAL EXCEPTION`, `ClassNotFoundException`, `NoSuchMethodError`, `VerifyError`, `SecurityException`. Ein Absturz wird als „App stürzt nach dem Patch ab" gemeldet, andere Treffer als Warnung. Kein stiller Erfolg. Hat die App keine Launcher-Activity oder lässt sie sich nicht starten, steht im Ergebnis, dass **nicht** geprüft wurde.

**Ehrliche Grenzen des Patches**
- Der Code abgeschalteter Bausteine bleibt in der APK. Bausteine ohne Manifest-Komponenten (Segment, Amplitude, altes Google Analytics, Werbe-ID-Bibliothek, Facebook-App-Events) lassen sich so nicht abschalten.
- „Abgeschaltet" heißt im Beweis: alle abschaltbaren Manifest-Komponenten dieses Bausteins sind deaktiviert. Das ist eine Aussage über das Manifest, keine Garantie, dass nichts mehr gesendet wird.
- Für das Abschalten wird nur das Manifest der Basis-APK bearbeitet. Ein Split mit eigenem Code lässt einen Klon mit klarer Meldung scheitern (die Paket-Umbenennung im Code deckt nur die Basis-APK ab).
- Die Startprobe ist eine Log-Heuristik: Eine abgefangene `SecurityException` erscheint als Warnung, obwohl die App läuft.

**Am echten Gerät gemessen (Android 14, 3,5 GB RAM, Testgerät — keine Schätzwerte)**
- Patch ohne Klon (nur Manifest), 39-MB-App: Extrahieren → Repack → Signieren → Installieren in rund 90 s. Das ist der schnelle Weg.
- Klon/Code-Umbau (baksmali + smali) ist CPU-gebunden und auf schwacher Hardware langsam: Eine 9-MB-App lief schon mehrere Minuten in `DEX_PATCH` (rund 2,8 Kerne Dauerlast, ~500 MB Speicher); eine 37-MB-App kam in neun Minuten nicht über `DEX_PATCH 40 %`. Auf dem Testgerät sind Klone nur für kleine Apps praktikabel — die Fortschritts-Benachrichtigung zeigt die Phase, ein Abbruch lässt die installierte App unangetastet.
- Beobachtetes Ergebnis an einer 37-MB-App (8 gefundene Bausteine): *vorher: 8 — nachher: 6 — abgeschaltet: Firebase Analytics, Facebook Audience Network*, dazu die drei Firebase-Schalter; ausdrücklich weiter aktiv und so gemeldet: AdMob, Werbe-ID-Bibliothek, Braze, Adjust, Datentransport, Crashlytics. Bausteine, die der Katalog nicht kennt, werden gar nicht erfasst (realer Fall: `com.microsoft.clarity.jobs.UploadSessionJob` blieb aktiv).
- Die Startprobe ist auf 90 s begrenzt und blockiert den Lauf nie; sieht sie den Prozess der App nicht, sagt sie „nicht aussagekräftig" statt Erfolg zu behaupten.

**Grenzen des Klons**
- Firebase (`google_app_id`) und AdMob (App-ID) sind an Paketname und Signatur gebunden — der Klon kann Registrierungsfehler melden oder keine Werbung erhalten.
- Apps mit Integritätsprüfung (Play Integrity) können den Start verweigern.
- Der Klon startet mit **eigenen Daten** (kein Login-/Verlaufs-Transfer).
- Vor dem Patch wird der Ziel-Paketname geprüft: Ist er von einer anderen App belegt, meldet der Reiter das und es wird nichts gepatcht. Ein früherer Klon dieser App wird an Ort und Stelle aktualisiert.

### Selbst bauen

```bash
export JAVA_HOME=/pfad/zum/jdk       # JDK 17+ (Projekt zielt auf Java 17)
export ANDROID_HOME=/pfad/zum/android-sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
./gradlew :app:assembleDebug
```

Das APK liegt danach unter `app/build/outputs/apk/debug/`. Unit-Tests: `./gradlew :app:testDebugUnitTest`. `scripts/release.sh` baut, testet, prüft die Signatur und veröffentlicht ein GitHub-Release (für den Maintainer).

### Signatur

Releases sind mit dem Debug-Zertifikat signiert, SHA-256 `652394f7…7226e` (vollständig: `652394f75d9ed1ea168561e4056556fd82d3645a6c88020cd30b18c0a3d7226e`). Ein Update lässt sich nur mit **gleicher Signatur** über eine bestehende Installation einspielen; ein selbst gebautes APK mit anderem Debug-Schlüssel aktualisiert die veröffentlichte App nicht (erst deinstallieren, dabei gehen die App-Daten verloren).

### Lizenz

Im Repository gibt es noch keine Lizenzdatei — **das ist ein offener Punkt**, daher wird vorerst keine Lizenz eingeräumt. Hinweis: Die App bündelt einen Build eines CalcYou-Forks (`app/src/main/res/raw/calcyou.apk`, laut `PROJEKTSTAND.md` GPL-3.0) für den Taschenrechner-Vault; das muss bei der Lizenzwahl geklärt werden.
