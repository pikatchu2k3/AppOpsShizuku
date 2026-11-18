### English

**App Ops Shizuku 1.20.6** — when the manufacturer blocks disabling an app, the app now tries other ways and tells you honestly which one worked.

**New: "Can't be disabled? Try other ways …"**

Every disable is now read back from the device. If it did not take effect (typical for OnePlus/OxygenOS, which answers `pm disable-user` with *new state: default*), the new button runs a chain and stops at the first step that really works:

1. `pm disable-until-used` — a different disabled state.
2. **Remove for this user** (`pm uninstall -k --user 0`) — the APK and its data stay on the device; the app shows up as *(removed)* in the list and is restored with one tap (`cmd package install-existing`).
3. **Hide as device owner** — only if device owner has been set up (see below).
4. **Neutralize** — freeze, force-stop, deny background ops, standby bucket *restricted*.

Each step is shown with ✓/✗ and the device's own reason. System UI, Settings, phone, providers, permission controller, the current launcher and keyboard, Shizuku and this app are never offered.

**New: device owner (optional, in Settings)**

`dpm set-device-owner` from the shell lets this app hide packages through `DevicePolicyManager.setApplicationHidden` — a different code path that some manufacturers do not block. Android only allows it when there are **no accounts** and **no second user or work profile** on the device. The app removes nothing by itself and shows the device's exact answer. *Give up* first unhides every app it hid, then releases device owner. The same is possible from a PC: `scripts/device-owner.sh check|set|remove` (adb).

**Honest limits**

- **No device run.** The chain, the removal and the restore are verified by unit tests (the decision logic) and by reading the code paths — none of it ran on a phone here. Whether OxygenOS refuses step 1 on your device, and whether removal or device owner gets through, has to be confirmed there.
- Removing a system app can break other functions; the confirmation says so, and restoring is one tap.
- A PC does not give more rights than Shizuku: adb runs as the same shell user. Without root, device owner is the only real step up.
- Neutralize is weaker than disabling: the system can still start a frozen app in the background.

**Tests:** 351 unit tests, 0 failures, 1 skipped (45 test classes).

Download: https://github.com/pikatchu2k3/AppOpsShizuku/releases/latest — this repository is **private**; for updates through [Obtainium](https://github.com/ImranR98/Obtainium) you need a GitHub token with read access.

### Deutsch

**App Ops Shizuku 1.20.6** — wenn der Hersteller das Deaktivieren sperrt, versucht die App jetzt andere Wege und sagt ehrlich, welcher gewirkt hat.

**Neu: „Lässt sich nicht deaktivieren? Andere Wege …“**

Jedes Deaktivieren wird jetzt vom Gerät zurückgelesen. Hat es nicht gewirkt (typisch bei OnePlus/OxygenOS: `pm disable-user` antwortet mit *new state: default*), startet der neue Knopf eine Kette und stoppt beim ersten Schritt, der wirklich greift:

1. `pm disable-until-used` — ein anderer Deaktiviert-Zustand.
2. **Für diesen Nutzer entfernen** (`pm uninstall -k --user 0`) — APK und Daten bleiben auf dem Gerät; die App erscheint als *(entfernt)* in der Liste und wird mit einem Tipp wiederhergestellt (`cmd package install-existing`).
3. **Als Geräteinhaber verstecken** — nur wenn der Geräteinhaber eingerichtet ist (siehe unten).
4. **Neutralisieren** — einfrieren, beenden, Hintergrund-Ops verweigert, Standby-Stufe *eingeschränkt*.

Jeder Schritt wird mit ✓/✗ und dem Grund des Geräts angezeigt. System-UI, Einstellungen, Telefon, Provider, Berechtigungs-Controller, der aktuelle Launcher und die Tastatur, Shizuku und diese App werden nie angeboten.

**Neu: Geräteinhaber (optional, in den Einstellungen)**

`dpm set-device-owner` aus der Shell erlaubt dieser App, Apps über `DevicePolicyManager.setApplicationHidden` zu verstecken — ein anderer Weg, den manche Hersteller nicht sperren. Android lässt das nur zu, wenn **keine Konten** und **kein zweiter Nutzer bzw. Arbeitsprofil** auf dem Gerät sind. Die App entfernt von sich aus nichts und zeigt die genaue Antwort des Geräts. *Abgeben* holt zuerst alle versteckten Apps zurück und gibt dann den Geräteinhaber ab. Dasselbe geht vom PC: `scripts/device-owner.sh check|set|remove` (adb).

**Ehrliche Grenzen**

- **Kein Gerätelauf.** Kette, Entfernen und Wiederherstellen sind durch Unit-Tests (die Entscheidungslogik) und das Nachlesen der Codepfade belegt — auf einem Handy lief davon hier nichts. Ob OxygenOS Schritt 1 auf deinem Gerät ablehnt und ob Entfernen oder Geräteinhaber durchkommt, muss dort bestätigt werden.
- Das Entfernen einer System-App kann andere Funktionen beeinträchtigen; die Bestätigung sagt das, und Wiederherstellen ist ein Tipp.
- Ein PC gibt keine höheren Rechte als Shizuku: adb läuft als derselbe Shell-Nutzer. Ohne Root ist der Geräteinhaber die einzige echte Stufe nach oben.
- Neutralisieren ist schwächer als Deaktivieren: das System kann eine eingefrorene App weiter im Hintergrund starten.

**Tests:** 351 Unit-Tests, 0 Fehler, 1 übersprungen (45 Testklassen).

Download: https://github.com/pikatchu2k3/AppOpsShizuku/releases/latest — dieses Repository ist **privat**; für Updates über [Obtainium](https://github.com/ImranR98/Obtainium) ist ein GitHub-Token mit Lesezugriff nötig.

<sub>Paket `de.minipc.appopsshizuku` · versionCode 46 · debug-signiert (updatefähig über 1.20.5) · targetSdk 35 · Lizenz GPL-3.0</sub>
