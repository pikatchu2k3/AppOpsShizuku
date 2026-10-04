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

- **Single components of other apps cannot be switched.** Android 15/16 refuses it to the shell (`SecurityException: Shell cannot change component state`), so there is deliberately no switch for it.
- The network switches stop **network traffic**, not the tracker code inside the app. Ads/analytics that the app starts itself keep running in its process. Only the APK patch in the Stealth tab can remove actual code.
- Tracker detection only knows building blocks that declare manifest components. **"Not found" does not mean "not included."**
- "Completely offline" is not possible for system apps (the switch is disabled).
- Some permissions can't be changed on some apps/devices (e.g. an app doesn't request the permission, or the device refuses the op); the app says so instead of pretending.

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

- **Einzelne Komponenten fremder Apps lassen sich nicht abschalten.** Android 15/16 verweigert das der Shell (`SecurityException: Shell cannot change component state`) — deshalb gibt es dafür bewusst keinen Schalter.
- Die Netzschalter stoppen **Netzverkehr**, nicht den Tracker-Code in der App. Werbung/Analysen, die die App selbst im eigenen Prozess startet, laufen weiter. Echten Code entfernt nur der APK-Patch im Reiter Tarnung.
- Die Tracker-Erkennung kennt nur Bausteine mit Manifest-Komponenten. **„Nicht gefunden" heißt nicht „nicht enthalten".**
- „Komplett offline" ist bei System-Apps nicht möglich (der Schalter ist dort deaktiviert).
- Manche Berechtigungen lassen sich bei manchen Apps/Geräten nicht ändern (z. B. fordert die App sie nicht an, oder das Gerät lehnt die Op ab); die App sagt das, statt Erfolg vorzutäuschen.

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
