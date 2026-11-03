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
  - **Only on demand (new, not yet tested on a device):** the app stays closed and locked (`am force-stop` + `pm suspend`, or `pm disable-user`, or **only stop** – `am force-stop` alone, so the app can still be opened from any launcher such as Nova; the watcher notices the start and stops it again after you leave) and only runs after you open it via its own home-screen shortcut. After leaving it, it is locked again after 10 s / 1 min / 5 min, or only when the screen turns off; turning the screen on never releases it. Leaving is detected via usage events (usage access is granted to this app via Shizuku), otherwise via `dumpsys activity`. Opening needs a running Shizuku; with *Disable* the app can also be re-enabled in the system settings without Shizuku. While locked, the app's messages, alarms and background sync do not arrive. **Own launchers** (e.g. AppDialer NXT) can open a frozen app through the exported action `de.minipc.appopsshizuku.action.OPEN_ON_DEMAND` (extra `pkg`); the caller must use `startActivityForResult` so Android reports who it is, and each new launcher is asked once (*Always allow* / *Only this time* / *Deny*; revocable in the *Behavior* tab).
- **Stealth** (tab *Stealth*; the controls currently live in the *Components* screen): patches an app's APK on the device and re-signs it. Hide from recents, hide the launcher icon, blank the recents thumbnail (`FLAG_SECURE`), require an active VPN, hide behind a calculator (vault), app-specific device-ID spoofing (build fields, Android ID, IMEI/IMSI), or install as a parallel clone.
- **Network & Trackers** (tab *Network & Trackers*):
  - block background data,
  - take an app completely offline,
  - put an app on Android's background restrict list,
  - tracker detection: reads the app's manifest and lists known building blocks (Firebase Analytics/DataTransport, Crashlytics, Facebook SDK, AdMob, Audience Network, Sentry, Mixpanel, Braze, AppsFlyer, Adjust, Kochava).
  
  Each switch is verified by reading the state back from the device. "Reset all" only undoes what this app changed.

- **New, not yet tested on a device:**
  - **Access log** (tab *Permissions*): last use, blocked attempts and "in use right now" per permission, from one `cmd appops get`. A suggestion card lists permissions that are granted but unused for 30 days, or unusual for the app's category (games, media, news) — nothing changes until you tap *Revoke*.
  - **Only while in use**: extra mode for permissions Android has no foreground mode for (contacts, calendar, call log, media, accounts …). Granted while the app is in front, revoked 10 s after leaving or when the screen turns off (Android then ends the app in the background). Runs in the screen watcher service.
  - **By permission** (menu): pick one permission and see which apps have it and when they last used it; switch per app or block it for all user apps at once (system apps never).
  - **Status line in the app list** (blocked, offline, no background data, on demand, while in use, quarantine) and a filter chip *Changed* — from this app's own records, no shell call.
  - **Quarantine for new apps** (menu, off by default): apps installed after switching it on get no background data, no clipboard and no background running until you release them in the *Permissions* tab.
  - **System-wide** (menu): *Lockdown* takes camera/microphone (optionally location) from all user apps and gives it back to exactly those apps — also as a Quick Settings tile. *Private DNS* with presets (AdGuard, Mullvad adblock, Quad9, Cloudflare) or an own host, with "back to previous". *Powerful access*: accessibility services, notification access, keyboards, default apps (SMS, phone, assistant, browser), always-on VPN and device admins — revocable where the shell may, otherwise a button to the system settings.
  - Blocking a permission now also sets `user-fixed`, so the app cannot simply ask again; granting and "Reset all" clear it.

- **More privacy tools (new, not yet tested on a device):**
  - **Change log** (menu): every change this app makes, with per-entry *Undo* for permission and network toggles.
  - **Profile templates** (Permissions tab): Game / Messenger / Banking in one tap, shareable as JSON.
  - **By network usage** (menu): per-app traffic over 7 days, background shown separately; apps with much background traffic that you rarely open are listed first, with a one-tap "block background".
  - **Activity** (menu): which apps wake the device, schedule jobs or read sensors (from dumpsys, heuristic).
  - **Automatic rules** (menu): by time (days + window, incl. overnight) or Wi-Fi (connected / not), action = Lockdown or take chosen apps offline. The Wi-Fi name is read only after you grant this app the location permission on first use (the app has no network access of its own). If Android hides the name while the app runs in the background, the rule is skipped (not switched off) until you open the app.
  - **Work profile** (menu): App Ops Shizuku can create a managed profile itself (own profile owner) and move apps into it; it can also move apps into an existing profile (e.g. Shelter) using shell rights alone.
  - **Placeholder data instead of blocking (beta patch)**: in the re-signed clone/patch, return a null advertising ID and a fixed city-level location instead of the real values, so apps that crash on "denied" keep working.
  - **Restart notice**: after a reboot (Shizuku not yet running) a notification points out that the configured protection is inactive; once connected, a bar offers to restore settings that drifted.

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

**Remove permissions (beta)** — six check boxes, plus a line listing which of these permissions the app declares at all:
- *No internet (offline clone)* `INTERNET` · *No advertising ID* `com.google.android.gms.permission.AD_ID` and the Privacy Sandbox `ACCESS_ADSERVICES_*` permissions · *Cannot see installed apps* `QUERY_ALL_PACKAGES` · *No location* `ACCESS_FINE/COARSE/BACKGROUND_LOCATION` · *No phone state / number* `READ_PHONE_STATE`, `READ_PHONE_NUMBERS` · *No account list* `GET_ACCOUNTS`.
- The `<uses-permission>` entries (including `uses-permission-sdk-23`) are removed from the patched manifest. Unlike *revoke* in the Permissions tab this is a property of the installed APK: it survives restarts, works without a running Shizuku, and the app cannot ask again (`requestPermissions` returns "denied" without a dialog). Without `INTERNET` the app's UID cannot open network sockets at all.
- **Proof:** the manifest of the **signed** APK is compared with the original. If a selected permission is still in it, nothing is installed. Afterwards the same start test as for trackers runs.
- Not promised by the platform below a certain `targetSdk`, and reported as such in the result: zeroed advertising ID only from targetSdk 33 (Google documentation), app-list filtering only from targetSdk 30.
- Without a clone the patched app replaces the original (data loss, see above). The app has to cope with "denied" itself; apps that need the internet stop working without it.
- **Not yet tested on a device** — only unit tests with aapt2 on synthetic and real manifests.

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

### Colours

The app uses a **complete Material 3 palette** derived systematically from a calm petrol/teal seed (light and dark), with red reserved for real errors. Menu of the main list → **System colors (Material You)**: on (default) = colours from the system wallpaper (Android 12+), off = the app's own petrol palette; the choice is remembered. On Android 11 and older the entry is greyed out. Measured contrast: worst text pair 6.49:1 (light) / 5.46:1 (dark) — target was 4.5:1.

### Signature

Releases are signed with the debug certificate, SHA-256 `652394f7…7226e` (full value: `652394f75d9ed1ea168561e4056556fd82d3645a6c88020cd30b18c0a3d7226e`). An update can only be installed over an existing install if it has the **same signature**; an APK you build yourself with a different debug key will not update the released app (uninstall first, which deletes app data).

### License

This project is under the **GNU General Public License, version 3.0** — full text in [LICENSE](LICENSE).

Reason: the app bundles a build of a CalcYou fork (`app/src/main/res/raw/calcyou.apk`) as a raw resource for the calculator vault, and that is GPL-3.0 itself. Since the app is distributed publicly anyway, the whole project uses the same license — consistent and legally clean.

Because the repository is public, the source that belongs to every released package is available in the same place (repository + releases). There is **no warranty**: patching other apps can damage them, and the Stealth tab is used at your own risk.

---

### Languages / Sprachen

The interface ships in English (base) plus 8 translations — 625 strings each, complete (every string translated, no gaps).

| Language | Locale | Coverage |
|---|---|---|
| German/Deutsch | `values-de` | 372/372 (100%) |
| Spanish/Español | `values-es` | 372/372 (100%) |
| French/Français | `values-fr` | 372/372 (100%) |
| Hindi/हिन्दी | `values-hi` | 372/372 (100%) |
| Japanese/日本語 | `values-ja` | 372/372 (100%) |
| Portuguese (Brazil) | `values-pt-rBR` | 372/372 (100%) |
| Russian/Русский | `values-ru` | 372/372 (100%) |
| Chinese (Simplified)/简体中文 | `values-zh-rCN` | 372/372 (100%) |

Alle Übersetzungen sind maschinell geprüft (Platzhalter, Pluralformen, XML-Struktur) — `R.array.*`-Ressourcen bleiben unberührt. English is the base, so any device with an unsupported language falls back to English instead of German.
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
  - **Nur bei Bedarf (neu, noch nicht am Gerät getestet):** Die App bleibt geschlossen und gesperrt (`am force-stop` + `pm suspend` oder `pm disable-user` oder **nur beenden** – allein `am force-stop`, damit die App weiter aus jedem Launcher wie Nova startbar ist; der Wächter erkennt den Start und beendet sie nach dem Verlassen wieder) und läuft nur, nachdem du sie über ihre eigene Verknüpfung auf dem Startbildschirm geöffnet hast. Nach dem Verlassen wird sie nach 10 s / 1 Min. / 5 Min. oder nur beim Bildschirm-Aus wieder gesperrt; das Einschalten des Bildschirms gibt sie nie frei. Das Verlassen wird über Nutzungsereignisse erkannt (Nutzungszugriff erteilt sich die App per Shizuku), sonst über `dumpsys activity`. Zum Öffnen muss Shizuku laufen; bei *Deaktivieren* lässt sich die App auch ohne Shizuku in den System-Einstellungen wieder aktivieren. Solange sie gesperrt ist, kommen Nachrichten, Wecker und Hintergrund-Sync der App nicht an. **Eigene Starter** (z. B. AppDialer NXT) können eine eingefrorene App über die exportierte Aktion `de.minipc.appopsshizuku.action.OPEN_ON_DEMAND` (Extra `pkg`) öffnen; der Aufrufer muss `startActivityForResult` nutzen, damit Android meldet, wer anfragt, und jeder neue Starter wird einmal gefragt (*Immer erlauben* / *Nur diesmal* / *Ablehnen*; im Reiter *Verhalten* widerrufbar).
- **Tarnung / Stealth** (Reiter *Tarnung*; die Schalter liegen derzeit noch im Bildschirm *Komponenten*): patcht die APK einer App auf dem Gerät und signiert sie neu. Aus dem Verlauf ausblenden, Launcher-Icon entfernen, Verlaufs-Vorschau schwärzen (`FLAG_SECURE`), VPN-Pflicht, als Taschenrechner tarnen (Vault), app-spezifisches Geräte-Spoofing (Build-Felder, Android-ID, IMEI/IMSI) oder als parallelen Klon installieren.
- **Netz & Tracker** (Reiter *Netz & Tracker*):
  - Hintergrund-Datenverkehr sperren,
  - App komplett offline schalten,
  - App auf Androids Hintergrund-Sperrliste setzen,
  - Tracker-Erkennung: liest das Manifest der App und listet bekannte Bausteine (Firebase Analytics/DataTransport, Crashlytics, Facebook SDK, AdMob, Audience Network, Sentry, Mixpanel, Braze, AppsFlyer, Adjust, Kochava).
  
  Jeder Schalter wird durch Zurücklesen vom Gerät geprüft. „Alles zurücksetzen" macht nur rückgängig, was diese App selbst geändert hat.

- **Neu, noch nicht am Gerät getestet:**
  - **Zugriffsprotokoll** (Reiter *Berechtigungen*): letzte Nutzung, abgewehrte Versuche und „wird gerade benutzt" je Berechtigung, aus einem einzigen `cmd appops get`. Eine Empfehlungskarte nennt Berechtigungen, die erteilt, aber seit 30 Tagen ungenutzt oder für die App-Kategorie unüblich sind (Spiele, Medien, Nachrichten) — geändert wird erst nach Tipp auf *Entziehen*.
  - **Nur während Nutzung**: zusätzlicher Modus für Berechtigungen ohne Vordergrund-Modus in Android (Kontakte, Kalender, Anrufliste, Medien, Konten …). Erteilt, solange die App vorne ist; entzogen 10 s nach dem Verlassen oder beim Bildschirm-Aus (Android beendet die App dann im Hintergrund). Läuft im Bildschirm-Wächter.
  - **Nach Berechtigung** (Menü): eine Berechtigung wählen und sehen, welche Apps sie haben und wann sie sie zuletzt benutzt haben; je App umschalten oder allen Nutzer-Apps auf einmal entziehen (System-Apps nie).
  - **Statuszeile in der App-Liste** (blockiert, offline, keine Hintergrunddaten, nur bei Bedarf, nur während Nutzung, Quarantäne) und Filter-Chip *Geändert* — aus den eigenen Speichern, ohne Shell-Aufruf.
  - **Quarantäne für neue Apps** (Menü, standardmäßig aus): nach dem Einschalten installierte Apps bekommen keine Hintergrunddaten, keine Zwischenablage und keinen Hintergrund-Lauf, bis du sie im Reiter *Berechtigungen* freigibst.
  - **Systemweit** (Menü): *Lockdown* entzieht allen Nutzer-Apps Kamera/Mikrofon (wahlweise Standort) und gibt sie genau diesen Apps zurück — auch als Schnelleinstellungs-Kachel. *Privates DNS* mit Vorlagen (AdGuard, Mullvad adblock, Quad9, Cloudflare) oder eigenem Host, mit „Zurück auf vorher". *Mächtige Rechte*: Bedienungshilfen, Benachrichtigungszugriff, Tastaturen, Standard-Apps (SMS, Telefon, Assistent, Browser), dauerhaftes VPN und Geräteadministratoren — entziehbar, wo die Shell es darf, sonst ein Knopf zu den Systemeinstellungen.
  - Blockieren einer Berechtigung setzt jetzt zusätzlich `user-fixed`, die App kann also nicht einfach erneut fragen; Erteilen und „Alle auf Standard" heben das wieder auf.

- **Weitere Datenschutz-Werkzeuge (neu, noch nicht am Gerät getestet):**
  - **Änderungsprotokoll** (Menü): jede Änderung dieser App, Berechtigungs- und Netz-Schalter je Eintrag *rückgängig*.
  - **Profil-Vorlagen** (Reiter Berechtigungen): Spiel / Messenger / Banking per Tipp, als JSON teilbar.
  - **Netzverkehr** (Menü): Verkehr je App über 7 Tage, Hintergrund getrennt; Apps mit viel Hintergrundverkehr, die man selten öffnet, stehen oben, mit „Hintergrund sperren" per Tipp.
  - **Aktivität** (Menü): welche Apps das Gerät wecken, Jobs planen oder Sensoren abfragen (aus dumpsys, heuristisch).
  - **Automatische Regeln** (Menü): nach Zeit (Tage + Fenster, auch über Nacht) oder WLAN (verbunden / nicht), Aktion = Lockdown oder gewählte Apps offline. Der WLAN-Name wird erst gelesen, nachdem du dieser App bei der ersten Nutzung die Standortberechtigung erteilt hast (die App hat selbst keinen Netzzugriff). Verbirgt Android den Namen, solange die App im Hintergrund läuft, wird die Regel übersprungen (nicht abgeschaltet), bis du die App öffnest.
  - **Arbeitsprofil** (Menü): App Ops Shizuku kann selbst ein verwaltetes Profil anlegen (eigener Profilbesitzer) und Apps hineinverschieben; in ein vorhandenes Profil (z. B. Shelter) verschiebt es Apps auch allein mit Shell-Rechten.
  - **Platzhalter-Daten statt Sperre (Beta-Patch)**: im neu signierten Klon/Patch eine Null-Werbe-ID und einen festen Ort auf Stadtebene statt der echten Werte liefern, damit Apps, die bei „verweigert" abstürzen, weiterlaufen.
  - **Neustart-Hinweis**: nach einem Neustart (Shizuku läuft noch nicht) meldet eine Benachrichtigung, dass der eingerichtete Schutz inaktiv ist; nach dem Verbinden bietet eine Leiste an, abgewichene Einstellungen wiederherzustellen.

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

**Berechtigungen entfernen (Beta)** — sechs Ankreuzfelder und eine Zeile, welche dieser Berechtigungen die App überhaupt deklariert:
- *Kein Internet (Offline-Klon)* `INTERNET` · *Keine Werbe-ID* `com.google.android.gms.permission.AD_ID` und die Privacy-Sandbox-Rechte `ACCESS_ADSERVICES_*` · *Installierte Apps nicht sichtbar* `QUERY_ALL_PACKAGES` · *Kein Standort* `ACCESS_FINE/COARSE/BACKGROUND_LOCATION` · *Kein Telefonstatus / keine Rufnummer* `READ_PHONE_STATE`, `READ_PHONE_NUMBERS` · *Keine Kontenliste* `GET_ACCOUNTS`.
- Die `<uses-permission>`-Einträge (auch `uses-permission-sdk-23`) werden aus dem gepatchten Manifest entfernt. Anders als *Entziehen* im Reiter Berechtigungen ist das eine Eigenschaft der installierten APK: Es übersteht Neustarts, wirkt ohne laufendes Shizuku, und die App kann nicht erneut fragen (`requestPermissions` liefert ohne Dialog „abgelehnt"). Ohne `INTERNET` kann die UID der App überhaupt keine Netz-Sockets öffnen.
- **Beweis:** Das Manifest der **signierten** APK wird mit dem Original verglichen. Steht eine gewählte Berechtigung noch drin, wird nichts installiert. Danach läuft dieselbe Startprobe wie bei den Trackern.
- Unterhalb eines bestimmten `targetSdk` von der Plattform nicht zugesagt und im Ergebnis so gemeldet: Nullen-Werbe-ID erst ab targetSdk 33 (Google-Doku), Filter der App-Liste erst ab targetSdk 30.
- Ohne Klon ersetzt die gepatchte App das Original (Datenverlust, siehe oben). Mit „abgelehnt" muss die App selbst zurechtkommen; Apps, die Internet brauchen, funktionieren ohne nicht mehr.
- **Noch nicht am Gerät getestet** — bisher nur Unit-Tests mit aapt2 an synthetischen und echten Manifesten.

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

### Farben

Die App nutzt eine **vollständige Material-3-Palette**, systematisch aus einer ruhigen Petrol-Startfarbe abgeleitet (hell und dunkel), Rot bleibt echten Fehlern vorbehalten. Menü der Hauptliste → **Systemfarben (Material You)**: an (Standard) = Farben aus dem Hintergrundbild des Systems (Android 12+), aus = die eigene Petrol-Palette; die Wahl bleibt gespeichert. Auf Android 11 und älter ist der Eintrag ausgegraut. Gemessener Kontrast: schlechtester Textwert 6,49:1 (hell) / 5,46:1 (dunkel) — Ziel war 4,5:1.

### Signatur

Releases sind mit dem Debug-Zertifikat signiert, SHA-256 `652394f7…7226e` (vollständig: `652394f75d9ed1ea168561e4056556fd82d3645a6c88020cd30b18c0a3d7226e`). Ein Update lässt sich nur mit **gleicher Signatur** über eine bestehende Installation einspielen; ein selbst gebautes APK mit anderem Debug-Schlüssel aktualisiert die veröffentlichte App nicht (erst deinstallieren, dabei gehen die App-Daten verloren).

### Lizenz

Dieses Projekt steht unter der **GNU General Public License, Version 3.0** — vollständiger Text in [LICENSE](LICENSE).

Grund: Die App bündelt einen Build eines CalcYou-Forks (`app/src/main/res/raw/calcyou.apk`) als Raw-Ressource für den Taschenrechner-Vault, und der ist selbst GPL-3.0. Da die App ohnehin öffentlich verteilt wird, steht das ganze Projekt unter derselben Lizenz — konsistent und rechtlich sauber.

Weil das Repository öffentlich ist, ist der Quellcode zu jedem veröffentlichten Paket an derselben Stelle verfügbar (Repository + Releases). Es gibt **keine Gewährleistung**: Das Patchen fremder Apps kann sie beschädigen, der Tarnungs-Reiter wird auf eigenes Risiko benutzt.
