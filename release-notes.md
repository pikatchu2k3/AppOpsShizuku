### English

**App Ops Shizuku 1.15.0** — new tab *Network & Trackers*.

**New**
- Fourth tab in the app detail screen: three network switches that really work through Shizuku (shell UID, no root) — block background data, take an app completely offline, and push it onto Android's restrict-background list. Every switch is read back from the device; the device state is the truth, not the tap.
- The firewall chain is checked as well: while it is off, Android stores the deny bit and still reports "deny" but blocks nothing. The switch now enables the chain itself and turns it back off when you reset (only if the app enabled it).
- "Completely offline" is disabled for system apps instead of always failing.
- Tracker overview (read-only): reads the app's manifest and lists known building blocks — Firebase Analytics, Firebase DataTransport, Crashlytics, Facebook SDK (app events), AdMob, Facebook Audience Network, Sentry, Mixpanel, Braze, AppsFlyer, Adjust, Kochava.
- "Reset all" only undoes what this app changed.

**Fixed**
- Component lists no longer lose already disabled components (`MATCH_DISABLED_COMPONENTS`).
- `ShellResult.isFailure()` no longer reports component names like `…ErrorReportingService` as errors.

**Honest limits**
- Disabling single *components* of other apps is not offered: Android 15/16 refuses it to the shell (`SecurityException: Shell cannot change component state`).
- The switches stop network traffic, not the tracking code inside the app. Only the APK patch (Stealth tab) removes code.
- The tracker list only knows building blocks with manifest components — "not found" does not mean "not present".

Download: https://github.com/pikatchu2k3/AppOpsShizuku/releases/latest
Automatic updates: add this repository to [Obtainium](https://github.com/ImranR98/Obtainium) (source: GitHub Releases).

Build: `:app:assembleDebug` · package `de.minipc.appopsshizuku` · versionCode 28 · debug signature (certificate SHA-256 `652394f7…7226e`).

### Deutsch

**App Ops Shizuku 1.15.0** — neuer Reiter *Netz & Tracker*.

**Neu**
- Vierter Reiter in der App-Detailansicht: drei Netzschalter, die über Shizuku (Shell-Rechte, kein Root) wirklich wirken — Hintergrund-Datenverkehr sperren, App komplett offline schalten und auf die Hintergrund-Sperrliste setzen. Jeder Schalter wird vom Gerät zurückgelesen; der Gerätezustand zählt, nicht der Tipp.
- Die Firewall-Kette wird mitgeprüft: Ist sie aus, merkt sich Android das Sperr-Bit und meldet trotzdem „deny", sperrt aber nichts. Der Schalter aktiviert sie jetzt selbst und schaltet sie beim Zurücksetzen wieder ab (nur wenn die App sie eingeschaltet hat).
- „Komplett offline" ist bei System-Apps gesperrt, statt immer zu scheitern.
- Tracker-Übersicht (nur lesen): liest das Manifest der App und zeigt bekannte Bausteine — Firebase Analytics, Firebase Datentransport, Crashlytics, Facebook SDK (App-Events), AdMob, Facebook Audience Network, Sentry, Mixpanel, Braze, AppsFlyer, Adjust, Kochava.
- „Alles zurücksetzen" nimmt nur zurück, was diese App geändert hat.

**Behoben**
- Komponenten-Listen verlieren keine bereits deaktivierten Komponenten mehr (`MATCH_DISABLED_COMPONENTS`).
- `ShellResult.isFailure()` meldet Namen wie `…ErrorReportingService` nicht mehr fälschlich als Fehler.

**Ehrliche Grenzen**
- Das Abschalten einzelner *Komponenten* fremder Apps wird bewusst nicht angeboten: Android 15/16 verweigert es der Shell (`SecurityException: Shell cannot change component state`).
- Die Schalter stoppen Netzverkehr, nicht den Tracker-Code in der App. Code entfernt nur der APK-Patch (Reiter Tarnung).
- Die Tracker-Liste kennt nur Bausteine mit Manifest-Komponenten — „nicht gefunden" heißt nicht „nicht enthalten".

Download: https://github.com/pikatchu2k3/AppOpsShizuku/releases/latest
Automatische Updates: dieses Repository in [Obtainium](https://github.com/ImranR98/Obtainium) hinzufügen (Quelle: GitHub Releases).

Build: `:app:assembleDebug` · Paket `de.minipc.appopsshizuku` · versionCode 28 · Debug-Signatur (Zertifikat SHA-256 `652394f7…7226e`).
