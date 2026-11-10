### English

**App Ops Shizuku 1.20.2** — "Only on demand" now also opens frozen apps from other launchers and locks them again when the system wakes them up in the background.

**Why:** a frozen on-demand app could only be released through its own pinned shortcut, because the launcher activity is deliberately not exported. And a frozen app that something else started kept running — `pm suspend` blocks activities, but not jobs, broadcasts or service starts.

**What is new**

- **External launchers may open on-demand apps.** A new exported alias (`de.minipc.appopsshizuku.action.OPEN_ON_DEMAND`, extra `pkg`) forwards to the same activity. Only apps set to *Only on demand* are released.
- **The caller has to identify itself** via `startActivityForResult`. Unknown callers and the target app itself are refused; a new launcher is asked once: allow always / this time only / deny. Allowed launchers are stored on the device (not in the export) and can be revoked in the Behaviour tab.
- **Woken apps are stopped again.** The lock state now distinguishes "frozen but running again" (`suspended=true`, `stopped=false`) and stops those apps on the next sweep and on screen-off.
- **A new wake watchdog** checks every 5 minutes of screen-on time, because nothing else looks between two screen-off events. It only queries apps with lock type *Freeze* — *Deactivate* cannot be woken and *Stop only* may keep running until you leave it, so no shell call is wasted on them.
- The Behaviour tab and the README state the limit openly: freezing does not prevent wake-ups completely, only *Deactivate* does.

**Honest limits**

- **Not tested on a device.** Verified: build, unit tests, signature and that the new manifest alias is in the APK. Open: whether AppDialer NXT's request is accepted on the target ROM, and how reliably the sweep catches a woken app.
- Apps locked with *Freeze* still cannot be released from the system settings — only through a starter or the shortcut, with a running Shizuku.
- The watchdog costs one shell call per frozen app every 5 minutes while the screen is on; with many frozen apps that is measurable, which is why it is limited to the lock type that needs it.

**Tests:** 340 unit tests, 0 failures, 1 skipped.

Download: https://github.com/pikatchu2k3/AppOpsShizuku/releases/latest — this repository is **private**; for updates through [Obtainium](https://github.com/ImranR98/Obtainium) you need a GitHub token with read access.

### Deutsch

**App Ops Shizuku 1.20.2** — „Nur bei Bedarf" öffnet eingefrorene Apps jetzt auch aus anderen Startern und sperrt sie wieder, wenn das System sie im Hintergrund weckt.

**Warum:** Eine eingefrorene Bedarfs-App ließ sich bisher nur über ihre eigene angeheftete Verknüpfung freigeben, weil die Start-Activity absichtlich nicht exportiert ist. Und eine eingefrorene App, die etwas anderes gestartet hat, lief weiter — `pm suspend` blockiert Activities, aber keine Jobs, Broadcasts oder Dienst-Starts.

**Was neu ist**

- **Fremde Starter dürfen Bedarfs-Apps öffnen.** Ein neuer exportierter Alias (`de.minipc.appopsshizuku.action.OPEN_ON_DEMAND`, Extra `pkg`) leitet auf dieselbe Activity. Freigegeben wird weiterhin nur, was auf „Nur bei Bedarf" steht.
- **Der Aufrufer muss sich zu erkennen geben** (über `startActivityForResult`). Unbekannte Aufrufer und die Ziel-App selbst werden abgelehnt; ein neuer Starter wird einmal gefragt: immer erlauben / nur diesmal / ablehnen. Erlaubte Starter liegen gerätebezogen im Laufzeitzustand (nicht im Export) und lassen sich im Reiter Verhalten zurücknehmen.
- **Geweckte Apps werden wieder beendet.** Der Sperrzustand unterscheidet jetzt „eingefroren, aber wieder am Laufen" (`suspended=true`, `stopped=false`) und beendet solche Apps beim nächsten Durchlauf und beim Bildschirm-Aus.
- **Neuer Weck-Wächter** alle 5 Minuten Wachzeit, weil zwischen zwei Bildschirm-Aus-Ereignissen niemand nachsieht. Er fragt nur Apps mit Sperrart *Einfrieren* ab — *Deaktivieren* kann niemand wecken, *Nur beenden* darf bis zum Verlassen laufen, dafür wird kein Shell-Aufruf ausgegeben.
- Reiter Verhalten und README nennen die Grenze offen: Einfrieren verhindert Weckrufe nicht vollständig, das schafft nur *Deaktivieren*.

**Ehrliche Grenzen**

- **Nicht am Gerät getestet.** Belegt: Build, Unit-Tests, Signatur und dass der neue Manifest-Alias im APK steht. Offen: ob der Aufruf von AppDialer NXT auf dem Ziel-ROM angenommen wird und wie zuverlässig der Durchlauf eine geweckte App erwischt.
- Mit *Einfrieren* gesperrte Apps lassen sich weiterhin nicht aus den System-Einstellungen freigeben — nur über einen Starter oder die Verknüpfung mit laufendem Shizuku.
- Der Wächter kostet pro eingefrorener App einen Shell-Aufruf alle 5 Minuten bei hellem Bildschirm; bei vielen eingefrorenen Apps ist das messbar — deshalb nur für die Sperrart, die es braucht.

**Tests:** 340 Unit-Tests, 0 Fehler, 1 übersprungen.

Download: https://github.com/pikatchu2k3/AppOpsShizuku/releases/latest — dieses Repository ist **privat**; für Updates über [Obtainium](https://github.com/ImranR98/Obtainium) ist ein GitHub-Token mit Lesezugriff nötig.

<sub>Paket `de.minipc.appopsshizuku` · versionCode 42 · debug-signiert (Zertifikat SHA-256 `652394f7…7226e`, updatefähig über 1.20.1) · targetSdk 35 · Lizenz GPL-3.0</sub>
