### English

**App Ops Shizuku 1.19.2** — new mode "Only on demand": an app stays closed and locked until you open it yourself.

**Why:** some apps switch themselves back on when the screen comes on. "Only on demand" is a separate card in the Behaviour tab, next to "Stop when the screen turns off" — whose "release on screen-on" is exactly that wake-up.

**How it works**
- **Locked** means `am force-stop` plus either **freeze** (`pm suspend`, icon stays, greyed out, only Shizuku can release it) or **deactivate** (`pm disable-user`, icon disappears, can be re-enabled in the system settings without Shizuku).
- A **home-screen shortcut** with the target app's icon and name opens it: the shortcut releases the app, remembers the time and starts it. Opening needs a running Shizuku — after a reboot, start Shizuku first. Without Shizuku you get a dialog instead of a silent failure.
- After you leave the app it is stopped and locked again — after **10 seconds, 1 minute, 5 minutes**, or **only when the screen turns off**. Turning the screen on never releases it.
- Leaving is detected via usage events (the app grants itself usage access through Shizuku); otherwise via `dumpsys activity`, which is a bit less reliable.
- **Success is only reported after reading the state back** from the device (`dumpsys package`, "User 0:" line). If the device does not take the lock, the app says so with the raw message.
- Switching the mode off releases the app and greys out the shortcut. While an app is locked, its messages, alarms and background sync do not arrive.

**Honest limits**
- **Not tested on a device.** Open: whether the shortcut survives launcher restarts, whether the lock really takes effect on the target ROMs (MIUI/HyperOS untested), and how reliably leaving is detected without usage access.
- Apps locked with **freeze** cannot be released from the system settings — only via the shortcut with a running Shizuku.
- The release action of the screen-off card never applies to apps with "Only on demand"; the app shows a note instead.

**Tests:** 263 unit tests, 0 failures, 1 skipped. New: `OnDemandTest` with 13 tests.

Download: https://github.com/pikatchu2k3/AppOpsShizuku/releases/latest — this repository is **private**; for updates through [Obtainium](https://github.com/ImranR98/Obtainium) you need a GitHub token with read access.

### Deutsch

**App Ops Shizuku 1.19.2** — neuer Modus „Nur bei Bedarf": Die App bleibt geschlossen und gesperrt, bis du sie selbst öffnest.

**Warum:** Manche Apps aktivieren sich beim Einschalten des Bildschirms selbst wieder. „Nur bei Bedarf" ist eine eigene Karte im Reiter Verhalten, neben „Beim Bildschirm-Aus anhalten" — dessen „beim Einschalten freigeben" ist genau dieses Wiederaufwachen.

**So funktioniert es**
- **Gesperrt** heißt `am force-stop` plus entweder **Einfrieren** (`pm suspend`, Icon bleibt grau, nur Shizuku kann freigeben) oder **Deaktivieren** (`pm disable-user`, Icon verschwindet, in den System-Einstellungen auch ohne Shizuku wieder aktivierbar).
- Eine **Verknüpfung auf dem Startbildschirm** (Icon und Name der Ziel-App) öffnet sie: Die Verknüpfung gibt frei, merkt sich die Öffnungszeit und startet die App. Zum Öffnen muss Shizuku laufen – nach einem Neustart zuerst Shizuku starten. Ohne Shizuku kommt ein Dialog statt eines stillen Fehlers.
- Verlässt du die App, wird sie wieder beendet und gesperrt – nach **10 Sekunden, 1 Minute, 5 Minuten** oder **erst beim Bildschirm-Aus**. Das Einschalten des Bildschirms gibt sie nie frei.
- Das Verlassen wird über Nutzungsereignisse erkannt (die App erteilt sich den Nutzungszugriff per Shizuku), sonst über `dumpsys activity` – etwas weniger zuverlässig.
- **Erfolg wird erst gemeldet, nachdem der Zustand frisch vom Gerät gelesen wurde** (`dumpsys package`, Zeile „User 0:"). Nimmt das Gerät die Sperre nicht an, steht die Rohmeldung in der App.
- Ausschalten gibt die App frei und graut die Verknüpfung aus. Solange eine App gesperrt ist, kommen ihre Nachrichten, Wecker und Hintergrund-Sync nicht an.

**Ehrliche Grenzen**
- **Nicht am Gerät getestet.** Offen: ob die Verknüpfung Launcher-Neustarts übersteht, ob die Sperre auf den Ziel-ROMs wirklich greift (MIUI/HyperOS ungeprüft) und wie zuverlässig das Verlassen ohne Nutzungszugriff erkannt wird.
- Mit **Einfrieren** gesperrte Apps lassen sich nicht aus den System-Einstellungen freigeben — nur über die Verknüpfung mit laufendem Shizuku.
- Die Freigabe-Aktion der Bildschirm-Aus-Karte gilt nie für Apps mit „Nur bei Bedarf"; die App zeigt stattdessen einen Hinweis.

**Tests:** 263 Unit-Tests, 0 Fehler, 1 übersprungen. Neu: `OnDemandTest` mit 13 Tests.

Download: https://github.com/pikatchu2k3/AppOpsShizuku/releases/latest — dieses Repository ist **privat**; für Updates über [Obtainium](https://github.com/ImranR98/Obtainium) ist ein GitHub-Token mit Lesezugriff nötig.

<sub>Paket `de.minipc.appopsshizuku` · versionCode 36 · debug-signiert (Zertifikat SHA-256 `652394f7…7226e`, updatefähig über 1.19.1) · targetSdk 35 · Lizenz GPL-3.0</sub>
